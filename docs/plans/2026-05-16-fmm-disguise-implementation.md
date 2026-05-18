# FMM Player Disguise — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Rework the prototype `/fmm disguise` feature into a working, polished system per [the design doc](2026-05-16-fmm-disguise-design.md) — fixing the reported bugs (model doesn't display correctly, hitting yourself), wiring up logout cleanup, and adding admin tooling, animation mirroring, and a public API.

**Architecture:** A new `DisguiseManager` owns all disguise state (keyed by player UUID). `PlayerDisguiseEntity` is reshaped to **not** use `underlyingEntity` (it holds a direct `Player` reference instead), removing several hacks. Position + animation sync runs inside the existing async `ModeledEntitiesClock` tick — no per-disguise BukkitTask. A `DisguiseAnimationController` per disguise drives a 5-state priority machine (`attack > jump > sneak > walk > idle`) using fixed reserved animation names. Public surface lives in `api/DisguiseAPI.java`.

**Tech Stack:** Java 17+, Paper API 1.21.x, the project's MagmaCore command/permission framework, the existing `ModeledEntity` / `Skeleton` / `AnimationComponent` infrastructure. No new external dependencies. No unit-test infrastructure exists in this project (typical for Paper plugins) — verification per phase is **compile clean → build shaded jar → deploy to testbed → manual smoke test on a running Paper server**.

**Branch:** work directly on `master` per the user's "Commit doc, plan + implement in-place" choice. Each task is its own commit.

---

## Cross-Cutting Conventions

**Build command** (run from `FreeMinecraftModels/`):
```
mvn -q -DskipTests clean package
```
Expected: `BUILD SUCCESS` and `target/FreeMinecraftModels.jar` exists. If the user has TestBeds set up, the jar can then be synced via `setup_symlinks.bat` per their setup.

**Quick compile check** (faster, when you want to verify changes parse before a full build):
```
mvn -q -DskipTests compile
```

**Commit style:** match the recent repo style — short imperative subject, optional blow-by-blow body for substantial changes. Co-author trailer is fine. **Do not** bump the plugin version per task — version bump happens at the end of the feature work in one place (`pom.xml`).

**Touching `ModeledEntity` / `InteractionComponent` / `HitboxComponent`:** these are shared with all other modeled entities (props, dynamic mobs). Every change must preserve existing behavior for non-disguise paths. Prefer overriding methods on `PlayerDisguiseEntity` over branching on `instanceof PlayerDisguiseEntity` in shared code.

---

## Phase 1 — Foundation: Manager + Lifecycle

Goal: introduce `DisguiseManager` as the single source of truth, refactor `PlayerDisguiseEntity` to stop using `underlyingEntity`, wire the existing commands through the manager, and add the quit/shutdown cleanup listener. **At end of phase: `/fmm disguise <model>` works at the same fidelity as today (visible model, hittable, no animation), but logout cleans up properly and re-disguise no longer leaks a model.**

### Task 1.1: Create `DisguiseManager` skeleton

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/DisguiseManager.java`

**Step 1: Write the class**

```java
package com.magmaguy.freeminecraftmodels.customentity;

import lombok.Getter;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for active player disguises. Keyed by the disguised
 * player's UUID. All disguise creation / removal must go through this class so
 * lifecycle is consistent regardless of trigger (command, API, listener).
 */
public final class DisguiseManager {
    private static final Map<UUID, PlayerDisguiseEntity> disguises = new ConcurrentHashMap<>();

    private DisguiseManager() {}

    /**
     * Disguises {@code player} as the model with {@code modelID}. If the player
     * is already disguised, the previous disguise is removed first so the new
     * one replaces it cleanly.
     *
     * @return {@code true} on success, {@code false} if the model ID is unknown.
     */
    public static boolean disguise(Player player, String modelID) {
        undisguise(player); // replace cleanly if already disguised
        PlayerDisguiseEntity entity = PlayerDisguiseEntity.create(modelID, player);
        if (entity == null) return false;
        disguises.put(player.getUniqueId(), entity);
        return true;
    }

    /**
     * Undisguises {@code player} if currently disguised. No-op otherwise.
     *
     * @return {@code true} if a disguise was removed, {@code false} if there
     *         was nothing to remove.
     */
    public static boolean undisguise(Player player) {
        PlayerDisguiseEntity entity = disguises.remove(player.getUniqueId());
        if (entity == null) return false;
        entity.remove();
        return true;
    }

    public static boolean isDisguised(Player player) {
        return disguises.containsKey(player.getUniqueId());
    }

    @Nullable
    public static PlayerDisguiseEntity getDisguise(Player player) {
        return disguises.get(player.getUniqueId());
    }

    public static Collection<PlayerDisguiseEntity> getAll() {
        return Collections.unmodifiableCollection(disguises.values());
    }

    /**
     * Plugin-disable / reload cleanup: remove every active disguise.
     */
    public static void shutdown() {
        for (PlayerDisguiseEntity entity : disguises.values()) {
            entity.remove();
        }
        disguises.clear();
    }
}
```

**Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS. `PlayerDisguiseEntity.create(...)` still has the old signature `(String, Player)` and still returns `@Nullable PlayerDisguiseEntity`, so this compiles unchanged.

**Step 3: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/customentity/DisguiseManager.java
git commit -m "Add DisguiseManager skeleton for centralized disguise state"
```

---

### Task 1.2: Refactor `PlayerDisguiseEntity` to stop using `underlyingEntity`

The current class extends `DynamicEntity` and calls `spawn(targetPlayer)`, which wires the player as `underlyingEntity` and triggers `RegisterModelEntity.registerModelEntity` (suppressed only via an `instanceof` hack at `ModeledEntity.java:130`). After this task, the disguise model has no underlying entity — it's just a skeleton sitting at the player's location, with the `Player` reference held as a dedicated field.

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/PlayerDisguiseEntity.java` (rewrite)

**Step 1: Rewrite the class**

Replace the entire contents of `PlayerDisguiseEntity.java` with:

```java
package com.magmaguy.freeminecraftmodels.customentity;

import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;

/**
 * A modeled entity that visually replaces a player. The disguised
 * {@link Player} is held as a direct reference — this class does NOT set the
 * player as the {@code underlyingEntity}, so the modeled-entity-with-underlying
 * registry, packet interaction entity, and {@code RegisterModelEntity} PDC
 * tagging are all bypassed naturally (no special-casing needed in shared code).
 *
 * <p>Lifecycle is owned by {@link DisguiseManager}; instances should not be
 * created or destroyed directly outside the manager or the public API.
 */
public class PlayerDisguiseEntity extends ModeledEntity {
    @Getter
    private final Player disguisedPlayer;

