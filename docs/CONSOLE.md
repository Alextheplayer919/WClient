# WClient console — what it captures and how to read it

The console exists to make a failed join or a freeze explainable from the app itself, without adb, a
second device or a packet capture. It starts **before** the relay can be started: the app opens on the
boot console, and everything after that point is recorded.

## Where it shows up

| Surface | When |
|---|---|
| **Boot console** | Automatically on app launch (before the loading screen). Continue manually or let the countdown finish. Can be turned off in the console's settings panel or the Settings page. |
| **Console page** | Sidebar → terminal icon. Live while the relay runs, with filters, search, packet table and report export. |
| **Floating console** | The terminal button on the overlay (drawn over the game) or the picture-in-picture action on the Console page. Last 40 lines plus the packet counters, so a freeze can be watched while it happens. |

Reports are written to `WClient/console/` on shared storage (falling back to the app-specific folder
when that is not writable) as `wclient-console_<date>_<time>.txt`.

## What is recorded

Everything except the contents of encrypted payloads:

| Kind | Filter chip | Examples |
|---|---|---|
| `code` | code | `android.util.Log` output and `println` from the app and the relay, module messages, exceptions |
| `relay` | relay | connect attempts with timings, DNS lookups, codec/version negotiation, login/chain/refresh steps, queued packets, link snapshots |
| `net` | network | RakNet handshake and MTU ladder, split/reassembly, gaps → NACK, resends, acks, compression and AES switches, session timeouts, disconnect reasons, Android link state (interface, MTU, VPN, DFS) |
| `packet` | packets | every forwarded Bedrock packet: direction, name, wire size, packet id; details for the join-critical ones (`StartGame`, `PlayStatus`, `NetworkSettings`, `Disconnect`, `Transfer`, resource packs, `LevelChunk`, `ServerToClientHandshake`) |

Two pieces of the console are worth knowing about:

- **Packet table** (top bar → list icon): per packet type, per direction, with count, bytes, average
  size and how long ago the last one arrived. A stream that stopped is visible here even when
  movement packets keep the line busy.
- **Stall watchdog**: while the relay runs, a second-by-second check reports the *shape* of a stall:

  - `DOWNSTREAM STARVED: the game client is still sending … but the server has sent nothing for 8s` —
    the classic freeze where other players stand still and the world stops updating, while the phone
    clearly still has internet.
  - `LINK STALLED: no packets in either direction for …` — the whole link is dead (or the relay's
    upstream socket was closed).
  - `relay has been running for 20s and not a single packet crossed it` — the game never reached the
    relay at all (wrong port, a second client on 19132, the game not restarted after pressing Start).

## Reading a first-join timeout

1. Filter `network` and look for the handshake lines:
   - `offline handshake started with <addr> (connectTimeout=…, retryEvery=…, mtuLadder=…)`
   - `Request1 #n probing MTU …` (only when datagram tracing is on)
   - `Reply1 after N attempt(s): server accepted MTU …`
   - `Reply2: session MTU … after N Request1 attempt(s)`
   No `Reply1` at all means the datagrams never made it: check the `dns` line, the `active network`
   line (VPN? unvalidated network?) and `local UDP port 19132`.
2. `connect: gave up on … after Xms and N attempt(s): ConnectTimeoutException` is the join timeout the
   game reports as *creeper*.
3. `auth` lines say whether the Xbox token had to be refreshed: `cached Xbox session expired` followed
   by a refresh blocks the join until it completes.

## Reading a post-join freeze

1. Open the packet table. Compare `last seen` between an uplink type (`PlayerAuthInputPacket`) and a
   downlink type (`LevelChunkPacket`, `SetTimePacket`, `MovePlayerPacket`).
2. Look for `split` lines: `REFUSING N byte message: needs M parts, peer allows 2048 at MTU X` is a
   dropped batch (missing chunk/entity data, not a freeze by itself), and
   `reassembly holding N bytes, over the … limit` means the peer withheld parts.
3. `encryption: failed to decrypt inbound batch #n` drops everything on that link — every later packet
   is lost, which looks exactly like a freeze.
4. `ordering channel is stuck` / `reliable index … ahead of the read index` disconnects appear when a
   transport ordering channel stopped moving.
5. The status line at the bottom of the console always shows both MTUs and the RTT:
   `upstream=connected(mtu=1400, rtt=42ms) client=connected(mtu=1400) …`.

## Versions

The console records the version the *game client* announces (`version: the game client identified
itself as Minecraft <version> (protocol <n>)`) and the codec the relay selected for it, including the
warning when a protocol has no registered codec and is negotiated as an older one. That line is the
first thing to look at for any version-specific problem (for example anything above 1.21.130).

## Cost

- Packet lines are rate limited per type and direction (30/s each), collapsed to `xN` when identical,
  and the buffer keeps 20 000 lines by default. All counters stay exact regardless.
- Datagram tracing is off by default; it produces dozens of lines per second and is meant for short
  captures.
- The trace settings panel (magnifier icon in the console) controls packet/network/datagram/code
  tracing, the minimum level and the buffer size; they apply immediately and are persisted.
