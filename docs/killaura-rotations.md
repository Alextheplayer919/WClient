# Killaura rotations

Killaura keeps its existing module/config ID (`killaura`), attack timing, friend/bot
filters, mob targeting, TP Aura, and movement-based orbit. Rotation calculations
run on input ticks independently of attack CPS.

## Modes

- **PREDICTIVE** (default for new/unconfigured profiles): tick-based target motion
  tracking, confidence-weighted lead, angular speed/acceleration limits, and
  hitbox-sized deadzones. Returns smoothly to the real camera orientation after
  losing targets, without moving the camera itself.
- **ABYSM**: proportional, speed-capped tracking with style-dependent turn caps.
  Starts from the last server rotation. The uploaded random noise is omitted.
- **AUTO**: experimental heuristic selector between the uploaded Lynx modes based
  on observed target movement/yaw. This is not a guarantee of improved accuracy.
- The eleven previously integrated modes remain available, including **SMOOTH**.
  Saved selections keep their existing enum names.

Target locking applies across modes: retain the current valid target until another
is more than 25% and 0.75 blocks closer, after at least six ticks. Removed,
out-of-range, filtered, or friendly entities cannot remain locked. All candidates
are still attacked as before; locking chooses the primary aim/orbit target only.

## Settings

- `rotations`: disables all Killaura rotation requests when off.
- `prediction`, `auto_prediction`, `prediction_ticks`: existing prediction controls;
  automatic mode uses latency and the hit-feedback offset, capped at ten ticks.
- `prediction_strength`, `rotation_smoothing`, `rotation_acceleration`: Predictive
  tuning. Strength zero disables predictive lead without disabling aiming.
- `rotation_max_speed`: Predictive yaw limit; Abysm uses it as its base turn cap
  before applying the target-style multiplier.
- `vertical_offset`: aim-height adjustment.

The original Lynx modes retain their own formulas; their prediction behavior is
not identical to Predictive, and these additional smoothing controls do not
rewrite those formulas.

## Silent transport

Every mode requests `LocalPlayer.silentRotation` in `(pitch, yaw, headYaw)` order.
`GameSession.applySilentRotation` consumes it, replaces rotation/interactRotation,
and re-encodes the outgoing input packet. The rotation engines never send
client-bound movement packets. The existing optional TP Aura/orbit functionality
is unchanged and can still move the player when explicitly enabled.

No camera-sync option or Kiwi compatibility package is required. Player network
positions are eye-level; target feet Y is player Y minus 1.62, while mobs already
use feet-level Y. Hitbox dimensions come from metadata with finite defaults.

Engines have instance-owned state, reset on enable/disable, disconnect, movement
correction, mode changes, and input-tick rollback. Lynx context resets on primary
target/selected-handler changes; Predictive retains candidate histories when
switching targets.

## Deliberately not imported

GeoRot's geometric patterns can put the aim point outside a player hitbox. The
Kiwi module's duplicate mode aliases, direct packet mutation, and camera-sync
movement injection were not imported.

## Verification

JUnit tests are in `app/src/test/java/com/retrivedmods/wclient/game/module/combat/rots`.
Run `bash gradlew :app:testDebugUnitTest :app:compileDebugKotlin` with JDK 17 and
an Android SDK configured. This editing environment has no Java installation,
so these tests and the Android compilation have not been executed here.