    private PlayerDisguiseEntity(String entityID, Player disguisedPlayer) {
        super(entityID, disguisedPlayer.getLocation());
        this.disguisedPlayer = disguisedPlayer;
    }

    /**
     * Factory used by {@link DisguiseManager}. Returns {@code null} if the
     * model ID is not loaded.
     */
    @Nullable
    static PlayerDisguiseEntity create(String entityID, Player player) {
        FileModelConverter converter = FileModelConverter.getConvertedFileModels().get(entityID);
        if (converter == null) return null;
        PlayerDisguiseEntity entity = new PlayerDisguiseEntity(entityID, player);
        // Spawn at the player's location, with NO underlying entity. The
        // Location-only spawn path skips setUnderlyingEntity, which is what
        // we want — the player must not be wired into
        // loadedModeledEntitiesWithUnderlyingEntities or get the
        // RegisterModelEntity PDC tag.
        entity.spawn(player.getLocation());
        return entity;
    }

    @Override
    public Location getLocation() {
        // Always track the disguised player while they're online; fall back to
        // the last cached location otherwise (used briefly during the tick
        // after the player disconnects but before the manager removes us).
        if (disguisedPlayer != null && disguisedPlayer.isOnline()) {
            return disguisedPlayer.getLocation();
        }
        return super.getLocation();
    }
}
```

**Step 2: Revert the `instanceof PlayerDisguiseEntity` hack in `ModeledEntity`**

Open `src/main/java/com/magmaguy/freeminecraftmodels/customentity/ModeledEntity.java` at line ~127. Replace:

```java
    public void setUnderlyingEntity(Entity underlyingEntity) {
        this.underlyingEntity = underlyingEntity;
        loadedModeledEntitiesWithUnderlyingEntities.put(underlyingEntity, this);
        if (!(underlyingEntity instanceof PlayerDisguiseEntity))
            RegisterModelEntity.registerModelEntity(underlyingEntity, getSkeletonBlueprint().getModelName());
        hitboxComponent.setCustomHitboxOnUnderlyingEntity();
    }
```

with:

```java
    public void setUnderlyingEntity(Entity underlyingEntity) {
        this.underlyingEntity = underlyingEntity;
        loadedModeledEntitiesWithUnderlyingEntities.put(underlyingEntity, this);
        RegisterModelEntity.registerModelEntity(underlyingEntity, getSkeletonBlueprint().getModelName());
        hitboxComponent.setCustomHitboxOnUnderlyingEntity();
    }
```

The check was wrong anyway: `underlyingEntity` is an `Entity` (a Player in the old design), never a `PlayerDisguiseEntity` (which extends `ModeledEntity`, not `Entity`). The branch never triggered. Removing it is safe.

**Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS. Note: `DisguiseCommand` still references `PlayerDisguiseEntity.create(String, Player)` directly — that call site keeps working because we kept that factory (now package-private). The next task moves it behind the manager.

**Step 4: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/customentity/PlayerDisguiseEntity.java src/main/java/com/magmaguy/freeminecraftmodels/customentity/ModeledEntity.java
git commit -m "Refactor PlayerDisguiseEntity to not use underlyingEntity

Holds the disguised Player as a dedicated field; spawn() now uses the
Location-only path so the player is never wired into the underlying-
entity registry, the RegisterModelEntity PDC tag, or the packet
interaction entity. Removes the instanceof PlayerDisguiseEntity branch
in ModeledEntity.setUnderlyingEntity that was always dead code anyway
(underlyingEntity is an Entity, never a ModeledEntity)."
```

---

### Task 1.3: Make disguise model interactions no-op (decorative hitbox)

Per design §3, attacks must pass through the disguise model to the underlying player. This task makes the model interaction-inert.

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/PlayerDisguiseEntity.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/core/components/HitboxComponent.java` (line ~112)

**Step 1: Suppress packet interaction entity creation for disguises**

In `HitboxComponent.createPacketInteractionEntity()`, the current guard skips non-prop entities that already have an `underlyingEntity`. Disguises now have `underlyingEntity == null`, so without an extra guard they'd get a packet interaction entity that intercepts clicks. Override `displayInitializer` on `PlayerDisguiseEntity` instead of branching in shared code.

Add to `PlayerDisguiseEntity` (after the constructor):

```java
    /**
     * Disguises must not spawn a packet interaction entity — the model is
     * decorative and clicks should pass through to the underlying player.
     * Override skips the {@code createPacketInteractionEntity()} call that
     * the base class makes.
     */
    @Override
    protected void displayInitializer() {
        getSkeleton().generateDisplays();
    }
```

This requires `displayInitializer` to be `protected` on `ModeledEntity` — it already is (verified at `ModeledEntity.java:157`).

**Step 2: Neutralize OBB-raytrace and hitbox-contact dispatches**

Even without a packet interaction entity, `OBBHitDetection.raytraceFromPlayer` will still find the disguise (it scans all modeled entities) and call left/right-click callbacks. And `HitboxComponent.checkPlayerCollisions` fires hitbox-contact events. Set no-op callbacks in the constructor so these paths do nothing:

In `PlayerDisguiseEntity`'s private constructor, after `this.disguisedPlayer = disguisedPlayer;` add:

```java
        // Decorative model: every interaction path must be a no-op so attacks
        // pass through to the underlying player as if undisguised. The
        // default left/right-click handlers in InteractionComponent would
        // otherwise either try to attack the (null) underlying entity, or
        // mount the player onto the disguise model.
        setLeftClickCallback((player, entity) -> {});
        setRightClickCallback((player, entity) -> {});
        // hitbox-contact and projectile callbacks are null by default — the
        // null check at HitboxComponent.tick line 61 + 145 in
        // InteractionComponent skips dispatch entirely, so no override
        // needed. Keep them null.
```

(Do not set `setHitboxContactCallback`/`setModeledEntityHitByProjectileCallback` to no-ops — they're null by default and the dispatch path skips when null, which is more efficient than calling a no-op lambda.)

**Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 4: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/customentity/PlayerDisguiseEntity.java
git commit -m "Make disguise model interaction-inert

Override displayInitializer to skip packet interaction entity creation,
and set no-op left/right-click callbacks so OBB-raytrace clicks are
swallowed. Fixes the user-reported 'I can hit myself' bug — attacks now
pass through the visible model and hit the underlying player at its
real hitbox location."
```

---

