# AutoEat / AutoPot — Proxy-Level Design

Every class/enum referenced below exists in the protocol library vendored in
`relay/Protocol` (verified), so this is implementable with what WClient already ships.

---

## 1. How the proxy KNOWS the player is eating (so modules pause)

A MITM proxy sees every packet in both directions. Eating is visible three ways,
and we use all three (any one alone can miss edge cases):

| Signal | Direction | Packet | Meaning |
|---|---|---|---|
| **Start-use flag** | client → server | `PlayerAuthInputPacket` with `PlayerAuthInputData.START_USING_ITEM` in `inputData` | The player just pressed & held use. Combined with "held item is food" ⇒ manual eating started. |
| **Use transaction** | client → server | `InventoryTransactionPacket` type `ITEM_USE`, actionType `1` (click air) with a consumable in hand — or the `itemUseTransaction` attached to `PlayerAuthInputPacket` on server-auth-inventory servers | Same thing, on the transaction path. |
| **Authoritative state** | server → client | `SetEntityDataPacket` for `localPlayer.runtimeEntityId` with `EntityFlag.USING_ITEM` / `EntityFlag.EATING` set | The server itself confirms "this player is consuming". This is ground truth — it catches eating no matter how it started, and its clearing tells us exactly when the window ended. |

Implementation: a tiny `ConsumeTracker` in `GameSession` that watches these and
exposes `isUserConsuming` + `consumingUntilTick`. AutoEat/AutoPot/AutoTotem check it
and **hold off** while the user is manually eating — no double-eats, no interrupting
a gap the player started themselves.

The same tracker doubles as the **module interlock**: a single shared
"item-use lock" with priority `manual use > emergency gap > totem > pot`, so two
modules never fight over the hand in the same tick. Killaura also respects the lock
(an attack transaction mid-consume makes servers cancel the consume — the #1 cause
of "I ate but got nothing").

---

## 2. Sending the eat reliably (no fiddling, no lost seconds)

The full proxy-side eat, invisible to the client's screen:

```
1. SELECT    MobEquipmentPacket (server-bound only)
             hotbarSlot = food slot, item = food stack
             → server now believes we hold the gap; client UI untouched.

2. START     InventoryTransactionPacket
             transactionType = ITEM_USE, actionType = 1 (click air)
             hotbarSlot / itemInHand / playerPosition filled from PlayerInventory
             → server starts the consume, sets USING_ITEM on us.

   CHECKPOINT A: wait for SetEntityDataPacket with USING_ITEM (≈1 RTT —
   session.latency already measures this). Not received in ~250 ms → resend
   START once. Still nothing → abort + HUD warning (don't spam-loop into a kick).

3. HOLD      ~32 ticks (1.6 s) for food, ~31 for drinkable potions.
             During the window: keep the lock, no swings, no slot changes.
             (Optionally mirror START_USING_ITEM on outgoing PlayerAuthInput
             for anticheats that cross-check input flags.)

4. CONSUME   InventoryTransactionPacket
             transactionType = ITEM_RELEASE, actionType = 1 (CONSUME)
             same slot + item
             → server applies hunger/absorption/effects.

   CHECKPOINT B: confirmation = any of
     • UpdateAttributesPacket (hunger/saturation/absorption change)
     • MobEffectPacket ADD (egap: absorption/regen; pot: strength…)
     • InventorySlotPacket decrementing that food stack
   Not received in ~400 ms → retry the whole sequence ONCE. If the server
   instead *restores* the slot (rejection), abort and surface it on the HUD.

5. RESTORE   MobEquipmentPacket back to the player's real held slot
             (PlayerInventory.heldItemSlot — already tracked).
```

Why this doesn't desync the client: we only spoof **server-bound** equip packets;
the server's own `InventorySlotPacket`/`InventoryContentPacket` updates flow back
through the relay as usual, so the client's inventory shows the eaten gap count
correctly without us doing anything.

Why the checkpoints matter: "send and pray" is what loses fights. Every step has a
server acknowledgement with a measured deadline (sized from `LatencyTracker`), one
bounded retry, and a visible failure state instead of silent no-ops.

### Splash potions (the anarchy-meta case)

Splash strength/healing is **better** than drinking mid-fight because there is no
1.6 s window at all:

```
1. MobEquipmentPacket → splash pot slot (server-bound only)
2. Set localPlayer.silentRotation = pitch 90° down   (already supported!)
3. InventoryTransactionPacket ITEM_USE actionType 1 on the next input tick
4. Restore slot
```

One tick, lands at your feet even while airborne. Confirmation = `MobEffectPacket`
ADD for the expected effect; the effect-expiry timer (from the same packet's
duration field) drives the "re-pot 5 s before Strength runs out" logic.

### Fail-safes

- **Precondition check before starting**: food actually present (tracked
  `PlayerInventory.content`), not already consuming, lock free, player alive.
  If the gap/pot isn't in the hotbar, a restock move happens *between* fights
  (never mid-sequence).
- **Server-auth inventories** (`localPlayer.inventoriesServerAuthoritative == true`):
  legacy transactions may be rejected — detect it at Checkpoint A on first use,
  flip to the ItemStackRequest path, remember per-server.
- **Hard timeout** on the whole sequence (~2.5 s) that force-releases the lock, so
  a dropped packet can never wedge AutoTotem out of saving you.

---

## 3. Module surface (what the user configures)

**AutoEat**: keep-absorption mode (re-gap when absorption hits 0), emergency HP
threshold (force-eat below N hearts), food priority (egap → gap → other), min
hunger for normal food, "only in combat" toggle.

**AutoPot**: effects to maintain (Strength / Fire Res / Regen / Speed), re-apply
threshold in seconds before expiry, splash vs drink mode, "pause while user eats"
(always on, from the ConsumeTracker).

Both share the item-use lock with AutoTotem — totem always wins.
