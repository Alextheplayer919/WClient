# FeetSurround + World/Voxel Tracking — Proxy-Level Design

> **Status: IMPLEMENTED** — `World`, `Block`, `BlockCategory`, `ChunkDataParser`,
> `VoxelRaycaster` (`game/world/`), `FeetSurroundModule` (`game/module/combat/`),
> registered in `ModuleManager` (Combat tab, name `feet_surround`).
> `RotationUtils.aimSilently` gained an optional `eyeHeight` for aiming at block faces.
> Unit tests: `app/src/test/.../game/world/` (parser layout, DDA walk, classification).

Two things went in together, because the surround module was written against world
knowledge WClient never had: a **minimal voxel store** fed from the packet stream, and the
**feet-surround module** on top of it. The same store now powers `CrystalSmashModule`'s
`require_los` sight check (crystals behind terrain are skipped) — see the appendix.

---

## 1. Why the client needed a world model

`Level` (despite the name) is an entity registry — the client tracked **zero** block
states. Every voxel decision a "smart" module needs (is that slot air? is there support
underneath? can I see it?) was simply unanswerable. The original FeetSurround sketch
papered over this with `isReplaceable`/`hasSupportBelow`/`canSee` stubs; this design
replaces the stubs with a real, bounded voxel store instead of stripping the features. The
sketch's helper classes map onto infrastructure that already exists:

| Sketch class | This client |
|---|---|
| `PositionTracker` (fed from `PlayerAuthInputPacket`) | `session.localPlayer` — updated from exactly that packet in `LocalPlayer.onPacketBound` |
| `SilentRotation.aimAt` / `applyTo` | `RotationUtils.aimSilently` → `LocalPlayer.silentRotation` → swapped into the outgoing auth input by `GameSession.applySilentRotation` |
| `session.level.getBlockAt` | `session.world.getBlockAt` (`Level` stays the entity registry) |

Design rules:

- **Never wrong, sometimes unknown.** Anything that can't be classified (missing assets,
  unloaded subchunk, unexpected payload) resolves to `UNKNOWN`: not placeable into, not
  usable as support, opaque for sight. The module then just doesn't place — it can never
  place *wrong*.
- **No new protocol surface.** The vendored codec already decodes everything we need;
  `World` consumes decoded packets and parses only the two opaque byte blobs the codec
  intentionally leaves alone.
- **Bounded memory.** ~16 KB per 16x8x16 subchunk, hard-capped (4096 subchunks = 64 MB),
  evicted per dimension. Plain `HashMap`s — every read/write happens on the session's
  relay thread, so no locks.

---

## 2. Data sources (all already decoded by `relay/Protocol`)

| Packet | Content used | Notes |
|---|---|---|
| `SubChunkPacket` | `centerPosition` + per-subchunk `position`/`data`/`result` | primary source; 4096 x i32le runtime block states per 16x8x16 subchunk. `SUCCESS_ALL_AIR` stores an all-zero array. |
| `LevelChunkPacket` | opaque `data` blob when `requestSubChunks == false` | embedded column payload parsed by `ChunkDataParser` (below). Modern servers (`requestSubChunks == true`) deliver the column via `SubChunkPacket` instead, so this path is a fallback for servers that still embed it. |
| `UpdateBlockPacket` | `blockPosition` + `definition.runtimeId` | single-block updates (doors, player break/place). Applied only to **loaded** subchunks — an update that arrives before its chunk is simply overwritten by the later chunk send. |
| `StartGamePacket` / `ChangeDimensionPacket` | `dimensionId` / `dimension` | store is cleared, current dimension tracked (UpdateBlock carries no dimension). |

**Embedded LevelChunk payload layout (v486+, parsed by `ChunkDataParser`):**

```
u32le format            0 = none, 1 = gzip (rest of the blob)
per subchunk (40 in a 16x320x16 column):
    u8 rawBlockStates   must be 1 (legacy palette subchunks -> reject whole payload)
    4096 x i32le        runtime block states (x fastest, then z, then y)
    u8 heightMapType    +1024 bytes when 1 (HAS_DATA)
    u8 renderHeightMapType  +1024 bytes when 1
```

Both parsers are strict: any structural mismatch returns `null` and the payload is
dropped rather than stored. They never mutate the codec's buffers.

**Version policy:**

- `< 486` (pre-1.20.0): the embedded/palette subchunk layouts are not parsed — voxel
  data simply doesn't exist and the module stays dormant. (These versions also predate
  the subchunk addressing used today.)