### Task 1.4: Route commands through `DisguiseManager`

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/commands/DisguiseCommand.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/commands/UndisguiseCommand.java`

These get only the manager-routing change in this task; full polish (player target, new permissions, tab-complete fix, description fix, `list` subcommand) lands in Phase 5. Doing them now would change too much surface area at once.

**Step 1: `DisguiseCommand.execute` — call manager**

Replace the body of `execute` with:

```java
    @Override
    public void execute(CommandData commandData) {
        String modelID = commandData.getStringArgument("models");
        if (!entityIDs.contains(modelID)) {
            Logger.sendMessage(commandData.getCommandSender(), "Invalid entity ID!");
            return;
        }
        if (!DisguiseManager.disguise(commandData.getPlayerSender(), modelID)) {
            Logger.sendMessage(commandData.getCommandSender(),
                    "Failed to disguise — model '" + modelID + "' could not be created.");
        }
    }
```

Update the import: `import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;`
Remove the now-unused `import com.magmaguy.freeminecraftmodels.customentity.PlayerDisguiseEntity;`.

**Step 2: `UndisguiseCommand.execute` — call manager**

Replace the entire `execute` method with:

```java
    @Override
    public void execute(CommandData commandData) {
        Player sender = commandData.getPlayerSender();
        if (!DisguiseManager.undisguise(sender)) {
            // Sender wasn't disguised; nothing to do. Stay silent for the
            // common case (admins routinely undisguise on quit etc.).
            return;
        }
    }
```

Add import: `import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;`
Add import: `import org.bukkit.entity.Player;`
Remove imports no longer needed: `ModeledEntityManager`, `ModeledEntity`, `PotionEffectType`. Remove the unused `entityIDs` field too.

**Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 4: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/commands/DisguiseCommand.java src/main/java/com/magmaguy/freeminecraftmodels/commands/UndisguiseCommand.java
git commit -m "Route disguise/undisguise commands through DisguiseManager

UndisguiseCommand no longer iterates all modeled entities looking for a
UUID match (which never worked anyway — underlyingEntity is no longer
set to the player). Both commands now share the manager's lifecycle so
re-disguising replaces cleanly and there's no orphaned model state."
```

---

### Task 1.5: Add quit / shutdown cleanup listener

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/listeners/DisguiseListeners.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java` (line ~193 area, with the other `registerEvents` calls)

**Step 1: Create the listener**

```java
package com.magmaguy.freeminecraftmodels.listeners;

import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Per design Q6 the only auto-undisguise trigger is player logout —
 * death, damage, and world change all preserve the disguise. The
 * plugin-disable path goes through DisguiseManager.shutdown() called
 * from the main plugin onDisable.
 */
public class DisguiseListeners implements Listener {
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        DisguiseManager.undisguise(event.getPlayer());
    }
}
```

**Step 2: Register the listener in `FreeMinecraftModels.syncInitialization`**

Find the block in `FreeMinecraftModels.java` around line 193 (the cluster of `Bukkit.getPluginManager().registerEvents(...)` calls). Add one new line just below `registerEvents(new MountDismountListener(), this);`:

```java
        Bukkit.getPluginManager().registerEvents(new DisguiseListeners(), this);
```

Add the import at the top of the file:

```java
import com.magmaguy.freeminecraftmodels.listeners.DisguiseListeners;
```

**Step 3: Wire shutdown into the existing shutdown path**

Find `ModeledEntity.shutdown()` calls in `FreeMinecraftModels.java` (`reloadImportedContent` at line ~256 and the plugin `onDisable` — search for `ModeledEntity.shutdown()`). Add `DisguiseManager.shutdown();` **before** each `ModeledEntity.shutdown()` call so disguises are torn down cleanly before the global modeled-entity cleanup runs.

Add the import: `import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;`

**Step 4: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 5: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/listeners/DisguiseListeners.java src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java
git commit -m "Add disguise quit listener + plugin-disable cleanup"
```

---

### Task 1.6: Phase 1 build + manual verification

**Step 1: Full build**

Run: `mvn -q -DskipTests clean package`
Expected: BUILD SUCCESS, `target/FreeMinecraftModels.jar` exists.

**Step 2: Deploy to testbed**

Per the user's testbed setup (memory: `setup_symlinks.bat`), the jar is sync'd to the testbed plugins folder. Start the testbed Paper server.

**Step 3: Manual smoke test**

Run these commands in-game (as a player with the wildcard permission, which the prototype already requires):

