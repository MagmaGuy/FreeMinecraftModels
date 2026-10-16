package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.core.TurnInertia;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.HitboxBlueprint;
import com.magmaguy.freeminecraftmodels.utils.ImmutableMapSnapshots;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DynamicEntity extends ModeledEntity {
    //Note: currently only storing these when they spawn, can't think of a reason why that might be bad for now
    private static final ConcurrentHashMap<UUID, DynamicEntity>
            dynamicEntities = new ConcurrentHashMap<>();
    private static final NamespacedKey namespacedKey = new NamespacedKey(MetadataHandler.PLUGIN, "DynamicEntity");
    @Getter
    @Setter
    private boolean damagesOnContact = true;
    /**
     * Whether the model's position and rotation should be synced with the underlying entity.
     * When true (default), the model follows the entity's location and head rotation.
     * When false, the model stays at its current position and can be moved independently via teleport().
     */
    @Getter
    private boolean syncMovement = true;
    /**
     * When true, the underlying entity's invisibility potion is cosmetic
     * (used to hide the vanilla model while showing the custom one) and
     * should NOT hide the custom model bones.
     */
    @Getter
    private boolean cosmeticInvisibility = false;
    /**
     * When true, the entity stays hidden by default but each Java viewer of the model tracks it as an
     * invisible, bare body (see SkeletonWatchers), so the display bones can ride it like a mount's.
     */
    @Getter
    private boolean viewerTrackedBody = false;
    private boolean isEvokerAttacking = false;
    // The model's facing, stepped once per model tick toward the mob's body yaw. Read from any thread.
    private final TurnInertia turnInertia = new TurnInertia();
    private volatile float modelYaw = Float.NaN;
    // Cube root of the hitbox bone's volume at scale 1; 0 when the model has none, -1 until computed.
    private double hitboxSize = -1;

    public DynamicEntity(String entityID, Location targetLocation) {
        super(entityID, targetLocation);
        setModeledEntityHitByProjectileCallback((projectile, entity) -> entity.damage(projectile));
        setHitboxContactCallback((player, modeledEntity) -> {
            if (!damagesOnContact) return;
            if (underlyingEntity instanceof LivingEntity livingEntity && !livingEntity.hasAI() ||
                    underlyingEntity instanceof Mob mob && !mob.isAware()) {
                return;
            }
            modeledEntity.attack(player);
        });
    }

    public static boolean isDynamicEntity(Entity entity) {
        return dynamicEntities.containsKey(entity.getUniqueId());
    }

    public static DynamicEntity getDynamicEntity(Entity entity) {
        return dynamicEntities.get(entity.getUniqueId());
    }

    public static HashMap<UUID, DynamicEntity> getDynamicEntities() {
        return ImmutableMapSnapshots.hashMapCopyOf(dynamicEntities);
    }

    @Nullable
    public static DynamicEntity create(String entityID, LivingEntity livingEntity) {
        FileModelConverter fileModelConverter = FileModelConverter.getModel(entityID);
        if (fileModelConverter == null) return null;
        DynamicEntity dynamicEntity = new DynamicEntity(entityID, livingEntity.getLocation());
        dynamicEntity.spawn(livingEntity);
        boolean bedrockUsesUnderlyingEntity = DefaultConfig.sendCustomModelsToBedrockClientsV2
                && dynamicEntity.getBedrockModeledEntity() != null
                && dynamicEntity.getBedrockModeledEntity().isAvailable()
                && dynamicEntity.getBedrockModeledEntity().isUsingUnderlyingEntity();
        // Paper-only per-viewer suppression. Java clients honour this; Geyser does NOT
        // reliably honour it at the NMS fetch level, so on its own it leaves the mob
        // visible to Bedrock viewers underneath the FMM bone attachables.
        livingEntity.setVisibleByDefault(false);
        // Base-entity INVISIBLE flag (same flag invisibility potions toggle). Geyser
        // does translate this one — Bedrock viewers see the underlying mob as
        // invisible, so only the bone packet armor stands render. Required for FMM
        // disguises on EliteMobs custom bosses to appear correctly on Bedrock.
        livingEntity.setInvisible(!bedrockUsesUnderlyingEntity);
        // Java viewers of the model track this body so the bones can ride it: it is shown to them at admission
        // with its equipment emptied and its invisible flag set on the wire, so a mob Bedrock sees in the flesh
        // still renders nothing for them.
        dynamicEntity.viewerTrackedBody = DefaultConfig.modelViewersTrackHiddenBodies;
        Bukkit.getOnlinePlayers().forEach(player -> {
            if (player.getWorld().equals(dynamicEntity.getLocation().getWorld())) {
                player.hideEntity(MetadataHandler.PLUGIN, livingEntity);
            }
        });

        livingEntity.getPersistentDataContainer().set(namespacedKey, PersistentDataType.BYTE, (byte) 0);
        return dynamicEntity;
    }

    /**
     * Creates a DynamicEntity wrapping the given living entity, using an
     * invisibility potion instead of hiding the entity from clients.
     * This keeps the entity tracked client-side (needed for vehicle steering).
     */
    @Nullable
    public static DynamicEntity createWithInvisibility(String entityID, LivingEntity livingEntity) {
        FileModelConverter fileModelConverter = FileModelConverter.getModel(entityID);
        if (fileModelConverter == null) return null;
        DynamicEntity dynamicEntity = new DynamicEntity(entityID, livingEntity.getLocation());
        dynamicEntity.spawn(livingEntity);

        // Make invisible instead of hiding from clients — the entity stays
        // tracked so passengers can steer it
        livingEntity.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
        dynamicEntity.cosmeticInvisibility = true;

        livingEntity.getPersistentDataContainer().set(namespacedKey, PersistentDataType.BYTE, (byte) 0);
        return dynamicEntity;
    }

    public void spawn(LivingEntity entity) {
        super.spawn(entity);
    }

    @Override
    protected void onSpawnComplete() {
        if (getBedrockModeledEntity() != null) {
            getBedrockModeledEntity().bindToUnderlyingEntity((LivingEntity) getUnderlyingEntity());
        }
        dynamicEntities.put(getUnderlyingEntity().getUniqueId(), this);
        syncSkeletonWithEntity();
    }

    @Override
    protected void onSpawnFailed() {
        if (underlyingEntity != null) {
            dynamicEntities.remove(underlyingEntity.getUniqueId(), this);
        }
    }

    @Override
    public void tick(AbstractPacketBundle abstractPacketBundle) {
        //todo: investigate if this is still necessary since everything now updates anyway, at least for animations
        syncSkeletonWithEntity();
        updateModelYaw();
        super.tick(abstractPacketBundle);
    }

    /**
     * Steps the model's facing toward the mob's body yaw once per model tick, before the bones transform, so the
     * bones, the hitbox, locators and the Bedrock carrier all read one yaw. Ridden models turn instantly: their
     * rider's camera and body follow the mob itself.
     */
    private void updateModelYaw() {
        if (isDying() || !syncMovement || underlyingEntity == null || !underlyingEntity.isValid()) return;
        float target = targetBodyYaw(underlyingEntity.getLocation());
        if (!DefaultConfig.weightedTurning || Float.isNaN(turnInertia.getYaw()) || isRiddenByPlayer())
            turnInertia.reset(target);
        else
            turnInertia.step(target, turnSize(), DefaultConfig.turnSpeedCap, DefaultConfig.turnAcceleration);
        modelYaw = turnInertia.getYaw();
    }

    private boolean isRiddenByPlayer() {
        for (Entity passenger : underlyingEntity.getPassengers()) if (passenger instanceof Player) return true;
        return getMountPointManager() != null && getMountPointManager().hasOccupant();
    }

    /**
     * Size in blocks for turning: the cube root of the hitbox bone's volume times the mob's scale, or of the mob's
     * own box (already scaled) when the model has no hitbox bone.
     */
    private double turnSize() {
        if (hitboxSize < 0) {
            HitboxBlueprint hitbox = getSkeletonBlueprint() == null ? null : getSkeletonBlueprint().getHitbox();
            double volume = hitbox == null ? 0 : hitbox.getWidthX() * hitbox.getWidthZ() * hitbox.getHeight();
            hitboxSize = volume > 0 ? Math.cbrt(volume) : 0;
        }
        if (hitboxSize > 0) return hitboxSize * getScaleModifier();
        double width = underlyingEntity.getWidth();
        return Math.cbrt(width * width * underlyingEntity.getHeight());
    }

    Evoker getEvokerForWatchdog() {
        if (!(underlyingEntity instanceof Evoker evoker)
                || !evoker.isValid()
                || !hasAnimation("attack")) {
            isEvokerAttacking = false;
            return null;
        }
        return evoker;
    }

    void updateEvokerAttackState(boolean attacking) {
        if (attacking && !isEvokerAttacking) {
            playAnimation("attack", false, false);
        }
        isEvokerAttacking = attacking;
    }

    private void syncSkeletonWithEntity() {
        if (isDying()) return;

        if ((underlyingEntity == null || !underlyingEntity.isValid())) {
            remove();
            return;
        }

        // Skip all syncing if movement sync is disabled
        if (!syncMovement) return;

        // Update skeleton position and rotation
        if (underlyingEntity instanceof LivingEntity livingEntity) {
            getSkeleton().setCurrentHeadPitch(livingEntity.getEyeLocation().getPitch());
            getSkeleton().setCurrentHeadYaw(livingEntity.getEyeLocation().getYaw());
        }
    }

    @Override
    protected boolean ridesUnderlyingEntity() {
        // Clients only seat passengers on an entity they track: a mount's cosmetically invisible body always is,
        // a hidden body only for the viewers the watchers show it to.
        return (cosmeticInvisibility || viewerTrackedBody) && syncMovement && !isDying()
                && underlyingEntity != null && underlyingEntity.isValid();
    }

    @Override
    public Location getLocation() {
        if (!syncMovement) return currentLocation != null ? currentLocation.clone() : null;
        if (underlyingEntity != null && underlyingEntity.isValid()) return getBodyLocation();
        if (currentLocation != null) return currentLocation.clone();
        return null;
    }

    /**
     * Sets whether the model's position and rotation should be synced with the underlying entity.
     * When set to false, captures the entity's current position so the model stays where it is.
     *
     * @param syncMovement true to sync with entity, false to freeze at current position
     */
    public void setSyncMovement(boolean syncMovement) {
        // When disabling sync, capture the current position for bone transforms
        if (!syncMovement && this.syncMovement && underlyingEntity != null) {
            Location frozenLocation = getBodyLocation();
            currentLocation = frozenLocation;
            // Set the cached bone transform location so skeleton uses this frozen position
            setCachedBoneTransformLocation(frozenLocation);
        }
        // When re-enabling sync, clear the cached bone transform location
        if (syncMovement && !this.syncMovement) {
            setCachedBoneTransformLocation(null);
        }
        this.syncMovement = syncMovement;
    }

    /**
     * Faces the model toward a world position, aiming head bones independently at that position.
     * Does not rotate or move the underlying entity or require vanilla AI. Callers own the target
     * lifecycle and must pass null when releasing gaze. The supplied location is copied.
     */
    public void setLookTarget(@Nullable Location target) {
        getSkeleton().setLookTarget(target);
    }

    @Override
    public void remove() {
        super.remove();
        // Null guard: a DynamicEntity constructed but never spawned has no underlying entity.
        if (underlyingEntity != null) dynamicEntities.remove(underlyingEntity.getUniqueId());
    }

    public static void shutdown() {
        dynamicEntities.clear();
    }

    /**
     * Gets the location of the model's body, facing the model's yaw: the mob's body yaw reached with turning
     * inertia when {@code weightedTurning} is on (see {@link #updateModelYaw()}), otherwise the body yaw itself.
     */
    public Location getBodyLocation() {
        Location bodyLoc = underlyingEntity.getLocation();
        float yaw = modelYaw;
        bodyLoc.setYaw(Float.isNaN(yaw) ? targetBodyYaw(bodyLoc) : yaw);
        bodyLoc.setPitch(0); // assume no body pitch
        return bodyLoc;
    }

    /**
     * The yaw the mob's body faces: toward the look target when one is set, otherwise clamped so it never lags the
     * head yaw by more than ±45°.
     * <p>
     * Note: this is done because the body rotation is handled by the client after the caching of the body rotation one the living entity stops moving around.
     * This is not a 1:1 recreation of the body rotation, as Minecraft entities have a further behavior that lerps the body rotation to the position they are looking at if they stare at it for about 1 second.
     * That lerping behavior would be unnecessarily demanding, and the is considered to be close enough for 99.99% of cases.
     */
    private float targetBodyYaw(Location bodyLoc) {
        Location gaze = getSkeleton().getLookDirection(bodyLoc);
        if (gaze != null) return gaze.getYaw();

        // current body yaw (what Minecraft thinks the body is doing)
        float bodyYaw = NMSManager.getAdapter().getBodyRotation(underlyingEntity);
        if (!(underlyingEntity instanceof LivingEntity livingEntity)) return bodyLoc.getYaw();
        // actual head yaw
        float headYaw = livingEntity.getEyeLocation().getYaw();
        // compute signed difference in range –180…+180
        float delta = wrapDegrees(headYaw - bodyYaw);

        // clamp delta to –45…+45
        if (delta > 45) delta = 45;
        if (delta < -45) delta = -45;

        // new body yaw is headYaw minus that clamped delta
        return headYaw - delta;
    }

    @Override
    protected Location deathLocation() {
        // Die facing where the model faces, not where the mob does.
        if (underlyingEntity != null) return getBodyLocation();
        return super.deathLocation();
    }

    /**
     * Wrap an angle in degrees to the range –180…+180.
     */
    private float wrapDegrees(float angle) {
        angle %= 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }

    public static class ModeledEntityEvents implements Listener {
        @EventHandler
        public void onEntityDeath(EntityDeathEvent event) {
            DynamicEntity dynamicEntity = DynamicEntity.getDynamicEntity(event.getEntity());
            if (dynamicEntity == null) return;
            dynamicEntity.removeWithDeathAnimation();
        }
    }
}
