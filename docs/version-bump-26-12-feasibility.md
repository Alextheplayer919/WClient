# Feasibility: supporting Bedrock 26.12 (a.k.a. "1.26.12", protocol 944)

Date: 2026-10-11 · Research-only; **no code changed** for this. Read with
`docs/relay-1.21.111-diagnosis.md`.

## Verdict

**Feasible, and mostly mechanical: ~2–4 days of focused work.** The codecs you need already
exist upstream, your relay negotiates the codec from what the game asks for, and nothing in the
app is pinned to the game binary — so this is a *data + vendoring* job, not a reversing job.

The two things that are genuinely hard are not the bump itself:

1. **26.12 is six months old and ~40 protocols behind.** Current stable is 26.52 (protocol 2193);
   upstream already carries `v975`, `v1001`, `v2168`, `v2169`, `v2193`, `v2225`. Bumping to 944
   only buys a target that stops being what players run. Do it as "make the registry track
   upstream", not as "hardcode 944".
2. **RakNet — the transport this entire relay sits on — is being removed.** NetherNet (WebRTC:
   HTTP signaling on TCP + UDP media) became the BDS default in **26.50**; RakNet is deprecated in
   the **26.60.22** preview, and third-party docs state 26.60 drops it. **26.12 predates all of
   that**, so a 26.12 bump is safe on transport. 26.50+ is not, and no Java implementation exists
   in CloudburstMC (grep for `nethernet|webrtc|sframe` in Protocol@3.0: zero hits).

## What "1.26.12" is

Mojang dropped the leading `1.` in 2026: the release is **26.12**, a hotfix shipped 2026-03-31
(Win 04-01), BDS **1.26.12.2**, **protocol 944** — shared with 26.10/26.11/26.13, exactly like
1.21.110–114 shared 844. Internal/advertised version strings still carry the `1.`
(`1.26.12.2` on Android), and upstream's codec calls itself `"1.26.10"`. Naming in code, logs and
the LAN advertisement should therefore stay `1.26.x` while docs/UX may say `26.12`. Any
`startsWith("1.21")`-style check is a bug magnet; the repo's two version strings are plain
constants (below), which is the good case.

## Where you are today (measured, not guessed)

`relay/Protocol` is **CloudburstMC/Protocol `3.0` at ~`01a2b1be` (2025-12-09)**, i.e. the commit
that added `v898`, plus local edits. Measured against that commit, 235 files in `bedrock-codec`
differ, of which:

* **219 differ only by field visibility** — a mechanical `private` → `public` sweep on packet
  classes, so Kotlin can read `packet.field` instead of getters. Any re-vendor must re-apply it
  (or the ~219 call sites must switch to getters — do the sweep, in a script, not by hand).
* **16 are substantive local edits** that must be re-applied by hand:

  | file | why |
  |---|---|
  | `packet/PlayerListPacket.java`, `codec/v800/serializer/PlayerListSerializer_v800.java` | drops `java.awt.Color` (no AWT on Android) for a raw int |
  | `packet/AnimatePacket.java`, `packet/MobEffectPacket.java` | field/type tweaks |
  | `packet/ServerboundDataStorePacket.java`, `codec/v898/serializer/ServerboundDataStoreSerializer_v898.java` | their own DataStore handling |
  | `packet/BedrockPacketType.java`, `packet/BedrockPacketHandler.java` | enum + handler entries for packets they added |
  | `data/SoundEvent.java`, `data/MovementEffectType.java`, `data/EntityEventType.java`, `data/GraphicsOverrideParameterType.java`, `data/camera/CameraAimAssistPresetDefinition.java` | enum gaps filled for 1.21.11x |
  | `codec/v860/Bedrock_v860.java`, `codec/v898/Bedrock_v898.java`, `packet/ServerPlayerPostMovePositionPacket.java` | codec registration tweaks |
* `bedrock-connection` is **not** locally edited relative to `01a2b1be` — but re-vendoring still
  bites there: upstream narrowed `BedrockPacketWrapper.headerLength` from `int` to `byte`
  (`29: private byte headerLength` at 3.0 HEAD), and the relay does
  `buffer.skipBytes(wrapper.headerLength)` at `WRelaySession.kt:206` **and** `:266`. Kotlin will not
  widen a `Byte` to `Int`, so both call sites need `.toInt()` the moment the vendored copy moves
  past that narrowing. This is the type of break a blind `cp -r` produces.

**Dead data found on the way:** `relay/src/main/resources/protocol_mapping.txt` (53 lines, ends at
`898`) has **no consumer anywhere in the repo** — grep for the filename across all tracked files
hits only itself. Either wire it up or delete it; do not spend time appending `924`/`944` to it.

