# FMM Player Disguise Feature — Design

**Date:** 2026-05-16
**Status:** Approved, ready for implementation planning
**Context:** The existing `/fmm disguise` / `/fmm undisguise` commands and `PlayerDisguiseEntity` were a prototype that was never finished. A user (yongzhun) reported the disguise "doesn't display properly" and "allows me to hit myself". This document specifies a polished rework.

---

## 1. Scope & Commands

**Goal:** turn the prototype into a working, polished disguise system with admin tooling and an API surface for other plugins.

**Permissions** (replaces the current wildcard `freeminecraftmodels.*`):

- `freeminecraftmodels.disguise.self` — disguise yourself.
- `freeminecraftmodels.disguise.others` — disguise / undisguise / inspect other players.

**Commands:**

| Command | Permission | Notes |
|---|---|---|
| `/fmm disguise <model>` | `.self` | Disguises sender. Player-only sender. |
| `/fmm disguise <model> <player>` | `.others` | Disguises target. Console-allowed. |
| `/fmm undisguise` | `.self` | Undisguises sender. |
| `/fmm undisguise <player>` | `.others` | Undisguises target. Console-allowed. |
| `/fmm disguise list` | `.others` | Lists currently disguised players + their model IDs. |

**Tab completion:** rebuild the model-ID list on each tab-complete call. The current code builds it once in the constructor, so newly-loaded models never appear.

**Description bug:** both existing commands call `setDescription` twice, overwriting the human description with the usage line. Switch to `setUsage` / `setDescription` per the `AdvancedCommand` API.

---

## 2. Lifecycle & State

### DisguiseManager (new)

Single source of truth for who is disguised. Lives in `customentity` or a new `disguise/` package.

- Holds `Map<UUID, PlayerDisguiseEntity>` keyed by the disguised player's UUID.
- All disguise/undisguise paths funnel through it (command, API, listeners).
- Exposes: `disguise(player, modelID)`, `undisguise(player)`, `isDisguised(player)`, `getDisguise(player)`, `getAll()`.

### PlayerDisguiseEntity rework

The current class extends `DynamicEntity` and uses the player as `underlyingEntity`. That choice is the root cause of several bugs:

- `setUnderlyingEntity` wires the player into `loadedModeledEntitiesWithUnderlyingEntities`.
- `RegisterModelEntity.registerModelEntity` is skipped only via an `instanceof PlayerDisguiseEntity` hack at `ModeledEntity.java:130`.
- `UndisguiseCommand` then iterates all modeled entities looking for the player by UUID — fragile and slow.

Cleaner shape:

- `PlayerDisguiseEntity` keeps a direct `Player` reference as a dedicated field, not as `underlyingEntity`.
- `underlyingEntity` stays `null`. The model is "ownerless" from the existing system's point of view. Skeleton spawns at the player's location and gets re-positioned each tick.
- The `instanceof PlayerDisguiseEntity` check at `ModeledEntity.java:130` goes away — no underlying entity means no registration call at all.

### Lifecycle

1. **Create:** validate model ID exists → if player already disguised, undisguise first → spawn model at player → hide player body (see §3) → register in manager.
2. **Tick:** copy player location + rotation to skeleton; drive animation state machine (see §4).
3. **Destroy:** remove skeleton → restore visibility → unregister from manager.

### Listeners (all routed through DisguiseManager)

- `PlayerQuitEvent` → undisguise. This is the only auto-trigger; death, damage, and world change all preserve the disguise.
- `PluginDisableEvent` → undisguise everyone (clean shutdown).

---

## 3. Visibility & Hitbox

### Hiding the real player body

Current code calls `hideEntity` only for players online in the same world at disguise time and applies an `INVISIBILITY` potion. Both go away. Replace with:

- **`Player#setVisibleByDefault(false)`** at disguise start. Paper API. Makes the player invisible to all current and future viewers without needing per-viewer iteration or join/world-change listeners.
- **Drop the `INVISIBILITY` potion.** It was a workaround for held-item / armor rendering that `hideEntity` doesn't suppress. With `setVisibleByDefault` the whole entity packet flow is suppressed, so the potion is redundant. Removing it also avoids polluting the player's effect state and lets `UndisguiseCommand`'s `removePotionEffect` call go.

