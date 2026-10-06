# HUD Layout Editing (drag) + Resource Counter + Corner-Aware ArrayList — Feasibility & Design

> **Status: IMPLEMENTED (Design A).** Sections 1–6 below are the original research notes and
> are kept as the rationale; the "As built" section is the authoritative description of what
> is in the tree now.

## As built

| Piece | Where | What it does |
|-------|-------|--------------|
| HUD edit mode flags + touch-flag toggling | `overlay/OverlayManager.kt` (`isHudEditMode`, `applyHudEditMode`, `dismissClickGui`, `hudSnap*`, `hudGridSizePx`, `hudShowOutlines`, `hudShowLabels`) | Clears `FLAG_NOT_TOUCHABLE` on every window with `isHudElement = true` while editing, restores it on exit, and closes the ClickGUI (which otherwise swallows every touch). Reset in `dismiss()`. |
| Per-window hooks | `overlay/OverlayWindow.kt` (`open val isHudElement`, `var onHudMoved`) | Opt-in marker + write-back callback. |
| Shared drag + outline | `overlay/hud/HudDrag.kt` (`HudDrag.drag/snap`, `HudEditBox`) | Gravity-aware drag (`END`/`BOTTOM` mirror the delta, `CENTER_*` use signed offsets), on-screen clamping, edge snap, optional grid, red outline + name chip. Pass-through `Box` when not editing. |
| **HUD Editor module** | `game/module/misc/HudEditorModule.kt` (`hud_editor`, Misc) | Toggle = edit mode. Values: Snap To Edges, Snap Distance, Grid Size, Show Outlines, Show Labels, Save Layout On Exit. Shows the Target HUD sample card while editing (only if Target HUD is on). `toJson`/`fromJson` force `state=false` so edit mode never survives a restart. Tip: tick its **Shortcut** box for a floating exit button. |
| **ArrayList** | `overlay/hud/ArrayListOverlay.kt`, `game/module/misc/ArrayListModule.kt` | Fixed the broken `flags` assignment (dead expression) and the `+`/`if` precedence bug in the enter/exit transitions; entries are `key()`ed and actually animate in now. New values **Position** (4 corners), **Offset X/Y**, **Order Direction** (AUTO/NORMAL/REVERSED), `Border Style = AUTO` (accent bar on the inner edge). Dragging re-picks the nearest corner; AUTO reverses the length order for bottom corners (**longest at the bottom, shortest at the top**). `CATEGORY_BASED` colour now uses the row's own module. |
| **Resource HUD module** | `game/module/misc/ResourceHudModule.kt` (`resource_hud`, Misc), `overlay/hud/ResourceHudOverlay.kt` | Totems + Strength potions (+ Strength timer) by default; optional Regen / Fire Res / Swiftness potions, Gapples, E-Gapples, Pearls, splash inclusion, hide-empty, low-count warning, compact mode, font, background. 8-anchor `Position` + signed `Offset X/Y`, draggable. Potion metas: Strength 31/32/33, Regen 28/29/30, Fire Res 12/13, Swiftness 14/15/16. |
| Other HUD elements made draggable | `WaterMarkOverlay`/`WaterMarkModule`, `KeyStrokesOverlay`/`KeyStrokesModule`, `PieChartOverlay`/`PieChartModule`, `CoordinatesOverlay`/`CoordinatesModule`, `TargetHudOverlay`/`TargetHudModule` | Nested `pointerInput` drags removed (they double-moved the window and swallowed game touches), windows are now `FLAG_NOT_TOUCHABLE` during play, positions are module values (`Offset X/Y` or widened `Position X/Y` ranges) written back after a drag. `setPosition(...)` early-returns on unchanged values so the modules' refresh loops never fight a drag. |
| Registration | `game/ModuleManager.kt` Misc block | `ResourceHudModule()` and `HudEditorModule()` added after `WaterMarkModule()`. |

Not done (by design): Crosshair and Minimap stay slider-positioned. Exit animations of ArrayList
rows are still instant (would need removed rows to linger until the transition ends).

---