| Action | Expected |
|---|---|
| `/fmm disguise <existingModelID>` | Player vanishes, model appears at player location, **model does not animate or follow** (that's later phases). |
| `/fmm undisguise` | Model disappears. Player visible again. |
| `/fmm disguise <id>` then `/fmm disguise <id>` again | Second call replaces the first cleanly — only one model. |
| `/fmm disguise <id>` then log out, log back in | On rejoin, player is undisguised (the original model was cleaned up at quit). |
| `/fmm disguise <invalidID>` | "Failed to disguise" message; no model spawned. |
| Punch the visible model | Attack lands on the player underneath (no "I hit myself" anymore — though damage may be cosmetic depending on PvP gamerules). |
| `/reload confirm` or plugin disable | No leftover armor stands / displays in the world. |

**Step 4: If anything fails**, fix the regression before continuing. Don't proceed to Phase 2 with a broken baseline.

**Step 5: Commit any fixes individually.** No commit for the verification itself.

---

## Phase 2 — Visibility Fix

Goal: replace the broken "iterate online same-world players + INVISIBILITY potion" approach with `setVisibleByDefault(false)`, so late-joiners and world-changers see the disguise correctly. **At end of phase: yongzhun's "doesn't display properly" complaint is fixed.**

### Task 2.1: Switch hide path to `setVisibleByDefault`

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/PlayerDisguiseEntity.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/DisguiseManager.java`

**Step 1: Apply visibility hide in `PlayerDisguiseEntity.create`**

Add to the `create` method, just before `return entity;`:

```java
        // setVisibleByDefault(false) covers all current AND future viewers
        // via a single Paper API call — no need to iterate online players
        // or listen for joins/world-changes. The previous prototype's
        // per-viewer hideEntity loop only covered same-world players online
        // at disguise time, which is why yongzhun reported the disguise
        // "doesn't display properly".
        player.setVisibleByDefault(false);
```

**Step 2: Restore visibility on undisguise**

In `PlayerDisguiseEntity`, override `remove` to restore the player's default visibility:

```java
    @Override
    public void remove() {
        if (disguisedPlayer != null && disguisedPlayer.isOnline()) {
            disguisedPlayer.setVisibleByDefault(true);
        }
        super.remove();
    }
```

(The base `ModeledEntity.remove` cleanly handles `underlyingEntity == null`, so calling `super.remove()` is safe.)

**Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 4: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/customentity/PlayerDisguiseEntity.java
git commit -m "Hide disguised player via setVisibleByDefault

Replaces the per-viewer hideEntity loop that only covered same-world
players online at disguise time. setVisibleByDefault propagates to all
current and future viewers in one API call. Visibility is restored in
remove() so undisguise leaves no residue."
```

---

### Task 2.2: Phase 2 verification

**Step 1: Build + deploy**

Run: `mvn -q -DskipTests clean package`
Sync jar to testbed, restart server.

**Step 2: Multi-player visibility test**

With at least two test players on the server:

| Action | Expected |
|---|---|
| P1 disguises. P2 is in same world. | P2 sees the disguise model, P1 invisible. |
| P1 disguises. P2 joins server **after** disguise. | P2 sees the disguise model on first frame, never sees P1's real body. |
| P1 disguises, then travels to a different world; P2 follows. | P2 sees the disguise model in the new world. |
| P1 undisguises. P2 immediately sees the real P1. | Visibility restored. |

If you only have one test player, use a second client (an alt account or a Mineflayer bot) to verify the late-joiner case — it's the regression the user actually reported.

**Step 3: Verify no INVISIBILITY potion in the player's effect list** (`/effect query @s minecraft:invisibility` should report "none").

**Step 4: If verified, no commit. Proceed to Phase 3.**

---

## Phase 3 — Position & Rotation Sync

Goal: make the disguise model follow the player's location and head/body rotation every tick.

### Task 3.1: Override `tick` to sync skeleton position and rotation

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/PlayerDisguiseEntity.java`

**Step 1: Add the tick override**

```java
    @Override
    public void tick(com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle bundle) {
        if (!disguisedPlayer.isOnline()) {
            // Liveness check: drop the disguise cleanly if the player object
            // became invalid mid-tick (e.g. kicked between the quit event
            // firing and the next async tick). Per design Q6 the only
            // auto-undisguise trigger is logout — death, damage, and world
            // change all preserve the disguise, so we deliberately do NOT
            // check isDead() here.
            DisguiseManager.undisguise(disguisedPlayer);
            return;
        }

        // player.getEyeLocation() is safe to call async — it returns a copy
        // of the cached state, same pattern as DynamicEntity.syncSkeletonWithEntity.
        org.bukkit.Location eye = disguisedPlayer.getEyeLocation();
        getSkeleton().setCurrentHeadPitch(eye.getPitch());
        getSkeleton().setCurrentHeadYaw(eye.getYaw());

        super.tick(bundle);
    }
```

Promote the import: `import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;` (already in the same package — no import needed; just inline reference works). Same for `AbstractPacketBundle` — clean up by adding the proper imports rather than fully-qualifying.

**Step 2: Override `getLocation` to drop the cached `super.getLocation()` fallback once the player is gone**

Currently the `getLocation` returns `super.getLocation()` if the player is offline — but `ModeledEntity.spawn(Location)` set `currentLocation` to the location at spawn time, which is now stale and would freeze the model in place. Replace the fallback:

```java
    @Override
    public org.bukkit.Location getLocation() {
        if (disguisedPlayer != null && disguisedPlayer.isOnline()) {
            return disguisedPlayer.getLocation();
        }
        return null;
    }
```

`ModeledEntity.tick` already handles `getLocation() == null` by short-circuiting (`if (isRemoved || getLocation() == null) return;` at line 186), so returning null is safe.

**Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 4: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/customentity/PlayerDisguiseEntity.java
git commit -m "Sync disguise model position and rotation to the player each tick

Overrides tick(AbstractPacketBundle) — runs inside the existing async
ModeledEntitiesClock, no new BukkitTask. Reads player.getLocation() and
eye rotation (both async-safe), pushes head pitch/yaw to the skeleton.
Liveness check drops the disguise cleanly if the player goes offline
between the quit event and the next tick. getLocation() now returns
null when the player is gone so the base tick short-circuits."
```

---

### Task 3.2: Phase 3 verification

Run: `mvn -q -DskipTests clean package`
Sync jar to testbed, restart server.

| Action | Expected |
|---|---|
| Disguise, then walk forward. | Model slides forward with the player. No visible lag. |
| Disguise, then look around (mouse only). | Model's head bone rotates to match the player's view. |
| Disguise, then sprint in a circle. | Body yaw follows the player's body rotation (clamped so head doesn't snap >45° from body — handled by `DynamicEntity.getBodyLocation()` logic if we want it later, but for now just `setCurrentHeadYaw` is fine). |
| Disguise, then teleport (`/tp`). | Model jumps to the new location with the player. |
| Disguise model still doesn't animate. | Expected — animation is Phase 4. |

If position tracking lags noticeably, check whether `ModeledEntitiesClock` is actually running (`/timings` reports it as an async task) — that's the same path every other entity uses, so it should be fine.

---

## Phase 4 — Animation State Machine

Goal: drive `idle` / `walk` / `attack` / `sneak` / `jump` based on player actions per design §4.

### Task 4.1: Create `DisguiseAnimationController`

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/DisguiseAnimationController.java`

**Step 1: Write the controller**

```java
package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-disguise state machine driving reserved animation names. Priority
 * (highest wins each tick): attack > jump > sneak > walk > idle.
 *
 * <p>Event-driven triggers (arm swing, sneak toggle) fire on the main
 * thread and set volatile flags that the async tick consumes. All other
 * inputs (location, ground state) are read directly from the player each
 * tick via async-safe Bukkit calls.
 */
public class DisguiseAnimationController {
    // Reserved animation names — contract with modelers.
    private static final String ANIM_IDLE = "idle";
    private static final String ANIM_WALK = "walk";
    private static final String ANIM_ATTACK = "attack";
    private static final String ANIM_SNEAK = "sneak";
    private static final String ANIM_JUMP = "jump";

    // Movement threshold in blocks-squared. Avoids treating float drift
    // / micro-jitter as walking. ~0.01 blocks => 0.0001 squared.
    private static final double MOVEMENT_THRESHOLD_SQ = 0.0001;

    private final PlayerDisguiseEntity disguise;
    private final Player player;

    // Edge-triggered one-shot animations: set on main thread, consumed
    // on next async tick. AtomicBoolean for visibility + cheap CAS.
    private final AtomicBoolean attackPending = new AtomicBoolean(false);

    // Tick-bookkeeping state.
    private Vector lastTickPosition = null;
    private boolean wasOnGround = true;
    private String currentLoopAnimation = null;

    // One-shot animation tracking: when set, we suppress lower-priority
    // loops until the one-shot's animation has had time to play.
    // We use a tick countdown rather than callback hooks because the
    // animation system here doesn't expose completion callbacks.
    private int oneShotTicksRemaining = 0;
    // Default duration (in server ticks) we assume a one-shot takes if
    // we can't measure it. 20 ticks = 1 second — reasonable upper bound
    // for an attack swing or jump anim. Tune if needed.
    private static final int DEFAULT_ONE_SHOT_DURATION_TICKS = 20;