- `486 .. 800`: fully functional (bundled MCPEData assets exist for every version).
- `801 .. 898`: raw states are stored fine, but `assets/mcpedata` ships no state files
  above 800, so block *names* — and therefore classification — are unavailable
  (`GameSession.mappingsLoaded == false`). Everything is `UNKNOWN`; the module does
  nothing. Adding the missing state dumps is a data task, not a code one.

**Subchunk addressing (v486+):** `SubChunkData.position` is an offset relative to the
packet's `centerPosition`. `v486-v817` send the full-height column (center.y = 0,
offset.y = 0..39); `v818+` send 16-block layers (center.y = 0..19, offset.y = 0..1).
One formula covers both: `worldSubY = center.y * 16 + offset.y * 8`.

---

## 3. Classification without physics flags

The protocol carries no per-block solid/opaque flags (`BlockDefinition` is name +
runtime id only), so `BlockCategory` is derived from the **resolved block name**:

| Category | Members | replaceable | support | sight |
|---|---|---|---|---|
| `AIR` | air, cave_air, void_air(, _transparent) | yes | no | passable |
| `LIQUID` | water, flowing_water, lava, flowing_lava | yes | no | passable |
| `PASSABLE` | powder_snow, bubble_column | no | no | passable |
| `NON_SOLID` | plants, crops, carpets, torches, signs, `potted_*`, `wall_*`, snow layer, fire, sculk, ... | no | no | **opaque** |
| `SOLID` | anything else with a `minecraft:` prefix | no | **yes** | opaque |
| `UNKNOWN` | unresolvable name (missing mapping) | no | no | opaque |

Defaults are conservative: unknown = solid-looking for sight, useless for placement.
Notable deliberate choices: cobweb is `SOLID` (it impedes movement in Bedrock),
carpets/signs/plants are `NON_SOLID` (no collision, so they cannot support a block, but
they still block sight). Categories are cached per runtime state id, so the hot path
touches no strings after the first lookup.

**Sight:** `World.canSee` runs a DDA voxel walk (`VoxelRaycaster`, Amanatides & Woo)
from eye to target center; first opaque voxel wins, unloaded subchunks count as
passable (placement is separately gated by the conservative replaceable/support checks).

---

## 4. The module (`feet_surround`, Combat)

Every `PlayerAuthInputPacket`, as often as `place_interval_ms` allows (default 60 ms):

1. **Candidate set** — the four horizontal neighbours of the block the player's feet
   occupy: keep slots that are `replaceable` (air/liquid), `solid` underneath (the block
   will be placed **on top** of that support), and — with `require_los` — visible from
   the eye by DDA.
2. **Ranking** — prefer the slot most aligned with the crosshair (Bedrock yaw
   convention: `look = (-sin yaw, 0, cos yaw)`, matching `RotationUtils.toRotation`),
   so the placement the server sees is the most natural one.
3. **Silent aim** — `RotationUtils.aimSilently(player, clickPoint, eyeHeight = 1.62)`
   (new `eyeHeight` parameter; combat keeps aiming from the feet). The rotation is the
   existing silent mechanism: it lands in `LocalPlayer.silentRotation` and
   `GameSession.applySilentRotation` swaps it into the outgoing auth input. Camera
   untouched. (On this protocol the server does not re-derive placements from the
   reported rotation — the aim is for plausibility, not correctness.)
4. **Placement transaction** — the vendored codec speaks the legacy `ITEM_USE`
   transaction for block placement (there is no `BLOCK_PLACE` type and no
   `BlockPlaceAction` in this fork; `ItemUseTransaction` fields exist since v712):

   ```
   InventoryTransactionPacket
     transactionType = ITEM_USE
     actionType      = 0            (use on block; 1 = click air, cf. BaseConsumeModule)
     blockPosition   = support block (target − 1y, the solid block below the target slot)
     blockFace       = 1            (+Y: the support's top face — the placement cell is
                                    resolved as clicked block + face direction, so the new
                                    block lands in the target slot itself)
     hotbarSlot      = obsidian slot
     itemInHand      = obsidian stack
     playerPosition  = player feet
     clickPosition   = (tx+0.5, ty, tz+0.5)   centre of the support's top face
     triggerType          = PLAYER_INPUT      (v712+ fields, ignored by older codecs)
     clientInteractPrediction = SUCCESS
     blockDefinition  = minecraft:air (same convention BaseConsumeModule uses)
   ```