> Original research notes follow.
> Answers three questions:
> 1. Can the ArrayList be repositioned by click-and-drag from the ClickGUI?
> 2. Can we add a custom HUD module that shows totem count + strength potion count, with an editable position?
> 3. Can the ArrayList order by *name length* (longest at the bottom, shortest at the top) when parked in a corner, instead of alphabetically?
>
> Short answers: **yes / yes / mostly-already-there**. Details, exact code paths, and the one
> architectural constraint that changes the shape of #1 are below.

---

## 0. Verdict summary

| # | Ask | Verdict | Effort | Main blocker |
|---|-----|---------|--------|--------------|
| 1 | Drag-to-move ArrayList | **Yes**, but not *while the ClickGUI is open* — it needs an "HUD Edit Mode" launched *from* the ClickGUI | 1–2 h for ArrayList alone; ~½ day for a proper global edit mode | `ArrayListOverlay` window is `FLAG_NOT_TOUCHABLE`, and the ClickGUI is a full-screen window stacked **above** every HUD window |
| 2 | Totem + Strength-potion counter HUD, draggable | **Yes** — all data is already in memory, and it is literally item #8 on `IMPROVEMENTS.md` §3 | 2–4 h (mostly Compose UI) | None. No item textures ship in assets, so it is text-based (or uses `res/drawable` icons) |
| 3 | Length-ordered ArrayList, longest at bottom in a corner | **Half-exists**: `SortMode.LENGTH` is already the *default*, but it sorts **descending** (longest on top) and the window is hard-anchored `TOP\|END` | 1–2 h | Needs an ascending variant + gravity/alignment/border flipping + auto-anchor |

---

## 1. How the overlay system works today (ground truth)

WClient renders HUD elements as **Android system overlay windows** (`TYPE_APPLICATION_OVERLAY`,
`OverlayWindow.kt:36`) on top of the real Minecraft app — it does **not** draw into the game.
Each HUD element is its own `WindowManager` view with its own `LayoutParams` (`x`, `y`, `gravity`,
`width/height = WRAP_CONTENT`).

Consequences that matter for all three asks:

* Moving an element = mutate `layoutParams.x/y` and call `windowManager.updateViewLayout(composeView, layoutParams)`.
* Whether an element can be dragged depends **entirely on its window flags**
  (`FLAG_NOT_TOUCHABLE` ⇒ touches pass straight through to the game ⇒ no drag, ever).
* Z-order for same-type overlay windows is **add order**. `OverlayManager.showOverlayWindow()`
  appends and adds the view (`OverlayManager.kt:74-80`, `:130-158`).

### 1.1 Existing drag precedents (the pattern to copy)

| Window | Draggable? | Flags | Persisted? |
|---|---|---|---|
| `OverlayButton` (the W button) | ✅ `detectDragGestures` (`OverlayButton.kt:60-66`) | not-touchable **not** set | ❌ |
| `OverlayShortcutButton` | ✅ (`:81-88`) | same | ✅ **yes** — writes back `module.shortcutX/Y`, serialized in `Module.toJson()/fromJson()` (`Module.kt:37-38`, `:73-87`, `:88-107`) |
| `WaterMarkOverlay` | ✅ (`:131-140`) | `NOT_FOCUSABLE \| NOT_TOUCH_MODAL \| WATCH_OUTSIDE_TOUCH` (touchable!) | ❌ |
| `KeyStrokesOverlay` | ✅ (`:186-191`) | touchable | ⚠️ module has `Position X/Y` values but they are never updated by the drag, and are re-pushed on enable (`KeyStrokesModule.kt:29-30`, `:68`) |
| `PieChartOverlay` | ✅ (`:236-241`) | touchable | ⚠️ same shape as KeyStrokes |
| `CoordinatesOverlay` | ✅ (`:215-222`) | touchable | ❌ |
| `TargetHudOverlay` | ❌ (`FLAG_NOT_TOUCHABLE`, `:41-44`) | sliders only (`TargetHudModule.kt:25-26`) | ✅ value-wise |
| `CrosshairOverlay`, `MinimapOverlay` | ❌ `FLAG_NOT_TOUCHABLE` | — | — |
| **`ArrayListOverlay`** | ❌ **`FLAG_NOT_TOUCHABLE`** (`:64`) | — | ❌ no position value at all |

So: **five windows already implement exactly the drag gesture you are asking for.** The ArrayList is
simply not one of them yet.

