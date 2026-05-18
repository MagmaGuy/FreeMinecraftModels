package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.checkerframework.checker.nullness.qual.Nullable;

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
    @Getter
    private final DisguiseAnimationController animationController;
    // Tracks whether THIS disguise applied the invisibility potion effect, so
    // we only strip it on remove() if we were the source. Avoids clobbering an
    // invisibility effect the player had from another source (existing potion,
    // /effect, another plugin) when the disguise ends.
    private boolean appliedInvisibilityEffect = false;

    private PlayerDisguiseEntity(String entityID, Player disguisedPlayer) {
        super(entityID, disguisedPlayer.getLocation());
        this.disguisedPlayer = disguisedPlayer;
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
        this.animationController = new DisguiseAnimationController(this);
    }

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

    /**
     * Factory used by {@link DisguiseManager}. Returns {@code null} if the
     * model ID is not loaded.
     *
     * <p>Package-private — only callable via {@link DisguiseManager}.
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
        // setVisibleByDefault(false) covers all current AND future viewers
        // via a single Paper API call — no need to iterate online players
        // or listen for joins/world-changes. The previous prototype's
        // per-viewer hideEntity loop only covered same-world players online
        // at disguise time, which is why yongzhun reported the disguise
        // "doesn't display properly".
        player.setVisibleByDefault(false);
        // Bedrock fallback: setVisibleByDefault doesn't reliably hide the
        // player from Bedrock viewers (Geyser's EntityCache / sendPlayer
        // paths can re-materialize the player independent of Java's tracker
        // state). An invisibility potion effect propagates cleanly through
        // Geyser's metadata translation and hides the player on both Java
        // and Bedrock. Only apply if the player doesn't already have it from
        // another source — otherwise remove() would strip a non-ours effect.
        if (!player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.INVISIBILITY,
                    PotionEffect.INFINITE_DURATION,
                    0,
                    false,  // ambient
                    false,  // particles
                    false   // icon
            ));
            entity.appliedInvisibilityEffect = true;
        }
        return entity;
    }

    @Override
    public void remove() {
        if (disguisedPlayer.isOnline()) {
            disguisedPlayer.setVisibleByDefault(true);
            if (appliedInvisibilityEffect) {
                disguisedPlayer.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
        }
        super.remove();
    }

    /**
     * Re-applies the invisibility potion effect if this disguise originally
     * applied it. Called from a PlayerRespawnEvent listener — vanilla Minecraft
     * clears all potion effects on death, so a disguised player who died would
     * respawn visible (the disguise itself persists per design, but the
     * invisibility is gone). No-op if this disguise didn't add the effect in
     * the first place (e.g. the player already had it from another source).
     */
    public void reapplyInvisibilityIfOurs() {
        if (!appliedInvisibilityEffect) return;
        if (!disguisedPlayer.isOnline()) return;
        if (disguisedPlayer.hasPotionEffect(PotionEffectType.INVISIBILITY)) return;
        disguisedPlayer.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                PotionEffect.INFINITE_DURATION,
                0,
                false,  // ambient
                false,  // particles
                false   // icon
        ));
    }

    @Override
    public Location getLocation() {
        if (disguisedPlayer.isOnline()) {
            return disguisedPlayer.getLocation();
        }
        return null;
    }

    @Override
    public void tick(AbstractPacketBundle bundle) {
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

        // Player.getEyeLocation() is safe to call async — it returns a copy
        // of the cached state, same pattern as DynamicEntity.syncSkeletonWithEntity.
        Location eye = disguisedPlayer.getEyeLocation();
        getSkeleton().setCurrentHeadPitch(eye.getPitch());
        getSkeleton().setCurrentHeadYaw(eye.getYaw());
        animationController.tick();
        super.tick(bundle);
    }
}