`relay/Network/transport-raknet` is likewise an old CloudburstMC/Network snapshot — see the
diagnosis doc, which ports from it for the same reason.

## Size of the actual bump (v898 → v944)

Upstream delta `98d0fcb`(2026-01-07) → `ccb85d2e`(2026-04-20), `bedrock-codec` + `bedrock-connection` + `common`:
**123 files, +4 389 / −503 lines**, and — the important number — **exactly one changed file in
`bedrock-connection`**, i.e. the session/transport API the relay is built on does not move:

* `bedrock-connection/.../EncryptionUtils.java`: `validatePayload` now requires
  `identityClaims.extraData.xuid`/`displayName` for `AuthType.FULL`. Relevant to you: the relay
  does online auth via `MinecraftRelays`, so a chain without extraData starts failing validation
  *after* the port. Test a Realms/XBL-auth join, not just an offline one.

Classified against your tree:

| bucket | count | handling |
|---|---|---|
| new files (v924/, v944/, new packet classes) | 73 | copy in |
| files where you already match upstream pre/post | 33 | take END version, re-apply visibility |
| **overlap with your local edits → real conflict risk** | **14** | merge by hand |
| deleted upstream | 1 | `data/BookEditType.java`, unused enum — check nothing imports it |
| in upstream's diff, absent from your tree | 2 | review |

The 14 include `BedrockPacketHandler`, `BedrockPacketType`, `StartGamePacket` and
`InventoryTransactionPacket` — precisely the classes the relay and the app poke at. Small enough to
do in an afternoon, big enough that a blind `cp -r` will not work.

## Checklist of every edit site found

1. `relay/Protocol/…` — port `v924` + `v944` (codec + helper + serializers + new packet classes +
   `BedrockPacketType`/`BedrockPacketHandler`/enum additions). Prefer bumping the whole vendored
   tree to a pinned upstream tag and re-applying the two mechanical patches (visibility sweep;
   AWT-free `PlayerList*`) as a script.
2. `relay/src/main/kotlin/com/retrivedmods/wrelay/codec/CodecRegistry.kt` — add
   `registerCodec(924, "1.26.1", …)`, `registerCodec(944, "1.26.10", …)`. Note the existing entries
   stop at 898 and there is a stray blank line pair where the next ones go; the reflective
   `Class.forName` + `CODEC` lookup means a missing class only logs
   `Failed to register codec …` and the entry silently disappears — make that fatal, or add a test
   asserting every registered protocol's class loaded.