### 1.2 🐛 Real bug found in `ArrayListOverlay`

`ArrayListOverlay.kt:60-68`:

```kotlin
flags =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or     // <-- dead expression, value discarded
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
```

The second statement is a bare expression whose result is thrown away, so:

* `FLAG_NOT_TOUCH_MODAL` and `FLAG_LAYOUT_IN_SCREEN` are **never applied** (unlike every other HUD
  window, which does `flags = flags or …`), and
* the window stays `FLAG_NOT_TOUCHABLE` ⇒ it can never receive a drag event.

Fixing this is step zero of ask #1 regardless of which UX we pick. It also explains why the
ArrayList can be inset oddly on notched devices (`layoutInDisplayCutoutMode` is never set here,
while `OverlayShortcutButton.kt:31` sets it).

### 1.3 Positions are not persisted anywhere

`IMPROVEMENTS.md` §4.4 already says it: *"Include everything in the profile: … **plus HUD element
positions** (watermark, keystrokes, target HUD), which are currently not part of the config."*
The only per-window position that survives a restart today is the module **shortcut** button,
because it is written back into the `Module` and serialized.

---

## 2. Ask #1 — drag the ArrayList (and other HUD elements)

### 2.1 Why "inside the ClickGUI" cannot work literally

`OverlayClickGUI` is its own window:

* `MATCH_PARENT × MATCH_PARENT` + `FLAG_DIM_BEHIND`, `dimAmount = 0.7f` (`OverlayClickGUI.kt:78-85`)
* a full-screen `Box` with a `clickable {}` that **dismisses the GUI on any outside tap** (`:105-116`)
* it is added **after** the HUD windows ⇒ it is stacked **on top** of them.

So while the ClickGUI is open, the ArrayList is behind a dimmed, touch-swallowing sheet: the finger
never reaches it. There is no flag combination that lets a *lower* window receive touches that a
*higher* full-screen window consumes.

⇒ Three viable designs:

### 2.2 Design A — "HUD Edit Mode" launched from the ClickGUI (**recommended**)

This is what basically every client does, and it fits this codebase cleanly.

1. Add a header button in `OverlayClickGUI.HeaderBar()` (next to Discord/Website/Close, `:167-201`)
   labelled **HUD** / **Layout**.
2. Clicking it: `OverlayManager.dismissOverlayWindow(clickGUI)` then `OverlayManager.applyHudEditMode(true)`.
3. `OverlayManager` already tracks every live window in `overlayWindows` (`:25`, `:74-80`) — add:

```kotlin
var isHudEditMode by mutableStateOf(false)
    private set

/** HUD windows that take part in layout editing. */
private val editableHudWindows
    get() = overlayWindows.filter { it is HudOverlayWindow }   // marker interface / open val

fun applyHudEditMode(enabled: Boolean) {
    isHudEditMode = enabled
    val wm = currentContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
    editableHudWindows.forEach { win ->
        val lp = win.layoutParams
        lp.flags = if (enabled) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                   else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(win.composeView, lp) }
    }
    if (enabled) showOverlayWindow(hudEditExitButton) else dismissOverlayWindow(hudEditExitButton)
}
```

4. Each HUD window gets a shared base (e.g. `abstract class HudOverlayWindow : OverlayWindow()`)
   that: exposes its current `x/y/gravity`, renders an **edit chrome** overlay when
   `OverlayManager.isHudEditMode` (dashed border + "≡ Module name" grab label + corner handles),
   and installs the drag gesture — identical to `OverlayShortcutButton.kt:81-88`:

```kotlin
Modifier.pointerInput(isEditing) {
    if (!isEditing) return@pointerInput
    detectDragGestures { _, drag -> onDrag(drag.x, drag.y) }
}
```

5. A tiny always-on-top **"Done"** floating window (clone of `OverlayButton`) exits edit mode,
   re-applies `FLAG_NOT_TOUCHABLE`, and calls `ModuleManager.saveConfig()` /
   `ConfigManager.saveActiveProfile()` so the layout sticks.
6. Optional polish, all cheap in Compose: **snap to screen edges** (within ~24 px), **snap to a
   grid**, **alignment guides** when two elements share an x/y, and clamp to the display bounds on
   rotation exactly like `KeyStrokesOverlay.kt:157-165` / `OverlayShortcutButton.kt:66-71`.