    public DisguiseAnimationController(PlayerDisguiseEntity disguise) {
        this.disguise = disguise;
        this.player = disguise.getDisguisedPlayer();
        warnIfMissingIdle();
    }

    /** Called from main thread by PlayerAnimationEvent. */
    public void onArmSwing() {
        attackPending.set(true);
    }

    /**
     * Called once per async tick from {@link PlayerDisguiseEntity#tick}.
     */
    public void tick() {
        if (oneShotTicksRemaining > 0) {
            oneShotTicksRemaining--;
            // Still bookkeeping movement/ground state so the next loop
            // pick after the one-shot finishes has fresh data.
            updateMovementBookkeeping();
            return;
        }

        // Priority: attack > jump > sneak > walk > idle.
        if (attackPending.getAndSet(false) && disguise.hasAnimation(ANIM_ATTACK)) {
            playOneShot(ANIM_ATTACK);
            return;
        }

        boolean onGround = player.isOnGround();
        boolean jumped = wasOnGround && !onGround && player.getVelocity().getY() > 0;
        if (jumped && disguise.hasAnimation(ANIM_JUMP)) {
            playOneShot(ANIM_JUMP);
            // fall through to bookkeeping; updateMovementBookkeeping below.
            wasOnGround = onGround;
            return;
        }

        if (player.isSneaking() && disguise.hasAnimation(ANIM_SNEAK)) {
            ensureLoop(ANIM_SNEAK);
            updateMovementBookkeeping();
            return;
        }

        if (isMoving() && disguise.hasAnimation(ANIM_WALK)) {
            ensureLoop(ANIM_WALK);
            updateMovementBookkeeping();
            return;
        }

        ensureLoop(ANIM_IDLE);
        updateMovementBookkeeping();
    }

    private boolean isMoving() {
        if (lastTickPosition == null) return false;
        Vector cur = player.getLocation().toVector();
        return cur.distanceSquared(lastTickPosition) > MOVEMENT_THRESHOLD_SQ;
    }

    private void updateMovementBookkeeping() {
        lastTickPosition = player.getLocation().toVector();
        wasOnGround = player.isOnGround();
    }

    private void playOneShot(String name) {
        // blend=false (interrupts loop), loop=false (one-shot).
        disguise.playAnimation(name, false, false);
        oneShotTicksRemaining = DEFAULT_ONE_SHOT_DURATION_TICKS;
        currentLoopAnimation = null; // re-evaluate loop after the one-shot.
    }

    private void ensureLoop(String name) {
        if (name.equals(currentLoopAnimation)) return; // already playing.
        if (!disguise.hasAnimation(name)) {
            if (!ANIM_IDLE.equals(name)) {
                // Missing walk/sneak — fall back to idle silently per design.
                ensureLoop(ANIM_IDLE);
            }
            return;
        }
        disguise.playAnimation(name, false, true);
        currentLoopAnimation = name;
    }

    private void warnIfMissingIdle() {
        if (!disguise.hasAnimation(ANIM_IDLE)) {
            Logger.warn("Disguise model '" + disguise.getEntityID()
                    + "' has no '" + ANIM_IDLE
                    + "' animation — disguised players using this model will freeze on the last animation frame whenever no other action is active.");
        }
    }
}
```

**Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 3: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/customentity/DisguiseAnimationController.java
git commit -m "Add DisguiseAnimationController with priority state machine

Reserved animation names: idle (required), walk, attack, sneak, jump.
Priority attack > jump > sneak > walk > idle. One-shots use a tick
countdown to suppress lower-priority loops (animation system has no
completion callback). Edge-triggered actions (arm swing) use
AtomicBoolean flags set from the main thread, consumed by the async
tick. Warns once at construction if 'idle' is missing."
```

---

### Task 4.2: Wire controller into `PlayerDisguiseEntity` lifecycle

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/PlayerDisguiseEntity.java`

**Step 1: Add the controller field and instantiation**

```java
    @Getter
    private final DisguiseAnimationController animationController;
```

Initialize it in the constructor (after the no-op callbacks block):

```java
        this.animationController = new DisguiseAnimationController(this);
```

**Step 2: Call `animationController.tick()` from the tick override**

Update the `tick(AbstractPacketBundle)` override to call the controller right after the position/rotation sync, before `super.tick(bundle)`:

```java
        getSkeleton().setCurrentHeadPitch(disguisedPlayer.getEyeLocation().getPitch());
        getSkeleton().setCurrentHeadYaw(disguisedPlayer.getEyeLocation().getYaw());
        animationController.tick();
        super.tick(bundle);
```

**Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 4: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/customentity/PlayerDisguiseEntity.java
git commit -m "Wire DisguiseAnimationController into PlayerDisguiseEntity tick"
```

---

### Task 4.3: Add `PlayerAnimationEvent` (arm swing) listener

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/listeners/DisguiseListeners.java`

**Step 1: Add the arm-swing handler**

```java
    @EventHandler
    public void onArmSwing(org.bukkit.event.player.PlayerAnimationEvent event) {
        PlayerDisguiseEntity disguise = DisguiseManager.getDisguise(event.getPlayer());
        if (disguise == null) return;
        disguise.getAnimationController().onArmSwing();
    }
```

Add imports as needed: `com.magmaguy.freeminecraftmodels.customentity.PlayerDisguiseEntity`.

**Note:** We don't need a sneak listener — sneak state is read per-tick from `player.isSneaking()`. Same for movement and jump (per-tick reads).

**Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 3: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/listeners/DisguiseListeners.java
git commit -m "Forward player arm-swing to the disguise animation controller"
```

---

### Task 4.4: Phase 4 verification

Run: `mvn -q -DskipTests clean package`
Sync jar to testbed, restart server.

You'll need a test model with the reserved animations. Use any FMM model that has them; otherwise create a quick test by aliasing animations in an existing model's `.bbmodel`.

| Action | Expected |
|---|---|
| Disguise as a model with all 5 anims. Stand still. | `idle` loops. |
| Walk forward. | `walk` loops; reverts to `idle` when you stop. |
| Sneak (shift). | `sneak` loops; reverts when you release shift. |
| Left-click. | `attack` plays once (one-shot); returns to whatever loop fits. |
| Jump. | `jump` plays once; returns to walk/idle on landing. |
| Disguise as a model with **only** `idle`. | Stays idle through movement/sneak/swing (fallback). No warnings. |
| Disguise as a model with **no** `idle`. | One log warning at disguise time. Model freezes on last frame between transitions. |
| Move + swing simultaneously. | `attack` wins (higher priority); walk resumes after one-shot finishes. |

If one-shot timing feels wrong (returns to loop too fast/slow), tune `DEFAULT_ONE_SHOT_DURATION_TICKS` (currently 20 = 1 sec).

