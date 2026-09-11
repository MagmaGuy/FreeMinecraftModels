package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitByProjectileEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitboxContactEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityLeftClickEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityRightClickEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityInteractEvent;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntityHitboxContactCallback;
import com.magmaguy.freeminecraftmodels.customentity.core.MountPointManager;
import com.magmaguy.freeminecraftmodels.customentity.core.OBBHitDetection;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntityLeftClickCallback;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntityRightClickCallback;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * This class handles left click, right click, and hitbox contact events for the entity.
 * Certain types of entities may have default behaviors for these events, which can be overriden by setting custom callbacks.
 */
public class InteractionComponent {
    private final ModeledEntity modeledEntity;
    // Callback fields
    @Setter
    private ModeledEntityLeftClickCallback leftClickCallback;
    @Setter
    @Getter
    private ModeledEntityRightClickCallback rightClickCallback;
    @Setter
    @Getter
    private ModeledEntityHitboxContactCallback hitboxContactCallback;
    @Setter
    @Getter
    private ModeledEntityHitByProjectileCallback projectileHitCallback;

    // Cooldown to prevent double-firing from both packet interaction entity and OBB raytrace
    private static final long RIGHT_CLICK_COOLDOWN_MS = 100;
    private final Map<UUID, Long> rightClickCooldowns = new HashMap<>();
    // Marks the synchronous public-event dispatch for a native backing-entity
    // hit. The normal default callback must not synthesize a second attack in
    // that context, while explicit custom callbacks still own the interaction.
    private final Set<UUID> nativeBackingLeftClickDispatches = new HashSet<>();

    public InteractionComponent(ModeledEntity modeledEntity) {
        this.modeledEntity = modeledEntity;
    }