**Why this is the right one:** WYSIWYG (you drag the *real* element at the *real* size on the *real*
screen), one mechanism upgrades **all eight** HUD elements at once, and it removes the current
"WaterMark/KeyStrokes/PieChart/Coordinates steal your touches during normal play" problem by making
touchability edit-mode-only.

### 2.3 Design B — a "Layout" tab *inside* the ClickGUI (proxy drag)

Add a `ModuleCategory`-less tab (like `ConfigurationScreen` is special-cased at
`OverlayClickGUI.kt:243-247`) that draws a rectangle representing the screen plus draggable
placeholder boxes for each HUD element; on release, convert dp→px and write `layoutParams.x/y`.

* Works, and it is literally "drag inside the click GUI".
* Costs: you must mirror every element's real size (which is `WRAP_CONTENT` and content-dependent —
  the ArrayList's width changes with the longest enabled module name!), convert dp↔px
  (`context.resources.displayMetrics.density`), account for each element's `gravity`, and the user
  still can't see the real result until they close the GUI (it's dimmed behind the sheet).
* Verdict: fine as a *complement* to A (a coarse "which corner" picker), poor as the only option.

### 2.4 Design C — numeric sliders (zero new infra)

Copy `TargetHudModule.kt:25-26`: `intValue("Position X", …)` + `intValue("Position Y", …)` and push
them in the module's `updateSettings()`. Renders automatically as sliders via
`ModuleContent.IntValueContent` (`ModuleContent.kt:277-338`).

⚠️ If you do this, **do not copy KeyStrokes' ranges**: `-100..100` px (`KeyStrokesModule.kt:29-30`)
cannot reach most of a 2400×1080 screen. Use the real display bounds, or store normalized
`0f..1f` floats and multiply by screen size at apply time (survives rotation/resolution changes).

### 2.5 Persistence + write-back (needed for A, B and C)

* Give the module position values, and have the **overlay write back** on drag end — the
  `OverlayShortcutButton.updateShortcut()` precedent (`:82-88`, `:104-107`). Cleanest hook is a
  lambda the module passes in, or a `var onPositionChanged: ((Int, Int) -> Unit)?` on the overlay
  companion that the module sets in `onEnabled()`.
* Because positions become ordinary `Value<*>`s, they are automatically included in
  `Module.toJson()` (`Module.kt:73-87`), `ModuleManager.saveConfig()/exportConfig()`
  (`ModuleManager.kt:174-229`) and every named profile in `ConfigManager` — that closes
  `IMPROVEMENTS.md` §4.4's last bullet for free.
* Re-entrancy trap: `WaterMarkModule` re-pushes settings every 500 ms (`WaterMarkModule.kt:26-34`)
  and `ArrayListModule` every 50 ms (`ArrayListModule.kt:32`, `:86-105`). Any `setPosition()` in
  that path must **not** fight the drag — write the dragged value back into the `Value` first, so
  the loop re-applies the *new* number.

### 2.6 Coordinate maths gotchas

* `x/y` are **relative to `gravity`**. The ArrayList uses `Gravity.TOP or Gravity.END`
  (`ArrayListOverlay.kt:71`), so `+x` moves it **left**, and `y` grows downward. Either negate the
  drag delta for END/BOTTOM gravities, or normalise every HUD window to `TOP|START` and compute
  absolute coordinates once (`PieChartOverlay` uses `BOTTOM|START` and does `_layoutParams.y -= dy`
  at `:203-204` — note the sign flip, exactly this issue).
* Keep `FLAG_NOT_FOCUSABLE` on HUD windows always; only the ClickGUI clears it
  (`OverlayWindow.kt:22-31`, `:49-56`). A focusable HUD would steal the back key / IME from the game.
* Wrap `updateViewLayout` in `runCatching` — it throws if the view isn't attached
  (`WaterMarkOverlay.kt:136-138` does this).

---

## 3. Ask #2 — Resource HUD (totems + strength potions), draggable

This is **already on the project roadmap**: `IMPROVEMENTS.md` §3 *"Resource counter overlay: totems /
egaps / strength pots remaining, rendered next to the hotbar (extend the existing overlay system in
`overlay/hud/`)"*, §1.3 *"Totem count awareness: expose remaining totem count to the HUD"*, and
priority table row 8 *"Resource/effect HUD counters — Low effort, Medium impact"*.