---

## Phase 5 — Commands Polish + Admin Tooling

Goal: split permissions, add `<player>` admin form, add `/fmm disguise list`, fix the description/usage bug, fix stale tab-complete.

### Task 5.1: Permission split

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/commands/DisguiseCommand.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/commands/UndisguiseCommand.java`
- Modify: `src/main/resources/plugin.yml` (if it declares permissions explicitly)

**Step 1: Replace wildcard permissions**

In `DisguiseCommand` constructor, replace:
```java
setPermission("freeminecraftmodels.*");
```
with:
```java
setPermission("freeminecraftmodels.disguise.self");
```

In `UndisguiseCommand` constructor, same edit.

**Step 2: Check plugin.yml**

Run: `grep -n disguise src/main/resources/plugin.yml`

If permissions are declared there (with children/defaults), add the two new nodes and make `freeminecraftmodels.*` imply them. If `plugin.yml` doesn't declare per-command permissions, no change needed.

**Step 3: Fix double `setDescription` in both commands**

In `DisguiseCommand` constructor, current code:
```java
setDescription("Disguises a player as a model");
setPermission(...);
setDescription("/fmm disguise <modelID>");
```
Replace with:
```java
setDescription("Disguises a player as a model");
setPermission("freeminecraftmodels.disguise.self");
setUsage("/fmm disguise <modelID>");
```

Same fix in `UndisguiseCommand` (description and usage).

**Step 4: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 5: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/commands/DisguiseCommand.java src/main/java/com/magmaguy/freeminecraftmodels/commands/UndisguiseCommand.java
git commit -m "Split disguise permissions; fix double setDescription bug

Replaces wildcard freeminecraftmodels.* with freeminecraftmodels.disguise.self
on both commands (the .others permission lands with the admin form in
the next commit). Both commands also called setDescription twice — the
second call overwrote the human-readable description with the usage
string. Now uses setUsage() for the usage line."
```

---

### Task 5.2: Admin `<player>` form

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/commands/DisguiseCommand.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/commands/UndisguiseCommand.java`

The MagmaCore AdvancedCommand framework doesn't natively support optional args (calling `getStringArgument` on a missing arg logs an error). Strategy: register `addPlayerArgument("target")` so it appears in tab-complete, then in `execute()` inspect `commandData.getArgs()` directly to detect whether the target was supplied. Permit any sender for the targeted form (so console can disguise players); restrict the self form to player senders.

**Step 1: `DisguiseCommand` — add target arg and dispatch**

Updated constructor:

```java
    public DisguiseCommand() {
        super(List.of("disguise"));
        setDescription("Disguises a player as a model (or another player if a target is given)");
        setPermission("freeminecraftmodels.disguise.self");
        setUsage("/fmm disguise <modelID> [player]");
        // Allow any sender — we check the sender type per branch in execute().
        setSenderType(SenderType.ANY);
        entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(fmc -> entityIDs.add(fmc.getID()));
        addArgument("models", new ListStringCommandArgument(entityIDs, "<modelID>"));
        addPlayerArgument("target");
    }
```

Updated `execute`:

```java
    @Override
    public void execute(CommandData commandData) {
        String modelID = commandData.getStringArgument("models");
        if (modelID == null || !entityIDs.contains(modelID)) {
            Logger.sendMessage(commandData.getCommandSender(), "Invalid entity ID!");
            return;
        }

        // args[0] = "disguise"; args[1] = model; args[2] = optional target
        String[] args = commandData.getArgs();
        Player target;
        if (args.length >= 3 && !args[2].isBlank()) {
            // Targeted form — requires .others permission.
            if (!commandData.getCommandSender().hasPermission("freeminecraftmodels.disguise.others")) {
                Logger.sendMessage(commandData.getCommandSender(),
                        "§cYou lack permission: freeminecraftmodels.disguise.others");
                return;
            }
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                Logger.sendMessage(commandData.getCommandSender(),
                        "Player '" + args[2] + "' is not online.");
                return;
            }
        } else {
            // Self form — requires the sender be a player.
            if (!(commandData.getCommandSender() instanceof Player playerSender)) {
                Logger.sendMessage(commandData.getCommandSender(),
                        "Specify a target player when running this command from console: /fmm disguise <modelID> <player>");
                return;
            }
            target = playerSender;
        }

        if (!DisguiseManager.disguise(target, modelID)) {
            Logger.sendMessage(commandData.getCommandSender(),
                    "Failed to disguise — model '" + modelID + "' could not be created.");
        }
    }
```

Add imports: `org.bukkit.Bukkit`, `org.bukkit.entity.Player`. `com.magmaguy.freeminecraftmodels.customentity.DisguiseManager` is already imported from Task 1.4.

**Step 2: Same pattern for `UndisguiseCommand`**

```java
    public UndisguiseCommand() {
        super(List.of("undisguise"));
        setDescription("Undisguises a player currently disguised as a model");
        setPermission("freeminecraftmodels.disguise.self");
        setUsage("/fmm undisguise [player]");
        setSenderType(SenderType.ANY);
        addPlayerArgument("target");
    }

    @Override
    public void execute(CommandData commandData) {
        String[] args = commandData.getArgs();
        Player target;
        if (args.length >= 2 && !args[1].isBlank()) {
            if (!commandData.getCommandSender().hasPermission("freeminecraftmodels.disguise.others")) {
                Logger.sendMessage(commandData.getCommandSender(),
                        "§cYou lack permission: freeminecraftmodels.disguise.others");
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                Logger.sendMessage(commandData.getCommandSender(),
                        "Player '" + args[1] + "' is not online.");
                return;
            }
        } else {
            if (!(commandData.getCommandSender() instanceof Player playerSender)) {
                Logger.sendMessage(commandData.getCommandSender(),
                        "Specify a target player when running this command from console: /fmm undisguise <player>");
                return;
            }
            target = playerSender;
        }

        DisguiseManager.undisguise(target);
    }
```

Add imports: `org.bukkit.Bukkit`, `org.bukkit.entity.Player`, `com.magmaguy.magmacore.util.Logger` (if not already there).

**Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 4: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/commands/DisguiseCommand.java src/main/java/com/magmaguy/freeminecraftmodels/commands/UndisguiseCommand.java
git commit -m "Add admin <player> form to disguise / undisguise commands

Both commands accept an optional trailing player argument. The targeted
form requires freeminecraftmodels.disguise.others; the self form
requires only .self. Console can use the targeted form. The MagmaCore
AdvancedCommand framework has no native optional-arg support, so the
execute() body inspects args.length directly instead of going through
commandData.getStringArgument (which would log 'Key not found' for the
missing arg)."
```

