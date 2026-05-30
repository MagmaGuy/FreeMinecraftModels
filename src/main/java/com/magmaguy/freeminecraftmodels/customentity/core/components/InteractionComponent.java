package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitByProjectileEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitboxContactEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityLeftClickEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityRightClickEvent;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;

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
    private static final long LEFT_CLICK_COOLDOWN_MS = 100;
    private final Map<UUID, Long> rightClickCooldowns = new HashMap<>();
    private final Map<UUID, Long> leftClickCooldowns = new HashMap<>();

    // Guards a single projectile against hitting an elite more than once. A projectile
    // can reach the projectile-hit chokepoint from more than one detection path within a
    // tick or two (the per-tick OBB sweep and the vanilla-hitbox EntityDamageByEntityEvent
    // redirect); on a model whose vanilla hitbox also catches the arrow that means two
    // damage applications. Wiped 1 second after the hit — there is no reason a projectile
    // that has already struck an entity should still be live and re-colliding beyond that,
    // and the window is long enough that a returning Loyalty trident re-thrown later still
    // registers as a fresh hit.
    private static final java.util.Set<UUID> recentlyHitProjectiles =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final long PROJECTILE_HIT_DEDUP_TICKS = 20L;

    public InteractionComponent(ModeledEntity modeledEntity) {
        this.modeledEntity = modeledEntity;
    }

    public void callLeftClickEvent(Player player) {
        if (modeledEntity.isDying()) return;
        long now = System.currentTimeMillis();
        Long last = leftClickCooldowns.get(player.getUniqueId());
        if (last != null && (now - last) < LEFT_CLICK_COOLDOWN_MS) return;
        leftClickCooldowns.put(player.getUniqueId(), now);
        ModeledEntityLeftClickEvent event = new ModeledEntityLeftClickEvent(player, modeledEntity);
        Bukkit.getPluginManager().callEvent(event);
    }

    public void callRightClickEvent(Player player) {
        if (modeledEntity.isDying()) return;
        long now = System.currentTimeMillis();
        Long last = rightClickCooldowns.get(player.getUniqueId());
        if (last != null && (now - last) < RIGHT_CLICK_COOLDOWN_MS) return;
        rightClickCooldowns.put(player.getUniqueId(), now);
        ModeledEntityRightClickEvent event = new ModeledEntityRightClickEvent(player, modeledEntity);
        Bukkit.getPluginManager().callEvent(event);
    }

    /**
     * Triggers the appropriate hitbox contact event based on entity type
     * This method should be overridden by subclasses to fire their specific event types
     */
    protected void callHitboxContactEvent(Player player) {
        if (modeledEntity.isDying()) return;
        //Pass back to synchronous
        new BukkitRunnable() {
            @Override
            public void run() {
                ModeledEntityHitboxContactEvent event = new ModeledEntityHitboxContactEvent(player, modeledEntity);
                Bukkit.getPluginManager().callEvent(event);
            }
        }.runTask(MetadataHandler.PLUGIN);
    }

    /**
     * Triggers the appropriate hitbox contact event based on entity type
     * This method should be overridden by subclasses to fire their specific event types
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
        // Default behavior for dynamic entities (those with an underlying
        // LivingEntity backing the model): forward the swing to the underlying
        // entity as a vanilla attack so it takes damage normally. Without this
        // default, OBB-edge clicks reach this callback path with no handler and
        // the entity stays un-attackable outside its resized vanilla bbox —
        // i.e. the custom hitbox visually exists but doesn't register hits.
        // The applyDamage flag bypasses OBBHitDetection's own
        // EntityDamageByEntityEvent cancel for this single dispatch.
        if (modeledEntity instanceof PropEntity) return;
        if (!(modeledEntity.getUnderlyingEntity() instanceof LivingEntity underlying)) return;
        OBBHitDetection.applyDamage = true;
        try {
            player.attack(underlying);
        } finally {
            OBBHitDetection.applyDamage = false;
        }
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

        // TEMP DIAGNOSTIC: trace EVERY dispatch (including ones the dedup will drop),
        // with the full call stack so we can see which detection path fired and how
        // many times, the velocity at this instant, the thread, and below the HP delta
        // (the damage that ACTUALLY landed after EliteMobs' formula overrides it).
        boolean willDedup = recentlyHitProjectiles.contains(projectile.getUniqueId());
        if (OBBHitDetection.DEBUG_PROJECTILE_HITS) {
            StringBuilder sb = new StringBuilder();
            sb.append("[FMM-ProjTrace] handleModeledEntityHitByProjectileEvent");
            sb.append("\n  proj=").append(projectile.getType()).append(" uuid=").append(projectile.getUniqueId());
            sb.append("\n  primaryThread=").append(Bukkit.isPrimaryThread());
            sb.append("\n  projVelocity=").append(String.format("%.4f", projectile.getVelocity().length()));
            if (projectile instanceof AbstractArrow a)
                sb.append(" arrow.getDamage()=").append(String.format("%.4f", a.getDamage()));
            sb.append("\n  underlying=").append(underlying.getType())
                    .append(" hpNow=").append(String.format("%.2f", underlying.getHealth()));
            sb.append("\n  willBeDeduped=").append(willDedup)
                    .append(willDedup ? " (DROPPED — already hit within 1s window)" : " (proceeds)");
            sb.append("\n  --- call stack ---");
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 2; i < Math.min(st.length, 26); i++) sb.append("\n    at ").append(st[i]);
            com.magmaguy.magmacore.util.Logger.warn(sb.toString());
        }

        // Dedup: only apply a given projectile once per short window (see field doc).
        // Drops the second of a double-dispatch instead of damaging the entity twice.
        if (!recentlyHitProjectiles.add(projectile.getUniqueId())) return;
        final UUID dedupId = projectile.getUniqueId();
        new BukkitRunnable() {
            @Override
            public void run() {
                recentlyHitProjectiles.remove(dedupId);
            }
        }.runTaskLater(MetadataHandler.PLUGIN, PROJECTILE_HIT_DEDUP_TICKS);
        double damage = 1.0;
        if (projectile instanceof AbstractArrow arrow) {
            damage = Math.max(1.0, Math.ceil(arrow.getDamage() * projectile.getVelocity().length()));
        }
        double hpBefore = underlying.getHealth();
        OBBHitDetection.applyDamage = true;
        OBBHitDetection.bypassProjectileRedirect = true;
        try {
            underlying.damage(damage, projectile);
        } finally {
            OBBHitDetection.applyDamage = false;
            OBBHitDetection.bypassProjectileRedirect = false;
        }
        if (OBBHitDetection.DEBUG_PROJECTILE_HITS) {
            com.magmaguy.magmacore.util.Logger.warn("[FMM-ProjTrace] APPLIED fmmDamageInput="
                    + String.format("%.4f", damage)
                    + " hpBefore=" + String.format("%.2f", hpBefore)
                    + " hpAfter=" + String.format("%.2f", underlying.getHealth())
                    + " actualApplied=" + String.format("%.4f", hpBefore - underlying.getHealth())
                    + " §8(actualApplied is the real damage after EliteMobs' formula override)");
        }
    }

    // Clear all callbacks
    public void clearCallbacks() {
        this.leftClickCallback = null;
        this.rightClickCallback = null;
        this.hitboxContactCallback = null;
    }

    public static class InteractionComponentEvents implements Listener {
        @EventHandler
        public void onLeftClick(ModeledEntityLeftClickEvent event) {
            if (event.isCancelled()) return;
            event.getEntity().getInteractionComponent().handleLeftClickEvent(event.getPlayer());
        }

        @EventHandler
        public void onRightClick(ModeledEntityRightClickEvent event) {
            if (event.isCancelled()) {
                return;
            }
            event.getEntity().getInteractionComponent().handleRightClickEvent(event.getPlayer());
        }

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
