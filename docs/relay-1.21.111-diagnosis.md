# WRelay on Minecraft Bedrock 1.21.111 — join timeout + post-join freeze

Branch: `arena/ccafc76e-wclient` (base `d64959d`) · Date: 2026-10-11

## Symptom

On Bedrock **1.21.111** (protocol **844**, shared by 1.21.110–1.21.114):

1. the **first** join attempt times out and the game shows error code **creeper**;
2. a **second** attempt gets further in, but the client then freezes and times out.

"Creeper" is Bedrock's generic *the client did not complete/reach the connection in time*
error, so both symptoms point at something stalling the login/keep-alive exchange between
the game and the relay — not at a version mismatch. Version support is fine:

| where | value |
|---|---|
| `CodecRegistry` | protocol 844 → `Bedrock_v844` |
| `Bedrock_v844.minecraftVersion()` | `"1.21.111"` |
| `VersionDetector` | 844 → 1.21.110 – 1.21.114 |

## Findings (all from code, no device log yet)

### A. The login session was refreshed on the Netty event loop — fixed

`OnlineLoginPacketListener.beforeClientBound()` handled the game's `LoginPacket`. When the
cached `FullBedrockSession` had expired it called `refresh()` **inline**, on the same Netty
event loop that has to forward the login upstream and deliver the server's
`ServerToClientHandshake` / `PlayStatus` back to the game.

`refresh()` is blocking HTTP (`MinecraftRelays.refresh()`: 10 s connect / 15 s read timeouts,
`RetryHandler(2, …)`) and a Bedrock chain refresh makes several sequential calls, so the
refresh alone can occupy tens of seconds. While it runs, the game's login cannot complete and
the game gives up with **creeper**. On the next attempt the refreshed session is already
cached in memory, which is exactly why "the second try works".

Fix: the expiry check, the refresh and the start of the upstream connection now run on
`Dispatchers.IO` (`ensureSessionFresh()`, `@Volatile` session field).

### B. RakNet offline handshake cadence was left at 1000 ms — tuned

The upstream connection is a RakNet client. The offline handshake re-sends
`Request1`/`Request2` every `RAK_TIME_BETWEEN_SEND_CONNECTION_ATTEMPTS_MS`, **1000 ms by
default**, and the MTU probe size only steps down every 4 attempts
(`1400 → 1200 → 576` in `RakClientOfflineHandler`).

With the timeout profile used for normal servers (`EnhancedServerConfig.FAST`,
`connectionTimeout = 15 000 ms`) the small-MTU phase therefore only starts at ~8 s, leaving
very little time to complete the handshake on paths where 1400-byte fragmented datagrams are
dropped (mobile/CGNAT). That produces exactly the "first join times out, retry works" pattern.

Fix: the relay now sets `RAK_TIME_BETWEEN_SEND_CONNECTION_ATTEMPTS_MS = 500`, so the ladder
reaches 576 B at ~4 s and roughly twice as many attempts fit in the timeout window.

### C. The latency probe sends a packet that no longer exists — fixed

`LatencyTracker` probed RTT by sending `TickSyncPacket` (id 23) once per second, driven from
`GameSession.handlePacket()` on every `PlayerAuthInputPacket`. TickSync was **deregistered in
protocol 685**; on 844 `codec.getPacketDefinition(TickSyncPacket.class)` is `null`, so the
encoder (`BedrockPacketCodec.getPacketId`) throws on every probe and the write is dropped.
The relay now also refuses to write any packet the negotiated codec does not know
(`WRelaySession.isEncodable` / `dropUnsupported`) instead of letting the Netty encoder throw,
and on modern protocols the tracker samples the RakNet-level RTT of the upstream link
(`WRelaySession.upstreamPingMs`, from `RakSessionCodec`'s `ConnectedPing`/`Pong`) instead of
sending TickSync.

### D. Failures were logged as `null` — fixed

RakNet throws `ConnectTimeoutException` with no message, so every failed attempt printed
`Connection attempt N failed: null`. The relay now prints the effective connect options before
each attempt and renders exception cause chains (`describe()`), which is what a device log
needs to tell a handshake timeout from a refused/dropped connection.

## Why the freeze is likely the same stall

While the upstream connection is still being established (or retried), the game is already
waiting for the server's login sequence. The relay's retry loop for a normal server is
`FAST`: 3 attempts, 15 s RakNet timeout each, 1 s / 1.5 s backoff — i.e. the relay can still be
retrying tens of seconds after the game started its login. If the game's login window expires
mid-retry the client stays on the loading screen and eventually reports a timeout again, which
matches "joins, then freezes and times out".

Files changed:

- `relay/src/main/kotlin/com/retrivedmods/wrelay/listener/OnlineLoginPacketListener.kt`
- `relay/src/main/kotlin/com/retrivedmods/wrelay/connection/ConnectionManager.kt`
- `relay/src/main/kotlin/com/retrivedmods/wrelay/WRelaySession.kt`
- `app/src/main/java/com/retrivedmods/wclient/game/GameSession.kt`
- `app/src/main/java/com/retrivedmods/wclient/game/utils/combat/LatencyTracker.kt`

## How to verify

1. Install the CI build of this branch (`Actions → Android Build → artifact
   WClient-APK-<sha>.apk`) or build `:app:assembleDebug` locally.
2. Join a 1.21.111 server and watch logcat. Expected new lines:
   - `RakNet connect options: protocol=11 guid=… connectTimeout=15000ms sessionTimeout=20000ms handshakeRetry=500ms compatibilityMode=true`
   - `Connection attempt 1/3` / `Connection attempt 1 failed: … <cause chain>`
   - `Dropping unsupported packet TickSyncPacket: not present in the negotiated codec` (only
     if some other module still emits it)
3. If the freeze still happens, capture logcat around it and grep for
   `Connection attempt`, `Dropping unsupported`, `Error processing`, `Client disconnect`,
   `Server disconnect`.

## Remaining suspects (not fixed here)

- **Split-packet reassembly in the vendored RakNet.** `SplitPacketHelper` is keyed by
  `partId & 255` with no owner/part-id check (`RakUnhandledMessagesQueue` is also unbounded);
  upstream CloudburstMC/Network fixed this area after the vendored snapshot. Large
  server→client payloads (StartGame / block palette / chunks) are exactly what uses splitting,
  so a mis-assembled packet would show up as "joined, then nothing loads, then timeout".
- **Block/item mapping assets stop at protocol 800** (`assets/mcpedata/*/index.json`), so on
  protocol 844 the app resolves runtime block ids against the 1.21.8x table. That is a
  correctness issue for block names/states (not a crash), and worth a separate pass.