---

### Task 5.3: `/fmm disguise list` subcommand

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/commands/DisguiseListCommand.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java` (register the new command)

**Step 1: Write the command**

```java
package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;
import com.magmaguy.freeminecraftmodels.customentity.PlayerDisguiseEntity;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.util.Logger;

import java.util.Collection;
import java.util.List;

public class DisguiseListCommand extends AdvancedCommand {

    public DisguiseListCommand() {
        super(List.of("disguiselist"));
        setDescription("Lists currently disguised players");
        setPermission("freeminecraftmodels.disguise.others");
        setUsage("/fmm disguiselist");
        setSenderType(SenderType.ANY);
    }

    @Override
    public void execute(CommandData commandData) {
        Collection<PlayerDisguiseEntity> all = DisguiseManager.getAll();
        if (all.isEmpty()) {
            Logger.sendMessage(commandData.getCommandSender(), "No players are currently disguised.");
            return;
        }
        Logger.sendMessage(commandData.getCommandSender(),
                "Currently disguised players (" + all.size() + "):");
        for (PlayerDisguiseEntity entity : all) {
            String name = entity.getDisguisedPlayer() != null
                    ? entity.getDisguisedPlayer().getName() : "<unknown>";
            Logger.sendMessage(commandData.getCommandSender(),
                    "  - " + name + " as " + entity.getEntityID());
        }
    }
}
```

(Using `disguiselist` as the alias rather than `disguise list` because the AdvancedCommand framework registers each AdvancedCommand under a single alias — sub-subcommands aren't really a thing. If you want `/fmm disguise list`, that would require restructuring `DisguiseCommand` to detect a literal "list" first-arg, which is more invasive. `/fmm disguiselist` matches the existing precedent of `hitboxdebug`, `deleteall`, etc.)

**Step 2: Register in `FreeMinecraftModels.syncInitialization`**

Add immediately after the existing `manager.registerCommand(new UndisguiseCommand());` line:

```java
        manager.registerCommand(new com.magmaguy.freeminecraftmodels.commands.DisguiseListCommand());
```

(Or add the import at the top and just `manager.registerCommand(new DisguiseListCommand());`.)

**Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 4: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/commands/DisguiseListCommand.java src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java
git commit -m "Add /fmm disguiselist admin command"
```

---

### Task 5.4: Fix stale tab-complete (live model-ID lookup)

The current `ListStringCommandArgument(entityIDs, ...)` captures a snapshot of model IDs at command construction. Models loaded after server start (e.g. after `/fmm reload`) don't appear. Fix: write a small subclass that reads `FileModelConverter.getConvertedFileModels().keySet()` on each call.

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/commands/arguments/LiveModelIDsArgument.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/commands/DisguiseCommand.java`

**Step 1: Write the argument**

```java
package com.magmaguy.freeminecraftmodels.commands.arguments;