### 3.1 The data already exists — no new packet handling required

`LocalPlayer.inventory` is a `PlayerInventory` whose `content` is a **41-slot array kept in sync**
from `InventoryContentPacket`, `InventorySlotPacket`, `InventoryTransactionPacket`,
`MobEquipmentPacket`/`PlayerHotbarPacket` and even its own outgoing `ItemStackRequest`s
(`PlayerInventory.kt:31`, `:50-121`). Slot map: `0..35` inventory/hotbar, `36..39` armour,
`40` offhand (`PlayerInventory.kt:196-206`, `:265-292`).

| Counter | Rule | Existing precedent |
|---|---|---|
| **Totems** | `item.definition?.identifier == "minecraft:totem_of_undying"`, slots `0..35` **+ offhand 40** | `AutoTotemModule.kt:50-60` (`isTotem`, `findTotemInInventory`) and `PopCounterModule.kt:87` |
| **Strength potions** | `identifier == "minecraft:potion"` **and** `item.damage in setOf(31, 32, 33)` (regular / extended / strong) | `AutoPotModule.kt:31`, `:59-69` (`findPotionSlot`) |
| *(optional)* egaps / gaps | `minecraft:enchanted_golden_apple` / `minecraft:golden_apple` | `ConsumeTracker.CONSUMABLE_IDENTIFIERS` (`ConsumeTracker.kt:44-70`) |
| *(optional)* splash/lingering strength | `minecraft:splash_potion`, `minecraft:lingering_potion` + same metas | `ChestStealerModule.kt:81` |
| **Strength timer** | `session.localPlayer.getEffectById(Effect.STRENGTH)?.duration / 20` seconds | `AutoPotModule.kt:52-55` (`remainingSeconds`), `Entity.kt:167-169`, `Effect.kt` (`STRENGTH = 5`) |
| **Absorption hearts** | `player.attributes[Attribute.ABSORPTION]?.value` | `Attribute.kt`, `Entity.kt:93`, `:236-243` |

**Potion metadata verified** against the Bedrock item-data table (minecraft.wiki → *Potion §
Metadata*): Fire Resistance 12/13, Swiftness 14/15/16, Poison 25/26/27, **Regeneration 28/29/30**,
**Strength 31/32/33**, Weakness 34/35. So `AutoPotModule`'s constants are correct — reuse them
rather than re-deriving. (Only caveat: if a future version/server ever ships potion type in NBT
instead of the aux value, `item.damage` would read 0 — add an NBT fallback if that ever shows up.)

### 3.2 Update cadence

Don't scan per packet. Two proven throttles in this repo:

* coroutine loop, 50–200 ms — `ArrayListModule.kt:31-33`, `:85-96` / `CoordinatesModule.kt:75-90`
* packet-driven with a time gate — `PopCounterModule.kt:62-67` (150 ms poll on
  `PlayerAuthInputPacket`)

Scanning 41 slots is nothing; 200 ms feels instant for a counter. Push into the overlay via a
companion setter (`ResourceHudOverlay.setCounts(totems, pots, gaps)`), same as
`ArrayListOverlay.setModules()`.

### 3.3 Files to add / touch

```
NEW  overlay/hud/ResourceHudOverlay.kt        (clone KeyStrokesOverlay: companion setters,
                                               layoutParams, drag handler, Compose content)
NEW  game/module/misc/ResourceHudModule.kt    (values + poll loop + overlay enable/disable,
                                               clone ArrayListModule/WaterMarkModule lifecycle:
                                               onEnabled / onDisabled / onDisconnect)
EDIT game/ModuleManager.kt                    (1 line: add(ResourceHudModule()) in the Misc block,
                                               `:152-170`)
EDIT (ask #1) OverlayManager / HudOverlayWindow base for drag + persistence
```

Naming note: module `name` is the **config key** *and* the ArrayList label, rendered through
`TranslationUtil.toDisplayName()`. `"hud"` is in its `specialCases` map
(`TranslationUtil.kt:19`), so `Module("resource_hud", …)` displays as **"Resource HUD"**, while
`"resourcehud"` would display as "Resourcehud". Prefer the underscore form (same trick as
`"Auto Totem"` / `"Auto Pot"` which use literal spaces).