3. `codec/VersionDetector.kt` — add `944 to listOf("1.26.10"…"1.26.13")`, `924 to listOf("1.26.0"…"1.26.2")`.
5. `relay/src/main/resources/nbt/block_palette.nbt` — **must be regenerated for 26.12.** It is one
   hardcoded palette: probing it finds `pale_oak_log` and `resin_block` (both 1.21.110 "Pale
   Garden" additions) among 1 528 distinct name tokens, so its vintage is at best the 1.21.11x era
   the diagnosis doc targets. Confirm by diffing it against BDS `1.26.12.2`'s palette rather than
   trusting a name probe. Loaded by `Definitions.loadBlockPalette()`
   from `app/…/service/Services.kt:123` and handed to the game-facing codec by
   `AutoCodecPacketListener`. Take it from BDS `1.26.12.2`'s `resource_pack/vanilla/block_textures`
   era data (or CloudburstMC/Data, which ships an uncompressed `block_palette.nbt`).
6. `app/src/main/assets/mcpedata/{blocks,items}/index.json` — add `924`/`944` tables
   (`runtime_block_states_944.dat`, `runtime_item_states_944.json`). Today they top out at **800**,
   and `MappingProvider.craftMapping()` picks the highest available `≤ protocol` *silently* — so
   on 944 you would resolve runtime ids against the 1.21.80 table and log nothing. That is already
   broken for 844/898 (see the diagnosis doc's last bullet); fix the fallback to log a warning while
   you are in there. Also `readMapping(version: Short)` caps usable protocol numbers at 32767 —
   fine at 2193, worth widening to `Int` before it isn't.
7. `app/…/service/RealmsManager.kt:20` `CLIENT_VERSION = "1.21.120"` → `"1.26.12"` (the Realms
   API rejects unknown client versions in practice; this gates the realms list, not the join).
8. `app/…/util/MinecraftUtils.kt:4` `RECOMMENDED_VERSION = "v1.21.132"` → the version players should
   be on. Shown in `HomePage.kt:459`.
9. `WRelay.DefaultAdvertisement` derives its version/protocol from `CodecRegistry.getLatestCodec()`
   ✅ — no edit needed, it follows automatically. Same for `VersionTrackingListener` +
   `AutoCodecPacketListener`, which pick the codec from the game's `RequestNetworkSettingsPacket`
   (`getClosestCodec` = highest registered `≤` requested). This is why the bump is mostly data.
10. `AutoCodecPacketListener.patchCodecIfNeeded()` overrides `InventoryContent`/`InventorySlot`
    serializers with the **v729** versions for every protocol > 729. Re-verify that on 924/944:
    upstream fixed `ItemStackResponseStatus` handling (commit `98d0fcb`, "fix an
    ArrayIndexOutOfBoundsException in ItemStackResponseStatus") in this very window, and the patch
    may no longer be needed — or may now be actively wrong.
11. RakNet side needs **nothing**: `ClientIdentification.getMinecraftProtocolVersion()` returns 11
    and the unconnected magic is unchanged — upstream's `RakConstants.RAKNET_PROTOCOL_VERSION` is
    still `11`, and 26.12 still speaks RakNet.

## Test surface for it

* Codec negotiation: assert `VersionInfoProvider.getVersionInfo(944).isExactMatch` (guards the
  silent `getClosestCodec` fallback, which is how a missing registration turns into a confusing
  "wrong version" instead of a clear error).
* Round-trip every packet the relay forwards, per direction, on 944 — the vendored
  `BedrockPacketCodec` tests exist upstream; the local tree has only `RakTests` +
  (now) `SplitPacketHelperTests` in `transport-raknet`, and **no test task runs them** (no
  `useJUnitPlatform()`, see the diagnosis doc).
* A real join against a 26.12 server with auth ON, because of the `EncryptionUtils` change.
* Re-run the 1.21.111 freeze repro: 944's StartGame palette is bigger still, which is exactly the
  payload class the split-reassembly fix in PR #14 addresses.

## If you want 26.12 only

Fastest honest path, ~2 days: port `v924`+`v944`, re-apply the 16 edits, do checklist items 2–6,
flip the two app constants, then test-join a 26.12 server. Everything else (mcpedata regeneration,
`Short`→`Int`, the v729 patch) can be a follow-up but each one is a silent-correctness trap rather
than a crash.

## Recommended framing

Don't do "support 1.26.12". Do **"make version support cheap and the current version correct"**:

1. Script the vendoring (upstream tag + visibility sweep + AWT patch + local-edit re-apply) so a
   version bump stops being a merge of 14 files by hand. One bump per upstream release, in CI.
2. Register the whole upstream range you can (upstream is at `v2225`), not one version. Fail loudly
   on a codec that didn't load.
3. Generate `mcpedata` + `block_palette.nbt` per protocol in CI from the same source, and warn on
   the fallback path.
4. **Spike NetherNet now**, while 26.12 still works on RakNet, because 26.5x/26.60 is the actual
   deadline and it needs HTTP signaling on TCP + ICE/DTLS/SCTP data channels — a transport, not a
   codec. Prior art to read: `df-mc/nethernet-spec` (the spec), `df-mc/go-nethernet` (Go),
   `bedrock-crustaceans/nethernet` (Rust, sans-io), `PrismarineJS/node-nethernet` (JS),
   `GameParrot/netherconnect` (Go, **NetherNet→RakNet proxy** — the shape closest to what WRelay
   needs, since a bridge keeps the game-facing side on RakNet), and `TheProjectLumina/Essence`
   (Java, "NetherNet MITM", but a single commit from 2026-01-08 — read it, don't build on it).
   Note the ecosystem gap: nothing in CloudburstMC (your Java dependency) has it yet.

## Sources

* minecraft.wiki: `Bedrock_Edition_26.12` (protocol 944, dates, BDS 1.26.12.2), `Bedrock_Edition_26.0`/`26.1`
  (protocol 924), `Protocol_version` (26.52 → 2193, preview 26.60.30 → 2225), `Bedrock_Edition_protocol`
  and `RakNet` (NetherNet default for BDS in 26.50, RakNet deprecated in 26.60.22 preview).
* itzg/docker-minecraft-bedrock-server README + Aternos NetherNet notes (transport keys, 26.60 removal
  claims) — note these disagree on whether 26.60 *removes* or only deprecates RakNet: a real-world
  report dated 2026-10-04 found `transport=raknet` still accepted on 26.60.29 preview. Treat 26.60 as
  the cliff, not a confirmed deletion date.
* CloudburstMC/Protocol@3.0 history (`01a2b1be` v898, `5e6cad4f` v924 start 2026-01-22,
  `da92f840` v944 start 2026-03-11, `8b9763d7` v975 2026-04-27) and
  CloudburstMC/Network@develop (`RAKNET_PROTOCOL_VERSION = 11`).
* All port-size numbers in this file were computed with `git diff` / `diff -w` between the vendored
  tree and those upstream commits, in a fresh clone — reproducible, not estimates.