import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.command.arguments.ICommandArgument;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Tab-completes against the current set of loaded model IDs. Re-reads
 * {@link FileModelConverter#getConvertedFileModels()} on each call so newly
 * loaded models appear without a server restart.
 */
public class LiveModelIDsArgument implements ICommandArgument {
    private final String hint;

    public LiveModelIDsArgument(String hint) {
        this.hint = hint;
    }

    private List<String> ids() {
        return FileModelConverter.getConvertedFileModels().values().stream()
                .map(FileModelConverter::getID)
                .toList();
    }

    @Override
    public String hint() {
        return hint;
    }

    @Override
    public boolean matchesInput(String input) {
        return ids().stream().anyMatch(id -> id.equalsIgnoreCase(input));
    }

    @Override
    public List<String> literals() {
        return ids();
    }

    @Override
    public List<String> getSuggestions(CommandSender sender, String partialInput) {
        String lower = partialInput.toLowerCase();
        return ids().stream()
                .filter(id -> id.toLowerCase().startsWith(lower))
                .toList();
    }

    @Override
    public boolean isLiteral() {
        return false;
    }
}
```

**Step 2: Use it in `DisguiseCommand`**

In the constructor, replace:

```java
        entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(fmc -> entityIDs.add(fmc.getID()));
        addArgument("models", new ListStringCommandArgument(entityIDs, "<modelID>"));
```

with:

```java
        addArgument("models", new LiveModelIDsArgument("<modelID>"));
```

And in `execute`, replace the now-broken validation against the captured `entityIDs` list:

```java
        if (modelID == null || !entityIDs.contains(modelID)) {
```

with:

```java
        if (modelID == null || !FileModelConverter.getConvertedFileModels().containsKey(modelID)) {
```

Remove the `entityIDs` field and its imports (`ArrayList`, `List`) — they're no longer used. Keep `FileModelConverter` import.

Add import: `com.magmaguy.freeminecraftmodels.commands.arguments.LiveModelIDsArgument;`

**Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 4: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/commands/arguments/LiveModelIDsArgument.java src/main/java/com/magmaguy/freeminecraftmodels/commands/DisguiseCommand.java
git commit -m "Tab-complete disguise model IDs from the live registry

Previously DisguiseCommand snapshotted FileModelConverter at command
registration, so models loaded after server start (or after /fmm
reload) never appeared in tab-completion and validation rejected
them. LiveModelIDsArgument reads the current registry on each call."
```

---

### Task 5.5: Phase 5 verification

Run: `mvn -q -DskipTests clean package`
Sync jar to testbed, restart server.

| Action | Expected |
|---|---|
| `/fmm disguise` (no args) in chat | Usage / error message rather than NPE. |
| `/fmm disguise <model>` as player with `.self` perm, no `.others` | Self-disguises. |
| `/fmm disguise <model> <other>` as player with `.self` only | Permission error mentioning `.others`. |
| `/fmm disguise <model> <other>` as player with both perms | Other player is disguised. |
| `/fmm disguise <model> <other>` from console | Other player is disguised. |
| `/fmm undisguise` from console | Refuses (console must specify a target). |
| `/fmm undisguise <other>` from console | Other player undisguised. |
| `/fmm disguiselist` shows currently disguised players | Lists name + model ID. |
| `/help fmm disguise` or `/fmm` help output | Description and usage are both present (not the same string). |
| Tab-complete `/fmm disguise ` with a model loaded **post-startup** | New model ID appears. |

---

## Phase 6 — Public API

Goal: expose disguise functions to third-party plugins via `api/DisguiseAPI.java`, in the same style as `ModeledEntityManager`.

### Task 6.1: Write the API class

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/api/DisguiseAPI.java`

**Step 1: Write the class**

```java
package com.magmaguy.freeminecraftmodels.api;

import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;
import com.magmaguy.freeminecraftmodels.customentity.PlayerDisguiseEntity;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Public entry point for the FMM disguise feature. Third-party plugins
 * (EliteMobs, scripts, etc.) should call this class rather than
 * {@link DisguiseManager} directly so internal refactors don't break them.
 */
public final class DisguiseAPI {

    private DisguiseAPI() {}

    /**
     * Disguises {@code player} as the model with {@code modelID}. If the
     * player is already disguised, the previous disguise is removed first
     * so the new one replaces it cleanly.
     *
     * @return {@code true} on success; {@code false} if the model ID is
     *         not loaded.
     */
    public static boolean disguise(Player player, String modelID) {
        return DisguiseManager.disguise(player, modelID);
    }

    /**
     * Undisguises {@code player} if currently disguised.
     *
     * @return {@code true} if a disguise was removed.
     */
    public static boolean undisguise(Player player) {
        return DisguiseManager.undisguise(player);
    }

    public static boolean isDisguised(Player player) {
        return DisguiseManager.isDisguised(player);
    }

    /**
     * @return the model ID the player is currently disguised as, or
     *         {@code null} if not disguised.
     */
    @Nullable
    public static String getDisguiseModelID(Player player) {
        PlayerDisguiseEntity entity = DisguiseManager.getDisguise(player);
        return entity != null ? entity.getEntityID() : null;
    }

    /**
     * @return a snapshot of all currently disguised players.
     */
    public static Collection<Player> getDisguisedPlayers() {
        return DisguiseManager.getAll().stream()
                .map(PlayerDisguiseEntity::getDisguisedPlayer)
                .collect(Collectors.toUnmodifiableList());
    }
}
```

**Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

**Step 3: Commit**

```
git add src/main/java/com/magmaguy/freeminecraftmodels/api/DisguiseAPI.java
git commit -m "Add public DisguiseAPI for third-party plugins

Thin wrapper over DisguiseManager exposed in the api package so
external code (EliteMobs, scripts) has a stable entry point even if
internal refactors move DisguiseManager around."
```

---

### Task 6.2: Phase 6 verification

Run: `mvn -q -DskipTests clean package`
Expected: BUILD SUCCESS.

No in-game testing for this phase — the API just wraps the manager. Integration is validated implicitly via the commands which already use the same path.

Optional: write a single sanity-check call from another plugin's startup (e.g. a Lua script that calls `DisguiseAPI.isDisguised`) to confirm the class is reachable from outside. Skip if not needed.

---

## Final Wrap-Up

### Task F.1: Bump version + update changelog-style commit

Per the recent commit style in this repo (e.g. `FreeMinecraftModels 2.5.2: ...`), the version bump is the final commit summarizing the feature.

**Files:**
- Modify: `pom.xml` (the `<version>` element)

**Step 1: Decide bump level**

This is a new feature + several bug fixes — minor bump. Current is `2.5.2`; bump to `2.6.0`.

**Step 2: Edit `pom.xml`**

Find `<version>2.5.2</version>` (probably near the top, under `<project>`). Change to `<version>2.6.0</version>`. Check for any other version strings (e.g. `plugin.yml` may have a `version:` field — if it does, edit it too).

**Step 3: Full build**

Run: `mvn -q -DskipTests clean package`
Expected: BUILD SUCCESS, jar `target/FreeMinecraftModels.jar` is produced.

**Step 4: Commit with the repo's standard summary format**

```
git add pom.xml src/main/resources/plugin.yml
git commit -m "FreeMinecraftModels 2.6.0:
  - [New] Player disguise rework — /fmm disguise <model> [player] and /fmm undisguise [player] disguise self (with freeminecraftmodels.disguise.self) or another player (with .others, console-allowed). /fmm disguiselist lists active disguises. Built on a new DisguiseManager that owns all disguise state by player UUID.
  - [New] Disguise model mirrors player behavior — position and head/body rotation sync each tick; reserved animations 'idle' (required), 'walk', 'attack', 'sneak', 'jump' play at priority attack > jump > sneak > walk > idle with graceful fallback when the model doesn't define them.
  - [New] DisguiseAPI in the api package — disguise / undisguise / isDisguised / getDisguiseModelID / getDisguisedPlayers static methods for third-party plugins.
  - [Fix] Disguised player no longer visible to players who join after the disguise started, or who cross worlds — Player.setVisibleByDefault(false) replaces the same-world-only hideEntity loop. Fixes yongzhun's display report.
  - [Fix] Punching the disguise model no longer damages the disguised player — disguise model is fully decorative (no packet interaction entity, no-op click callbacks); attacks pass through to the player's real hitbox.
  - [Fix] Disguise survives until /fmm undisguise or logout — was leaking the model on disconnect because no quit listener cleaned up.
  - [Fix] /fmm disguise no longer leaks a model when called twice in a row — DisguiseManager replaces the existing disguise cleanly.
  - [Fix] Disguise tab-complete now reflects models loaded after server start.
  - [Fix] Permission for disguise/undisguise is no longer the freeminecraftmodels.* wildcard.
  - [Fix] Removed always-dead 'instanceof PlayerDisguiseEntity' check in ModeledEntity.setUnderlyingEntity — disguises no longer register a player as underlyingEntity, so the branch is structurally unnecessary."
```

---

## Architecture Notes (deviations from the design doc)

- **Tick loop:** the design said "single repeating BukkitTask per disguise". Implementation piggybacks on `ModeledEntitiesClock` via a `tick(AbstractPacketBundle)` override on `PlayerDisguiseEntity`, matching how `DynamicEntity` does it. The design doc has been updated inline to reflect this.
- **Self-view of the disguise model:** the design accepted that first-person stays normal (hand visible) — no packet-level hack. This implementation does not attempt to change that.
- **`/fmm disguise list`:** registered as `/fmm disguiselist` (single token), because the MagmaCore `AdvancedCommand` framework wires each command under one alias and doesn't natively support nested subcommands. Other admin commands in the project follow the same flat naming (`hitboxdebug`, `deleteall`).
- **Animation one-shot duration:** the animation system here doesn't expose a "this animation finished" callback. The controller uses a fixed tick countdown (default 20 ticks = 1 second) to suppress lower-priority loops while a one-shot plays. Tunable in `DisguiseAnimationController.DEFAULT_ONE_SHOT_DURATION_TICKS`.

## Out of Scope

- True first-person self-hiding (would need packet manipulation).
- Persistent disguise across server restart (design explicitly chose ephemeral).
- Per-model animation name overrides (design picked fixed names).
- Hitbox-forwards-damage mode (design picked decorative-only).
- Death / damage / world-change auto-undisguise (design picked logout-only).
- `PlayerDisguiseEvent` / `PlayerUndisguiseEvent` API events — YAGNI; add when a consumer asks.