### 3.4 Suggested settings (all render automatically in the ClickGUI)

`boolValue` Show Totems / Show Strength Pots / Show Gaps / Show Effect Timer / Background / Border ·
`intValue` Font Size, Position X, Position Y, Low-Count Threshold · `floatValue` Scale, BG Opacity ·
`enumValue` Position (9 anchors — reuse `WaterMarkModule.Position`), Font (`NORMAL`/`MINECRAFT`,
`R.font.minecraft` is already bundled), Color Mode.
Nice extras: turn the number red under the threshold, and an optional client-only chat warning via
`session.displayClientMessage(...)` — *"2 totems left — disengage"* is exactly what
`IMPROVEMENTS.md` §1.3 asks for, and `displayClientMessage` never reaches the server
(`GameSession`, same mechanism `.command` replies use).

### 3.5 Icons

There are **no item textures** in the app (`app/src/main/assets/mcpedata/items/` holds only
`runtime_item_states_*.json` + `index.json`; the only images are server logos and UI icons in
`res/drawable/`). So the counter should be text-based (`⛨ 3   ⚔ 5`) or ship two small vector
drawables. Rendering real Bedrock item icons would mean extracting/packing textures — a separate,
much bigger job.

---

## 4. Ask #3 — corner-aware length ordering

### 4.1 What already exists

`SortMode.LENGTH` is the **default** (`ArrayListModule.kt:18`) — so the ArrayList is *already*
ordered by name length, not alphabetically:

```kotlin
// ArrayListOverlay.kt:218-223
val sortedModules = when (sortMode) {
    ArrayListModule.SortMode.LENGTH -> modules.sortedByDescending { it.name.length }
    ArrayListModule.SortMode.ALPHABETICAL -> modules.sortedBy { it.name }
    ArrayListModule.SortMode.CATEGORY -> modules.sortedBy { it.category }
    ArrayListModule.SortMode.CUSTOM -> modules.sortedByDescending { it.priority }
}
```

…but **descending** ⇒ longest at the **top**, which is correct for today's hard-coded anchor
`Gravity.TOP or Gravity.END` (`:71`) and right-aligned column (`:226`). That's the classic
top-right staircase:

```
TOP|END (today)                 BOTTOM|END (what you want)
┌──────────────┐ AutoTotem      ┌──────────────┐ Fly
│              │ Killaura               …      │ Speed
│              │ Sprint                 …      │ ESP
│              │ Fly                      AutoTotem ┐ corner
└──────────────┘ ESP ← shortest at bottom  └────────┘ longest at bottom
```

### 4.2 What to add

1. **Direction.** Either `SortMode.LENGTH_ASC` (new enum constant — `ArrayListModule.kt:143-145`,
   handled at `ArrayListOverlay.kt:219` and in `calculatePriority`, `ArrayListModule.kt:114-125`), or
   better a separate `boolValue("Reverse Order", false)` that just calls `.reversed()` on the sorted
   list so it composes with *every* sort mode.
2. **Anchor.** Add a `Position` enum like `WaterMarkModule.Position` (9 anchors,
   `WaterMarkModule.kt:66-70` → gravity mapping at `WaterMarkOverlay.kt:100-112`) or derive it
   automatically from the dragged x/y (see 4.3).
3. **Alignment flip.** `Column(horizontalAlignment = Alignment.End)` (`:226`) must become
   `Alignment.Start` for LEFT anchors, otherwise a bottom-**left** ArrayList still hugs its right
   edge and the staircase points the wrong way.
4. **Border flip.** `BorderStyle.LEFT` draws the accent bar on the left (`:305-321`); for a
   left-anchored list you want `RIGHT`. Auto-swap unless the user set it explicitly.
5. **Sort key.** Sort by the **displayed** string, not the raw name:
   `modules.sortedBy { it.name.translatedSelf.length }`. `TranslationUtil` inserts spaces at camel
   humps (`"PopCounter"` → `"Pop Counter"`, `"PieChart"` → `"Pie Chart"`), so raw length and rendered
   length disagree. With `FontStyle.MINECRAFT` (monospace-ish) character count ≈ width; with
   `FontStyle.NORMAL` (`FontFamily.Default`, proportional) the honest fix is to measure with a
   Compose `TextMeasurer` and sort by `layoutResult.size.width`.
