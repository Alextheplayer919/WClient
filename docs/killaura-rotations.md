# D4C Aura rotations

The client has one aura module, **D4C Aura** (`d4c_aura`). The Kiwi and Lynx rotation handlers
are integrated into this module rather than registered as separate modules. Existing combat options
remain in place: target range and filters, attack timing, packet count, optional TP Aura, and
movement-based orbit. The module still resolves friends and bots through the client's own data.

Profiles using the former `killaura` key (or `kiwi_killaura`) are read as D4C Aura settings. New
profiles are written with the `d4c_aura` key. The former Kiwi title-cased settings, including
`Camera Sync`, are mapped to this module's options. The `.killaura` command remains an alias for
toggling it; `.d4c_aura` is the new name.

## Rotation modes

- **Predictive** (default): tick-based movement tracking, confidence-weighted lead, angular
  velocity/acceleration limits, and hitbox-sized deadzones. With Camera Sync off, it releases
  smoothly to the real input rotation when the target is lost; with Camera Sync on, it holds the
  last synchronized camera angle.
- **Abysm**: keeps the uploaded style-dependent lead (including its vertical lead rules), speed cap,
  and optional humanization formula; its `humanize` value defaults to zero.
- **Auto / HvHGod**: classifies observed target movement/yaw and selects one of the existing Lynx
  handlers. This heuristic is not a guarantee of improved accuracy.
- **Lynx handlers**: Lynx Smooth, Nemesis, Vortex, FrontStrafe, AirHvH Pro, FrontsX, Astral,
  Atomic, Syntax, Cortex, and Alpha. Their existing rotation routines are kept intact.
- **Kiwi handlers**: None, Snap, Normal, Silent, Body, Ping Predict, Lead Shot, Smooth, Legit,
  Jitter, Strafe, and Anticipate. These use the uploaded formulas; `randomize`, `smooth_speed`, and
  `strafe_amp` configure the corresponding Kiwi options. With rotations enabled, `None` intentionally
  performs no attacks, matching the uploaded module. Turning the separate `rotations` toggle off
  skips mode dispatch and still allows the normal attack loop without changing input rotation.
- **Geometric patterns**: Circle, Square, Zigzag, and Spiral move the aim point around the target's
  chest using the uploaded `GeoRot` formulas.

## Direct input-packet transport

D4C Aura writes the calculated rotation directly into the current `PlayerAuthInputPacket` (and its
`interactRotation` when present). The relay normally forwards raw bytes, so `GameSession` snapshots
the original rotation, detects an edited rotation, and re-encodes and forwards that packet. D4C Aura
does not read or set `LocalPlayer.silentRotation`; that legacy request remains supported for other
modules.

Predictive mode's `camera_sync` option (default on, matching the uploaded setting) additionally sends
a guarded `MovePlayerPacket` to keep the visible client camera aligned. The other rotation modes only
edit the server-bound input packet and do not send client-bound movement packets. With Camera Sync
off, no rotation mode changes the visible camera.

The client adapts the uploaded calculations to Bedrock's network-height conventions: local-player Y
is eye-level, while player target Y is converted to feet level for hitbox calculations. Existing
predictive, Abysm, Lynx, and target-classifier state resets on enable/disable, disconnect, movement
corrections, mode changes, and input tick rollback. Kiwi Smooth/Legit state remains module-owned
between targets and mode changes, matching the uploaded module. The target lock continues to choose
the primary aim and orbit target without changing the attack target list.

## Verification

`GeoRotTest` checks the starting offsets for the four geometric patterns and the unknown-mode
fallback. Run `bash gradlew :app:testDebugUnitTest :app:compileDebugKotlin` with JDK 17 and an Android
SDK configured. This edit environment has no Java installation, so the build and tests could not be
run here.