### Self-view trade-off

Per Q3 the player body is hidden to everyone, including self. `setVisibleByDefault` doesn't affect self-rendering — hiding the player from themselves in third-person would need packet-level hacks. **Accepted trade-off:** first-person stays normal (hand visible, HUD intact), third-person (F5) shows the model where the player expects to see themselves. If true self-hiding is needed later, it's a packet-level follow-up.

### Hitbox (decorative model)

The model's hitbox lives on its interaction component (`InteractionEntity` under the skeleton). For disguises:

- **Don't spawn the interaction entity** for `PlayerDisguiseEntity` (or spawn it with zero size).
- Attacks pass through the visible model and hit the real player at the player's hitbox location, which is right where the model is. Net effect: PvP works as if undisguised; the "I hit myself" bug disappears because there is no longer a model hitbox forwarding clicks back to the player.
- Mob AI behaves identically — they target the real player, not the (now non-existent) model hitbox.

---

## 4. Animation State Machine

Reserved animation names: **`idle`** (required), **`walk`**, **`attack`**, **`sneak`**, **`jump`**.

### State priority (highest wins each tick)

1. `attack` — one-shot, plays to completion, doesn't get interrupted.
2. `jump` — one-shot, plays to completion.
3. `sneak` — loop while crouched.
4. `walk` — loop while moving on ground, not crouched.
5. `idle` — fallback loop.

If a higher-priority one-shot is playing, lower-priority loops are suppressed until it finishes. `idle` plays whenever nothing else does.

### Triggers

| Animation | Driver | Detection |
|---|---|---|
| `attack` | `PlayerAnimationEvent` (arm swing) | Direct event hit. |
| `jump` | Per-tick: was on ground last tick, not this tick, Y-velocity > 0 | No Bukkit jump event exists; standard idiom. |
| `sneak` | `PlayerToggleSneakEvent` + per-tick `isSneaking()` re-check | Event gives edge; tick check keeps state correct after teleports etc. |
| `walk` / `idle` | Per-tick: compare current position to last tick's, threshold ~0.01 blocks | Avoids treating float drift as walking. |

### Missing-animation behavior

- Missing `walk` → falls back to `idle` while moving.
- Missing `attack` / `jump` / `sneak` → those triggers become no-ops; current loop continues.
- Missing `idle` → log a warning at model load. Model still works but freezes on its last frame whenever no other animation is active.

### Where this lives

A new `DisguiseAnimationController` owned by `PlayerDisguiseEntity`, ticked from the same task that copies player position to the skeleton, so position + animation update happen in lockstep — no visible desync between movement and walk animation.

---

## 5. Tick Loop & Public API

### Tick loop

`ModeledEntitiesClock` already ticks every `ModeledEntity` once per server tick (async, batched into a single packet bundle — see `ModeledEntitiesClock.tick`). `PlayerDisguiseEntity.tick(AbstractPacketBundle)` overrides the base method (like `DynamicEntity` does) and runs each tick, in order:

1. **Liveness check.** Player offline or dead → call `DisguiseManager.undisguise` and return.
2. **Position sync.** Copy `player.getLocation()` (x/y/z/yaw/pitch) to the skeleton. Pitch goes to head bone if the model has one; body yaw follows the player's body yaw, not head yaw, to match vanilla rendering.
3. **Animation update.** `DisguiseAnimationController.tick()` evaluates the priority chain and updates the current playing animation.
4. **Movement bookkeeping.** Store this tick's location + on-ground state for next tick's walk/jump detection.

Piggybacking on `ModeledEntitiesClock` keeps disguise ticking consistent with every other entity, batches packet sends, and avoids needing a separate `BukkitTask` to manage. The clock is async; `player.getLocation()` / `isSneaking()` / `isOnGround()` are safe to read async (they read cached state). Event-driven triggers (arm swing, sneak toggle) fire on the main thread and stash a flag the next async tick consumes — see `DisguiseAnimationController` below.