6. **Smooth reordering.** `sortedModules.forEachIndexed { … }` (`:228`) has no `key()`, so when the
   order changes (a module toggles on, or you flip the sort direction) every row is recomposed in
   place rather than animated. Wrap the item in `key(module.name) { … }` and the list will glide
   into its new order — that also makes the existing `AnimatedVisibility` blocks (`:229-241`)
   meaningful.
7. 🐛 **Pre-existing bug in those `AnimatedVisibility` blocks**: `visible = true` is constant, so
   `exit` never runs (a disabled module vanishes instantly), and the `enter`/`exit` expressions mix
   `if/else` with `+`, so precedence makes them `if (fade) fadeIn() else (fadeOut(0) + …)` — i.e. the
   slide/expand branches are only reachable when fade is off. Worth cleaning up while we are in there
   (`:229-241`).

### 4.3 "It shifts when I put it in a corner" — auto-anchor

The nicest version of your request needs no new setting at all: after a drag ends, look at where the
window landed and configure itself.

```kotlin
private fun applyAnchorFromPosition(screenW: Int, screenH: Int) {
    val cx = layoutParams.x + measuredWidth / 2f     // absolute, gravity-normalised
    val cy = layoutParams.y + measuredHeight / 2f
    val right  = cx > screenW / 2f
    val bottom = cy > screenH / 2f

    layoutParams.gravity = (if (bottom) Gravity.BOTTOM else Gravity.TOP) or
                           (if (right)  Gravity.END    else Gravity.START)
    horizontalAlign = if (right) Alignment.End else Alignment.Start
    reverseOrder    = bottom            // bottom anchors ⇒ longest at the bottom
    effectiveBorder = if (right) BorderStyle.LEFT else BorderStyle.RIGHT
}
```

Combined with `WRAP_CONTENT` + `Gravity.END`, the staircase edge stays glued to the screen edge even
when the longest enabled module changes — which is the behaviour you described.

---

## 5. Suggested implementation order

| Step | Change | Files | Effort |
|---|---|---|---|
| 1 | Fix the `ArrayListOverlay` flags bug (§1.2) | `ArrayListOverlay.kt` | 5 min |
| 2 | `Position X/Y` (+ `Position` anchor enum) on `ArrayListModule`, write-back from the overlay, config-persisted | `ArrayListModule.kt`, `ArrayListOverlay.kt` | 1 h |
| 3 | Reverse/ascending order + alignment/border flip + auto-anchor (§4) | same two files | 1–2 h |
| 4 | `key()` + `AnimatedVisibility` cleanup for smooth reordering | `ArrayListOverlay.kt` | 30 min |
| 5 | `ResourceHudModule` + `ResourceHudOverlay` (totems / strength pots / gaps / timer), draggable + persisted | 2 new files, `ModuleManager.kt` | 2–4 h |
| 6 | `HudOverlayWindow` base + `OverlayManager.applyHudEditMode()` + "HUD Layout" button in the ClickGUI header + Done button + snap/guides | `OverlayWindow.kt`/new base, `OverlayManager.kt`, `OverlayClickGUI.kt`, all 8 HUD overlays | ½ day |
| 7 | (optional) Layout tab with proxy drag (§2.3) | `OverlayClickGUI.kt` + new screen | ½ day |

Steps 1–4 are self-contained and immediately visible. Step 6 is what makes dragging a *first-class*
feature for every element instead of an ArrayList-only hack, and it also fixes the current
touch-stealing of WaterMark/KeyStrokes/PieChart/Coordinates during normal play.

---

## 6. Verification

There is no JDK/Android SDK in this workspace, so nothing can be compiled locally; the repo builds via
`.github/workflows` (`./gradlew :app:assembleDebug` / `assembleRelease`, JDK 17 + the SDK on
`ubuntu-latest`). Any implementation should be pushed to a branch and checked against that workflow,
then smoke-tested on a device for: drag feels right in landscape, positions survive app restart **and**
config profile switch, rotation clamps elements back on screen, and no HUD element eats game touches
while edit mode is off.
