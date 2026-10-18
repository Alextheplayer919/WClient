# WRelay on Minecraft Bedrock 1.21.111 — join timeout + post-join freeze

Branch: `arena/504ef958-neon` (base `3508be6`, the merge of `#13`) · Date: 2026-10-11

> **Status of the first pass (A–D below, merged as #13):** it compiles, it shipped, and it addresses the
> *first-join timeout* half of the symptom. The **post-join freeze was never caused by any of A–D**, so a
> build of that branch still freezes — as it says in its own closing note. Section **E** at the bottom is
> the root cause, confirmed against upstream CloudburstMC/Network and fixed here.

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

## The freeze: was two candidate mechanisms, is now one

**(a) The join window is exceeded while the relay is still connecting** — the first pass targeted
this (A + B), and the new `Connection attempt …/…` / `connected to …` lines show it working. It is
not what makes a *joined* world freeze.

**(b) Large-payload handling in the vendored RakNet** — confirmed, and it is the whole story. See E.

## E. Split-packet reassembly in the vendored RakNet — the freeze, fixed

Everything below is the same area the first pass pointed at but did not change. The vendored
`transport-raknet` is a snapshot older than Aug 2025, i.e. before upstream fixed this
(`048d182` sane split limits, `6b1a52d` part-ID aliasing, `c025d90` reassembly/ordering budgets,
`10c0065` 2048-part send cap). Every large server payload goes through it: StartGame's block
palette and item registry, and every chunk.

| # | defect in the vendored snapshot | what the player sees |
|---|---|---|
| E1 | Reassembly state lives in a `RoundRobinArray` of **256** slots indexed by `partId & 255`, and nothing checked that the helper found in a slot belonged to the incoming part. Part IDs are 16-bit on the wire, so while one split packet is incomplete, **any part of a packet 256 apart is fed into its helper** | two unrelated messages merge into one corrupt buffer → the game discards it; or a part index past the aliased helper's length throws `IllegalArgumentException` out of `channelRead`, which drops the rest of the datagram and surfaces as a session error |
| E2 | `SplitPacketHelper.expired()` **was never called** — no reassembly is ever given up on, and no slot is freed | one lost part pins a slot for the life of the session (guaranteeing an E1 alias) and holds its buffers; on a phone that is retained memory until the process is killed |
| E3 | No cap on how many parts a **sent** message is cut into. A Bedrock client acks every part of an oversized message and *then drops the message*, so it is never resent | the ordering channel that carried it stalls forever → everything queued behind it on that channel (chunks, entities) is never delivered → **joined, nothing loads, timeout** |
| E4 | `RakUnhandledMessagesQueue` (pre-activation buffer on the game-facing child channel) is unbounded and has no timeout; ordering/reliable index gaps are unbounded too | a connection that never activates hangs instead of failing, and a peer can grow the buffers without limit |

E1 + E2 + E3 are the freeze. Note E3 is *also* made more likely by fix **B**: the 500 ms retry
ladder reaches the 576 B MTU probe in ~4 s, and the part count is
`ceil(payloadBytes / (sessionMtu − MAXIMUM_ENCAPSULATED_HEADER_SIZE − RAKNET_DATAGRAM_HEADER_SIZE))`
with `sessionMtu = configuredMtu − 28` for IPv4. So a link that settles on 576 carries ~1.0 MiB per
message where 1400 carries ~2.6 MiB — both are exactly what `maxMessageBytes` in the new log line
reports. A fast handshake and a small MTU pull in opposite directions, which is why the limit is now
enforced and logged rather than silently exceeded.

### What changed

- `SplitPacketHelper` now records the **owning `partId`**, exposes `matches(packet)`
  (`partId` **and** `partCount`), rejects part counts over `RakConstants.MAXIMUM_RECEIVED_SPLIT_COUNT`
  (8192) and reports `getReassembledSize()` for budget accounting. Byte-for-byte upstream.
- `RakSessionCodec.getReassembledPacket()` drops a part that lands on a slot held by a different,
  unfinished reassembly instead of corrupting it, and only reuses a slot once its reassembly has
  completed or expired — which is what vanilla does too (256 in-flight, new parts dropped at the
  limit). Byte-for-byte upstream.
- `RakSessionCodec.onTick()` calls the new `evictExpiredSplitPackets()`, so `expired()` finally has a
  caller and stuck slots are freed after 30 s, then enforces the new `RAK_MAX_SPLIT_QUEUED_BYTES`
  (8 MB default) per session before disconnecting with `SPLIT_QUEUE_TOO_LONG`.
- `RakSessionCodec.createEncapsulated()` refuses a message that would need more than
  `RakConstants.MAXIMUM_SPLIT_COUNT` (2048) parts, **before** taking a split ID or ordering index, so
  the session carries on; it logs the MTU and the byte figures first, so this is visible in logcat
  instead of only as a hang.
- Gap bounds from the same upstream pass: reliable and ordering indices may not run more than
  `MAX_RECEIVE_GAP` (32768) ahead of the read index (`BAD_PACKET`), and the reorder buffer is bounded
  by `RAK_MAX_ORDERING_QUEUED_BYTES` (8 MB, `ORDERING_QUEUE_TOO_LONG`).
- `RakUnhandledMessagesQueue` is now bounded (128 messages / 256 KB) and times out with
  `TIMED_OUT` if the child channel never activates. Byte-for-byte upstream.
- New plumbing the above needs, mirroring the existing `RAK_MAX_QUEUED_BYTES`:
  `RakChannelOption.RAK_MAX_SPLIT_QUEUED_BYTES` / `RAK_MAX_ORDERING_QUEUED_BYTES`,
  `RakChannelConfig` + `DefaultRakSessionConfig` accessors, and the two `RakDisconnectReason` values.
- `WRelaySession.describeLink()` + one log line per link at session creation (`Upstream link: mtu=…
  maxMessageBytes=…`, `Client link: …`), because the size a batch may be is a property of the
  negotiated MTU, not of the 1400 the relay requests.

Deliberately **not** done: the cap is enforced in the transport, not in `WRelaySession.clientBound()`.
`BedrockBatchEncoder` coalesces several Bedrock packets into one RakNet message, so a per-Bedrock-packet
size check in the relay would be checking the wrong number — the transport is the only layer that sees
the real batch size.

## Files changed

First pass (merged as #13): `gh pr diff 13` — those files are not re-listed here, and `main` only has
that merge as a squashed commit, so `git show --stat` on it is useless.

Second pass (this PR):

- `relay/Network/transport-raknet/src/main/java/org/cloudburstmc/netty/util/SplitPacketHelper.java`
- `relay/Network/transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakSessionCodec.java`
- `relay/Network/transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakUnhandledMessagesQueue.java`
- `relay/Network/transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/RakConstants.java`
- `relay/Network/transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/RakDisconnectReason.java`
- `relay/Network/transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/config/{RakChannelOption,RakChannelConfig,DefaultRakSessionConfig}.java`
- `relay/Network/transport-raknet/src/test/java/org/cloudburstmc/netty/SplitPacketHelperTests.java` (new)
- `relay/src/main/kotlin/com/retrivedmods/neon/relay/{WRelay,WRelaySession}.kt`,
  `relay/src/main/kotlin/com/retrivedmods/neon/relay/connection/ConnectionManager.kt`

## How to verify

0. **Compile status: green.** `:app:assembleDebug` on PR #14 built with these changes
   (run 38123555712), which is the only automated check this area gets — no JDK or Maven
   access in the agent sandbox, so nothing was compiled or exercised locally before that, and
   no device log has been read yet. Behaviour on 1.21.111 is still unconfirmed until someone
   installs the APK.

1. Install the CI build of this branch (`Actions → Android Build → artifact
   `Neon-APK-<sha>.apk`, containing `Neon-debug-<sha>.apk`) or build
   `:app:assembleDebug` locally.
2. Join a 1.21.111 server and watch logcat. New lines from this pass:
   - `Upstream link: mtu=… maxMessageBytes=…` and `Client link: mtu=… maxMessageBytes=…` at session
     creation — if either MTU is not 1400, that link's per-message budget has shrunk with it.
   - `Refusing to send a N byte message: it needs M parts, more than the 2048 this peer can take at MTU X`
     — the relay dropped an oversized batch instead of stalling the channel. Missing chunk, not a freeze.
   - `Split reassembly holding N bytes, over the 8388608 byte limit; closing session` — a peer is
     deliberately withholding parts, which is the only way this fires.
   - Still relevant from the first pass: `RakNet connect options: …`, `Connection attempt N/3 …`.
3. If the freeze still happens, grep logcat for `Connection attempt`, `Refusing to send`, `Dropping
   unsupported`, `Error processing`, `Client disconnect`, `Server disconnect`, and for the Netty
   warning `io.netty.channel.AbstractChannelHandlerContext` — an unhandled `IllegalArgumentException`
   from the split cap would show up there rather than in the relay's own log lines.
4. Unit test: `SplitPacketHelperTests` covers the aliasing guard, `reassembledSize` accounting and the
   part-count limit. **Note** this module has no `useJUnitPlatform()`, so Gradle currently discovers no
   tests in it (`RakTests` never runs either) — `./gradlew :relay:Network:transport-raknet:test` is a
   no-op until that is added. Enabling it also enables `RakTests`, whose loopback tests block without
   a timeout, so it was left alone deliberately.

## Remaining suspects / follow-ups

- **Block/item mapping assets stop at protocol 800** (`assets/mcpedata/*/index.json`), so on protocol
  844 the app resolves runtime block ids against the 1.21.8x table. Correctness, not a hang — worth a
  separate pass, unchanged here.
- **The vendored tree is 57 files behind upstream**, including fixes to `RakSlidingWindow`,
  `RakDatagramCodec`, NACK resend weighting, a `TreeMap` for `sentDatagrams`, wrong-thread write
  protection in `RakSessionCodec.write()`, and server cookie/throttling work. Only the split- and
  queue-bounding commits were ported here because they are the ones that match this symptom; a full
  re-vendor should replace `relay/Network/` with a pinned upstream tag and delete the local copy.
  Upstream's `write()` wrong-thread check is worth taking next as a diagnostic:
  `OnlineLoginPacketListener.connectServer()` sends to the *game-facing* peer from inside the upstream
  link's callback, i.e. from the other peer's event loop. Netty re-dispatches that safely today, but the
  guard would prove it instead of leaving it to reading.
- **No MTU floor is enforced toward the game client.** The client proposes the MTU in
  `ID_OPEN_CONNECTION_REQUEST_2` and the relay only clamps it to min/max, so a client that asks for 576
  halves the bytes per message twice over. If the log shows a small `Client link` MTU together with
  `Refusing to send`, that interaction is the thing to tune, not the 2048 cap.
- **`RakTests`' `testClientResend`** style loopback coverage for the new `SPLIT_QUEUE_TOO_LONG` /
  `ORDERING_QUEUE_TOO_LONG` paths does not exist; those branches are reachable only by a hostile or
  buggy peer, and were reviewed rather than exercised.