### Public API

`com.magmaguy.freeminecraftmodels.api.DisguiseAPI`:

```java
public final class DisguiseAPI {
    public static boolean disguise(Player player, String modelID);
    public static boolean undisguise(Player player);
    public static boolean isDisguised(Player player);
    public static @Nullable String getDisguiseModelID(Player player);
    public static Collection<Player> getDisguisedPlayers();
}
```

- All methods static, thin wrappers over `DisguiseManager`.
- `disguise` returns `false` if the model ID is unknown; `true` on success including the "already disguised → re-disguise as new model" path.
- No events fired in v1 (YAGNI — can add `PlayerDisguiseEvent` / `PlayerUndisguiseEvent` later if a consumer asks).
- Lives next to `ModeledEntityManager` so external plugins discover it the same way.

### Internal vs API split

`DisguiseManager` is internal (package-private constructor, accessed via the `FreeMinecraftModels` plugin singleton). `DisguiseAPI` is the only public entry point. Keeps refactor freedom.

---

## 6. Bug-Fix Mapping

| # | Bug | Fixed by |
|---|---|---|
| 1 | Self-only command | §1 — adds `<player>` form |
| 2 | Wildcard permission | §1 — `.self` / `.others` split |
| 3 | Double `setDescription` | §1 — proper `setUsage` / `setDescription` |
| 4 | No "already disguised" guard | §2 — manager funnels all create calls, re-disguise replaces cleanly |
| 5 | `UndisguiseCommand` UUID-scan never finds player | §2 — manager lookup by UUID is O(1) and authoritative |
| 6 | Only hides from same-world online players | §3 — `setVisibleByDefault(false)` covers current + future viewers |
| 7 | No logout cleanup, orphaned model | §2 — `PlayerQuitEvent` listener |
| 8 | `PersistentDataContainer.remove` on player is a no-op | §2 — `underlyingEntity` stays null; call goes away |
| 9 | Tab-complete list stale | §1 — rebuild per call |
| 10 | "I can hit myself" (reported bug) | §3 — interaction hitbox disabled |
| 11 | Model doesn't follow or animate | §4 + §5 — tick loop + animation controller |
| 12 | `INVISIBILITY` potion pollutes player state | §3 — drop the potion |

---

## 7. Implementation Phases

Each phase compiles and runs as a working plugin. The Discord reporter could test after phase 2 — both their complaints (display, self-hit) are fixed by then — and feedback can come in before later phases land.

1. **Foundation** — `DisguiseManager` + listener wiring + lifecycle. Rip out the `instanceof PlayerDisguiseEntity` hack at `ModeledEntity.java:130`. Both commands route through the manager. **Verify:** disguise/undisguise no longer leak; logout cleans up.
2. **Visibility & hitbox** — switch to `setVisibleByDefault`, drop potion, disable interaction hitbox. **Verify:** late-joining players see the model; PvP hits land on the player not the model; no self-damage.
3. **Tick loop + position sync** — model follows player smoothly. **Verify:** model tracks movement and rotation without visible lag.
4. **Animation controller** — state machine, fixed reserved names, fallback rules. **Verify:** test with a model that defines all 5 anims, one that defines only `idle`, and one missing `idle` (should warn).
5. **Commands polish + admin tooling** — `<player>` form, `disguise list`, permissions, tab-complete fix, description fix.
6. **Public API** — `DisguiseAPI` class in `api/` package.

## 8. Files Touched

**New:**
- `customentity/DisguiseManager.java` (or `disguise/DisguiseManager.java`)
- `customentity/DisguiseAnimationController.java`
- `listeners/DisguiseListeners.java`
- `api/DisguiseAPI.java`
- `commands/DisguiseListCommand.java`

**Modified:**
- `customentity/PlayerDisguiseEntity.java`
- `commands/DisguiseCommand.java`
- `commands/UndisguiseCommand.java`
- `customentity/ModeledEntity.java` (revert the `instanceof PlayerDisguiseEntity` check at line 130)
- `FreeMinecraftModels.java` (register listener + manager)
