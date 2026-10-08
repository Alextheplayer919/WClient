# WClient — Anarchy PvP & Config System Improvement Roadmap

Bedrock anarchy fights come down to three resources — **strength potions, totems, and
(enchanted) golden apples** — and most fights happen **in the air**. This roadmap focuses
on automating and hardening those three pillars, plus reworking the config system so
WClient owns its own directory and configs can be saved in-game at any moment.

---

## 1. The Three Pillars of Anarchy Combat

### 1.1 AutoPot / StrengthKeeper (new module — highest impact, currently missing)
There is no potion automation at all right now (`EffectsModule` only *applies* client-side
effects). An anarchy-grade AutoPot should:

- **Track live effect state** from `MobEffectPacket` (add/modify/remove) so the client
  always knows the remaining duration of Strength, Fire Res, Regen, Speed.
- **Re-pot before expiry**: configurable threshold (e.g. re-drink when < 5s of Strength II
  remain) instead of waiting for it to drop.
- **Drinkable potions only** (standard anarchy loadout — nobody carries splash):
  full hold-use emulation, i.e. the same start → hold ~31 ticks → consume packet
  sequence as eating, so AutoPot and AutoEat share one consume implementation.
- **Switch mode** (implemented, shared with AutoEat): `Visible` (default) moves the client's
  hotbar to the potion slot, drinks, and moves it back, like a key press. `Silent` sends only
  the server-bound `MobEquipmentPacket`. `HotbarSwitcherModule` is client-bound only.
  See `docs/AUTOEAT_AUTOPOT_DESIGN.md` §2 and §4.
- **Smart drink timing**: since a drink commits ~1.6 s, schedule it early (re-pot at
  ~8–10 s remaining) and prefer lulls in the exchange, with a hard deadline so the
  effect can never fully drop.
- Options: `effects to maintain` (multi-select), `re-drink threshold (s)`,
  `wait-for-lull + deadline`, `pause while eating`, `only in combat`
  (hook `PopCounter`/aura target state).

### 1.2 AutoEat / GapKeeper (new module — currently missing)
- **Keep Absorption up permanently**: watch the Absorption attribute
  (`Attribute` constants already exist in `game/utils/constants`) and re-eat an enchanted
  golden apple the moment absorption hearts hit 0, not just when HP is low.
- **Emergency mode**: force-eat gap/egap below a configurable HP threshold even
  mid-swing.
- **Item priority list**: egap → gap → chorus-safe food, configurable.
- **Eat packet sequence**: proper `ITEM_USE` transaction + held-item spoof so eating works
  while airborne and while Killaura is swinging (no visible hotbar flicker).
- Shared cooldown with AutoPot so the two never try to "use" simultaneously.

### 1.3 AutoTotem hardening (`combat/AutoTotemModule.kt`)
The current implementation polls on every `PlayerAuthInputPacket` with a millisecond
delay and scans slots 0–35. Improvements:

- **Pop-reaction instead of polling**: listen for `EntityEventPacket` with
  `TOTEM_ANIMATION` (and offhand → AIR transitions, which `PopCounterModule` already
  detects) and re-equip **immediately on the pop tick** — this is the difference between
  surviving a double-hit and dying.
- **Nearest-slot priority**: pick the totem slot with the cheapest transaction path, and
  optionally pre-stage one totem in a fixed hotbar slot as a hot spare.
- **ItemStackRequest fallback**: some servers reject legacy
  `InventoryTransactionType.NORMAL` moves (`AbstractInventory.moveItem`); add an
  `ItemStackRequestPacket`-based move mode toggle for compatibility.
- **Totem count awareness**: expose remaining totem count to the HUD and add a
  configurable low-totem chat/sound warning ("2 totems left — disengage").
- **Desync guard**: verify the offhand actually updated (server-confirmed) and retry once
  if the move was silently rejected.

### 1.4 Shared inventory action queue (infrastructure)
AutoTotem, AutoPot, AutoEat, and ChestStealer will all fight over
`InventoryTransactionPacket`s. Add a single prioritized queue
(**totem > gap > pot > restock**) with one in-flight transaction at a time, so modules
never send conflicting slot moves in the same tick — the #1 cause of "ghost items" and
flag-outs on anarchy servers.

---

## 2. Air-Combat Improvements

- **Velocity-lead targeting for auras** (`KillauraModule`, `WAuraModule`, `ACAModule`):
  track per-entity velocity from successive `MoveEntityAbsolute`/delta packets and aim at
  the *predicted* position. Airborne targets move fast; static-position targeting whiffs.
- **True 3D range + vertical priority**: make range checks fully spherical and add a
  target-priority option for enemies **above or below** you (crucial when fighting around
  fly/elytra height differences).
- **OrbitStrafe integration**: let `OrbitStrafe` lock onto the aura's current target and
  orbit in 3D (configurable vertical offset), instead of being a standalone module.
