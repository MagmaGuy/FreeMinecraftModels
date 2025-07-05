package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.core.ModeledEntityInterface;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
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

    public DynamicEntity(String entityID, Location targetLocation) {
        super(entityID, targetLocation);
        setLeftClickCallback((player, entity) -> entity.damage(player));
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
    public void tick() {
        //todo: investigate if this is still necessary since everything now updates anyway, at least for animations
        syncSkeletonWithEntity();
        super.tick();
    }

    private void syncSkeletonWithEntity() {
        if (isDying()) return;

        if ((underlyingEntity == null || !underlyingEntity.isValid())) {
            remove();
            return;
        }

        // Update skeleton position and rotation
        if (underlyingEntity instanceof LivingEntity livingEntity) {
            getSkeleton().setCurrentHeadPitch(livingEntity.getEyeLocation().getPitch());
            getSkeleton().setCurrentHeadYaw(livingEntity.getEyeLocation().getYaw());
        }
    }

    @Override
    public Location getLocation() {
        if (underlyingEntity != null) return getBodyLocation();
        else return super.getLocation();
    }

    @Override
    public void remove() {
        super.remove();
        dynamicEntities.remove(underlyingEntity.getUniqueId());
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