5. **Silent slot swap** — `MobEquipmentPacket` (server-bound only) equips the obsidian
   slot around the transaction, then restores the previous slot — the same pattern as
   `BaseConsumeModule.sendEquip`; the real client's hotbar never flickers.
6. **Out of items / unknown world** — no candidate or no obsidian in the hotbar →
   retry in 50 ms; the module never spams.

Config: `place_interval_ms` (20..200), `silent_rot` (bool), `require_los` (bool),
`block` (OBSIDIAN / CRYING_OBSIDIAN, resolved by item name in the hotbar via
`ItemMapping`).

---

## 5. Failure modes, by construction

| Situation | Behaviour |
|---|---|
| Chunks not loaded yet | all slots `UNKNOWN` → no candidates → module idles (50 ms retry) |
| Protocol 801+ (no state assets) | `mappingsLoaded == false` → same as above; also `findBlockSlot` bails |
| Malformed/unknown chunk payload | parser returns `null` → payload dropped, previous data kept |
| Server places/breaks a block nearby | `UpdateBlockPacket` patches the loaded subchunk |
| Player changes dimension / rejoins | store cleared, refilled from the fresh chunk stream |
| Runaway server streaming chunks forever | 4096-subchunk cap: other dimensions evicted first, then current reset (server re-sends on demand) |
| Two modules both using the hand (e.g. Killaura + surround) | known, accepted tradeoff — `BaseConsumeModule` has the same property; placement restore is in-order on the relay thread so the server never sees a torn swap |

---

## 6. What was deliberately NOT done

- **Server-authoritative `ItemStackRequest` placement** (`BlockPlaceAction`): this fork
  has no such action type; the `ITEM_USE` transaction is the supported path (it's what
  the v712+ serializers in `relay/Protocol` encode).
- **Pre-1.20 chunk layouts** (palettes, 8/16-bit states): not parsed — those servers
  predate everything WClient's combat suite targets.
- **Full physics flags** (per-state solid/opaque tables): would need the block-state
  JSON dumps, same data gap as the 801+ assets. Name-based classification is a
  deliberate, testable approximation.
- **Swing/arm animation on place**: the module is silent by design; no
  `AnimatePacket` is emitted.

---

## Appendix — `CrystalSmashModule` (upgraded)

Pre-existing module (crystal smashing was the client's old standby); upgraded in place with the
same "extend the client, don't strip features" approach. No `PositionTracker`/`SilentRotation`
duplicates: crystals live in `Level.entityMap` (Add/Remove/Move already ingested) and are
matched by `identifier == "minecraft:end_crystal"` (the server-sent identifier, not an entity
type constant — this fork's `AddEntityPacket` has no owner field, so "skip my own crystals" is
impossible, and end crystals have no fuse timer, so "smash the about-to-explode one first" is
meaningless).

| Feature | Mechanism |
|---|---|
| Instant first hit | `firstHitPending` (weak-keyed set) — a crystal's first attack bypasses the `delay` tick gate entirely |
| Motion prediction | `TargetPredictor` + `session.latency.lookaheadTicks + hitTracker.predictionOffsetTicks` (Killaura's pipeline); aim at box centre (`+0.45y`, entity origin is the box bottom) |
| Silent rotation | `RotationUtils.aimSilently` at the primary, every gated tick (window-independent, Killaura pattern) |
| Burst rhythm | `burst` consecutive cps-gated windows, then `burst_pause_ms` lull (steady at `burst=1`) |
| Silent weapon swap | best hotbar sword (netherite → wood) equipped server-side via `MobEquipmentPacket` for the strike window; raw `ITEM_USE_ON_ENTITY` transaction with the explicit slot (mirrors `LocalPlayer.attack`); no-op swap when a weapon is already held or mappings are missing |
| Hit confirmation | struck crystal must produce server knockback (move packet) within 150 ms or it is set aside for up to 1 s — voided hits stop being spammed; any move clears the doubt |
| Multi-target cone | `only_closest` off → all candidates within a 60° cone of the aim direction (off-angle hits would send knockback the wrong way) |

**Hotbar coordination:** `utils/combat/HotbarLock` (same shape as `ConsumeLock`: named owners,
priority — weapon 70 > block 50 — stale eviction at 3 s). Crystal smash and feet surround both
take it around their server-side swap windows; the loser skips that tick and retries (no
interleaved `MobEquipment` packets, no transactions referencing a slot the server no longer holds).

State (all relay-thread): `firstHitPending`/`lastStrike`/`doubtful` are weak-keyed maps keyed on
the `Entity` instances `Level` owns — entries die with the crystal, no explicit cleanup needed.