- **AntiKnockback air profile** (`AntiKnockbackModule`): separate horizontal/vertical KB
  multipliers — in air fights, vertical knockback is what throws you out of totem/gap
  range. A "reduce vertical only" preset is less detectable than full cancel.
- **Hit-select while falling**: option in TriggerBot/Killaura to only swing when the
  crosshair-predicted hit is inside reach *at packet-send time*, reducing wasted swings
  that reset combos mid-air.

---

## 3. Combat HUD / Awareness

- **Resource counter overlay**: totems / egaps / strength pots remaining, rendered next to
  the hotbar (extend the existing overlay system in `overlay/hud/`).
- **Effect timers HUD**: live countdowns for Strength, Fire Res, Absorption — the three
  numbers an anarchy player actually checks mid-fight.
- **TargetHUD upgrades** (`TargetHudModule`): show the target's pop count (data already
  exists in `PopCounterModule`), absorption hearts, and whether they currently hold a
  totem in offhand.
- **PopCounter extras**: per-player session pop totals, "announce pops in chat" toggle,
  and a pops-per-minute readout to judge whether you're winning the resource war.

---

## 4. Config System Rework

### Current state (for reference)
- One hardcoded file: internal `filesDir/configs/UserConfig.json` (`ModuleManager`).
- Saved **only** in `MainActivity.onDestroy()` / service stop — a crash loses everything.
- Export writes timestamped files to `Documents/WClient/configs`
  (`getWClientConfigsDirectory()`), but that folder is write-only; loading goes through a
  SAF file picker instead.
- No way to save or switch configs while in a game.

### 4.1 WClient owns its own directory
- Create **`/storage/emulated/0/WClient/`** (with `configs/`, and room for future
  `replays/`, `logs/`, `waypoints/`) **once at app startup**, and make it the *primary*
  config home — not just an export target.
- Scoped-storage strategy: on Android 10+ either request `MANAGE_EXTERNAL_STORAGE`, or
  keep the public `Documents/WClient` root via the existing helper with
  `getExternalFilesDir()` as a silent fallback — but read **and** write from the same
  place so users can edit configs with any file manager and see changes in-game.
- Keep internal `filesDir` only as a mirror/backup of the last-good config.

### 4.2 Named profiles instead of one UserConfig.json
- `ModuleManager.saveConfig(name)` / `loadConfig(name)` / `listConfigs()` /
  `deleteConfig(name)` / `renameConfig()` over the WClient directory.
- Track the **active profile** and remember it across restarts.
- Typical anarchy use case: `crystal.json`, `sword-air.json`, `travel.json` — switched
  mid-session.

### 4.3 Save in-game, immediately, at any time
- **Configs tab in the in-game ClickGUI** (`OverlayClickGUI`): list profiles with
  Save / Save As / Load / Delete buttons — no need to tab out to the app.
- **Chat commands** via the existing command system (currently only `FriendCommand` and
  `HelpCommand` exist):
  - `.config save [name]`, `.config load <name>`, `.config list`, `.config delete <name>`
- **Auto-save**: debounced write (~2s after the last change) on every module toggle and
  value edit, so even a crash or force-close loses nothing. The `Value` change path in
  `ModuleValues.kt` is the single hook point.

### 4.4 Robustness & quality of life
- **Schema version field + migrations** so old configs survive module/value renames
  (`ignoreUnknownKeys` already helps, but renames currently silently reset values).
- **Atomic writes**: write to `name.json.tmp` then rename, keeping a `.bak` of the
  previous version — prevents corrupt configs on crash-during-save.
- **Include everything in the profile**: module values + enabled state + shortcut
  positions (already serialized) **plus HUD element positions** (watermark, keystrokes,
  target HUD), which are currently not part of the config.
- **Per-server auto-profiles** (stretch): map server IP → profile and auto-load on
  connect via `RelayService`.
- **Config sharing**: export/import via clipboard string (base64) in addition to files —
  the easiest way to share setups in Discord.

---

## 5. Suggested Priority Order

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 1 | In-game config save (GUI tab + `.config` commands) + auto-save | Medium | Very high |
| 2 | WClient public directory as primary config home + named profiles | Medium | Very high |
| 3 | AutoTotem pop-reaction rewrite | Low | Very high |
| 4 | AutoEat (absorption keeper + emergency gap) | Medium | Very high |
| 5 | AutoPot (strength/fire-res maintenance) | Medium–High | High |
| 6 | Shared inventory action queue | Medium | High (enables 3–5 safely) |
| 7 | Velocity-lead aura targeting + 3D priorities | Medium | High |
| 8 | Resource/effect HUD counters | Low | Medium |
| 9 | AntiKB vertical-only air profile | Low | Medium |
| 10 | Config versioning, atomic writes, per-server profiles | Low–Medium | Medium |