    public void callLeftClickEvent(Player player) {
        if (modeledEntity.isDying()) return;
        ModeledEntityLeftClickEvent event = new ModeledEntityLeftClickEvent(player, modeledEntity);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) handleLeftClickEvent(player);
    }

    /**
     * Fires the same public left-click event for a hit on a model's vanilla
     * backing entity without recursively attacking that entity again. The native
     * Bukkit damage event may continue only when no listener cancelled the
     * modeled event and no explicit callback owns the click.
     *
     * @return true when the caller should keep the native damage event, false
     * when the modeled interaction consumed or cancelled it
     */
    public boolean callNativeBackingLeftClickEvent(Player player) {
        if (modeledEntity.isDying()) return false;
        UUID playerId = player.getUniqueId();
        if (!nativeBackingLeftClickDispatches.add(playerId)) return false;

        ModeledEntityLeftClickEvent event = new ModeledEntityLeftClickEvent(player, modeledEntity);
        try {
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) handleLeftClickEvent(player);
        } finally {
            nativeBackingLeftClickDispatches.remove(playerId);
        }
        return !event.isCancelled() && leftClickCallback == null;
    }

    public void callRightClickEvent(Player player) {
        if (modeledEntity.isDying() || modeledEntity.isRemoved()) return;
        long now = System.currentTimeMillis();
        Long last = rightClickCooldowns.get(player.getUniqueId());
        if (last != null && (now - last) < RIGHT_CLICK_COOLDOWN_MS) return;
        pruneExpired(rightClickCooldowns, now, RIGHT_CLICK_COOLDOWN_MS);
        rightClickCooldowns.put(player.getUniqueId(), now);
        // A block/air/packet click has no Bukkit entity permission decision of its own.
        // Normalize all model inputs through the backing entity before any model behavior.
        // The cooldown is claimed first so reentrant listeners cannot dispatch this twice.
        if (modeledEntity.getUnderlyingEntity() != null) {
            if (!modeledEntity.getUnderlyingEntity().isValid()) return;
            ModeledEntityInteractEvent permission = new ModeledEntityInteractEvent(player, modeledEntity);
            Bukkit.getPluginManager().callEvent(permission);
            if (permission.isCancelled()) return;
        }
        if (modeledEntity.isRemoved() || modeledEntity.isDying() || !player.isOnline()
                || modeledEntity.getWorld() != player.getWorld()) return;
        ModeledEntityRightClickEvent event = new ModeledEntityRightClickEvent(player, modeledEntity);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) handleRightClickEvent(player);
    }

    /**
     * Drops entries whose cooldown window has already elapsed — they can no
     * longer suppress a click, so removing them is behavior-neutral and keeps
     * these per-instance maps from accumulating one entry per player that ever
     * clicked this entity.
     */
    private static void pruneExpired(Map<UUID, Long> cooldowns, long now, long windowMs) {
        cooldowns.values().removeIf(timestamp -> (now - timestamp) >= windowMs);
    }

    /**
     * Fires the {@link ModeledEntityHitboxContactEvent} for this entity on the
     * primary thread. When already on the primary thread (the hitbox contact
     * scan is), dispatches synchronously; off-thread callers bounce through the
     * scheduler.
     */
    protected void callHitboxContactEvent(Player player) {
        if (modeledEntity.isDying()) return;
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(new ModeledEntityHitboxContactEvent(player, modeledEntity));
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    Bukkit.getPluginManager().callEvent(new ModeledEntityHitboxContactEvent(player, modeledEntity));
                }
            }.runTask(MetadataHandler.PLUGIN);
        }
    }

    /**
     * Fires the {@link ModeledEntityHitByProjectileEvent} for this entity on the
     * primary thread.
     */
    public void callModeledEntityHitByProjectileEvent(Projectile projectile) {
        if (modeledEntity.isDying()) return;
        // Fire on the main thread. When the caller is ALREADY on the main thread
        // (the OBB projectile-detection tick and the vanilla-hit redirect both are),
        // fire SYNCHRONOUSLY instead of deferring a tick. The deferred path read the
        // projectile's velocity a tick later — after the arrow had punched through the
        // model and decelerated — so the hit registered at a near-zero velocity and
        // dealt minimum damage even on a clean single hit (the downstream damage
        // formula scales by impact velocity). Firing now, at detection time, sees the
        // real velocity. Off-thread callers still bounce back through the scheduler.
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(new ModeledEntityHitByProjectileEvent(projectile, modeledEntity));
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    Bukkit.getPluginManager().callEvent(new ModeledEntityHitByProjectileEvent(projectile, modeledEntity));
                }
            }.runTask(MetadataHandler.PLUGIN);
        }
    }

    public void handleLeftClickEvent(Player player) {
        if (leftClickCallback != null) {
            leftClickCallback.onLeftClick(player, modeledEntity);
            return;
        }
        // OBBHitDetection has already validated and retained the original
        // backing-entity damage event for this dispatch. Re-entering through
        // player.attack() here would either duplicate the hit or be rejected by
        // the server as a second attack in the same tick.
        if (nativeBackingLeftClickDispatches.contains(player.getUniqueId())) return;
        // Default behavior for dynamic entities (those with an underlying
        // LivingEntity backing the model): forward the swing to the underlying
        // entity as a vanilla attack so it takes damage normally. Without this
        // default, OBB-edge clicks reach this callback path with no handler and
        // the entity stays un-attackable outside its resized vanilla bbox —
        // i.e. the custom hitbox visually exists but doesn't register hits.
        // The applyDamage flag bypasses OBBHitDetection's own
        // EntityDamageByEntityEvent cancel for this single dispatch.
        if (modeledEntity instanceof PropEntity) return;
        if (!(modeledEntity.getUnderlyingEntity() instanceof LivingEntity)) return;
        modeledEntity.damage(player);
    }

    public void handleRightClickEvent(Player player) {
        if (rightClickCallback != null) {
            rightClickCallback.onRightClick(player, modeledEntity);
            return;
        }
        // Default behavior: try mounting if the entity has mount points
        MountPointManager mountPointManager = modeledEntity.getMountPointManager();
        if (mountPointManager != null && mountPointManager.hasMountPoints()) {
            mountPointManager.tryMount(player);
        }
    }

    public void handleHitboxContactEvent(Player player) {
        if (hitboxContactCallback == null) return;
        hitboxContactCallback.onHitboxContact(player, modeledEntity);
    }

    public void handleModeledEntityHitByProjectileEvent(Projectile projectile) {
        if (projectileHitCallback != null) {
            projectileHitCallback.onHitByProjectile(projectile, modeledEntity);
            return;
        }
        // Default behavior for dynamic entities (those with an underlying
        // LivingEntity backing the model): forward the projectile hit to the
        // underlying entity as a vanilla projectile-damage event. Without this
        // default, OBBHitDetection's tick loop detects the arrow inside the
        // visible OBB, fires this event, then removes the projectile — leaving
        // the model visually hit but the underlying entity untouched, so combat
        // plugins (EliteMobs, etc.) never see the hit. Mirrors the left-click
        // default in handleLeftClickEvent above.
        //
        // The bypass flags suppress OBBHitDetection's own cancellation listeners
        // so the EntityDamageByEntityEvent we trigger here reaches downstream
        // handlers (cause=PROJECTILE, damager=projectile) instead of being
        // cancelled or re-routed back into this same callback.
        if (modeledEntity instanceof PropEntity) return;
        if (!(modeledEntity.getUnderlyingEntity() instanceof LivingEntity underlying)) return;

        double damage = 1.0;
        if (projectile instanceof AbstractArrow arrow) {
            damage = Math.max(1.0, Math.ceil(arrow.getDamage() * projectile.getVelocity().length()));
        }
        OBBHitDetection.applyDamage = true;
        OBBHitDetection.bypassProjectileRedirect = true;
        try {
            underlying.damage(damage, projectile);
        } finally {
            OBBHitDetection.applyDamage = false;
            OBBHitDetection.bypassProjectileRedirect = false;
        }
    }

    // Clear all callbacks
    public void clearCallbacks() {
        this.leftClickCallback = null;
        this.rightClickCallback = null;
        this.hitboxContactCallback = null;
        this.projectileHitCallback = null;
    }

    public static class InteractionComponentEvents implements Listener {
        @EventHandler
        public void onHitboxContact(ModeledEntityHitboxContactEvent event) {
            if (event.isCancelled()) return;
            event.getEntity().getInteractionComponent().handleHitboxContactEvent(event.getPlayer());
        }

        @EventHandler
        public void onProjectileHit(ModeledEntityHitByProjectileEvent event) {
            if (event.isCancelled()) return;
            event.getEntity().getInteractionComponent().handleModeledEntityHitByProjectileEvent(event.getProjectile());
        }
    }
}
