package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.core.ModeledEntityInterface;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
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
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.UUID;

public class DynamicEntity extends ModeledEntity implements ModeledEntityInterface {
    //Note: currently only storing these when they spawn, can't think of a reason why that might be bad for now
    @Getter
    private static final HashMap<UUID, DynamicEntity> dynamicEntities = new HashMap<>();
    private static final NamespacedKey namespacedKey = new NamespacedKey(MetadataHandler.PLUGIN, "DynamicEntity");
    @Getter
    private final String name = "default";
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
    private boolean isEvokerAttacking = false;

    public DynamicEntity(String entityID, Location targetLocation) {
        super(entityID, targetLocation);
        setLeftClickCallback((player, entity) -> entity.damage(player));
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

    @Nullable
    public static DynamicEntity create(String entityID, LivingEntity livingEntity) {
        FileModelConverter fileModelConverter = FileModelConverter.getConvertedFileModels().get(entityID);
        if (fileModelConverter == null) return null;
        DynamicEntity dynamicEntity = new DynamicEntity(entityID, livingEntity.getLocation());
        dynamicEntity.spawn(livingEntity);
        livingEntity.setVisibleByDefault(false);
        Bukkit.getOnlinePlayers().forEach(player -> {
            if (player.getLocation().getWorld().equals(dynamicEntity.getLocation().getWorld())) {
                player.hideEntity(MetadataHandler.PLUGIN, livingEntity);
            }
        });

        livingEntity.getPersistentDataContainer().set(namespacedKey, PersistentDataType.BYTE, (byte) 0);
        return dynamicEntity;
    }

    public void spawn(LivingEntity entity) {
        super.spawn(entity);
        dynamicEntities.put(entity.getUniqueId(), this);
        syncSkeletonWithEntity();
    }

    @Override
    public void tick(AbstractPacketBundle abstractPacketBundle) {
        //todo: investigate if this is still necessary since everything now updates anyway, at least for animations
        syncSkeletonWithEntity();
        evokerWatchdog();
        super.tick(abstractPacketBundle);
    }

    private void evokerWatchdog() {
        if (!(underlyingEntity instanceof Evoker evoker)) return;
        if (!hasAnimation("attack")) return;

        Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
            if (!evoker.isValid()) return;

            boolean fangsNearby = evoker.getLocation().getWorld()
                    .getNearbyEntities(evoker.getLocation(), 2, 2, 2)
                    .stream()
                    .anyMatch(entity -> entity instanceof EvokerFangs);

            if (fangsNearby) {
                if (!isEvokerAttacking) {
                    playAnimation("attack", false, false);
                    isEvokerAttacking = true;
                }
                // If already attacking, skip playing the animation
            } else {
                // No fangs nearby, reset for next attack
                isEvokerAttacking = false;
            }
        });
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
    public Location getLocation() {
        if (!syncMovement) return currentLocation != null ? currentLocation.clone() : null;
        if (underlyingEntity != null) return getBodyLocation();
        else return super.getLocation();
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

    @Override
    public void remove() {
        super.remove();
        dynamicEntities.remove(underlyingEntity.getUniqueId());
    }

    public static void shutdown() {
        dynamicEntities.clear();
    }

    /**
     * Gets the location of the model's body, clamped so its yaw never
     * lags the head yaw by more than ±45°.
     * <p>
     * Note: this is done because the body rotation is handled by the client after the caching of the body rotation one the living entity stops moving around.
     * This is not a 1:1 recreation of the body rotation, as Minecraft entities have a further behavior that lerps the body rotation to the position they are looking at if they stare at it for about 1 second.
     * That lerping behavior would be unnecessarily demanding, and the is considered to be close enough for 99.99% of cases.
     */
    public Location getBodyLocation() {
        Location bodyLoc = underlyingEntity.getLocation().clone();

        // current body yaw (what Minecraft thinks the body is doing)
        float bodyYaw = NMSManager.getAdapter().getBodyRotation(underlyingEntity);
        if (underlyingEntity instanceof LivingEntity livingEntity) {
            // actual head yaw
            float headYaw = livingEntity.getEyeLocation().getYaw();
            // compute signed difference in range –180…+180
            float delta = wrapDegrees(headYaw - bodyYaw);

            // clamp delta to –45…+45
            if (delta > 45) delta = 45;
            if (delta < -45) delta = -45;

            // new body yaw is headYaw minus that clamped delta
            float newBodyYaw = headYaw - delta;

            bodyLoc.setYaw(newBodyYaw);
        }

        bodyLoc.setPitch(0); // assume no body pitch
        return bodyLoc;
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