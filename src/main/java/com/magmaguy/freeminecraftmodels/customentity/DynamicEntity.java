package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.core.ModeledEntityInterface;
import com.magmaguy.freeminecraftmodels.customentity.core.OBBHitDetection;
import com.magmaguy.freeminecraftmodels.customentity.core.RegisterModelEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.util.AttributeManager;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DynamicEntity extends ModeledEntity implements ModeledEntityInterface {
    @Getter
    private static final List<DynamicEntity> dynamicEntities = new ArrayList<>();
    private static final NamespacedKey namespacedKey = new NamespacedKey(MetadataHandler.PLUGIN, "DynamicEntity");
    @Getter
    private final String name = "default";
    boolean oneTimeDamageWarning = false;
    int counter = 0;
    // Contact damage detection is integrated into the entity's internal clock
    // Contact damage properties
    @Getter
    @Setter
    private boolean damagesOnContact = false;
    @Getter
    private int customDamage = 1;

    public DynamicEntity(String entityID, Location targetLocation) {
        super(entityID, targetLocation);
        dynamicEntities.add(this);
        super.getSkeleton().setDynamicEntity(this);
    }

    public static boolean isDynamicEntity(LivingEntity livingEntity) {
        if (livingEntity == null) return false;
        return livingEntity.getPersistentDataContainer().has(namespacedKey, PersistentDataType.BYTE);
    }

    public static DynamicEntity getDynamicEntity(LivingEntity livingEntity) {
        for (DynamicEntity dynamicEntity : dynamicEntities)
            if (dynamicEntity.getLivingEntity().equals(livingEntity))
                return dynamicEntity;
        return null;
    }

    @Override
    protected void shutdownRemove() {
        remove();
    }

    //safer since it can return null
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

    /**
     * This value only gets used if damagesOnContact is set to true and the entity doesn ot have an attack damage attribute
     *
     * @param customDamage
     */
    public void setCustomDamage(int customDamage) {
        this.customDamage = customDamage;
    }

    public void spawn(LivingEntity entity) {
        super.livingEntity = entity;
        RegisterModelEntity.registerModelEntity(entity, getSkeletonBlueprint().getModelName());
        super.spawn();
        syncSkeletonWithEntity();
        setHitbox();
    }

    @Override
    public void tick() {
        syncSkeletonWithEntity();
        super.tick();
    }

    private void syncSkeletonWithEntity() {
        if (isDying()) return;

        if ((livingEntity == null || !livingEntity.isValid())) {
            remove();
            return;
        }

        // Update skeleton position and rotation
        getSkeleton().setCurrentLocation(getBodyLocation());
        getSkeleton().setCurrentHeadPitch(livingEntity.getEyeLocation().getPitch());
        getSkeleton().setCurrentHeadYaw(livingEntity.getEyeLocation().getYaw());

        if (damagesOnContact && counter % 2 == 0) checkPlayerCollisions();
        counter++;
    }

    @Override
    public void remove() {
        super.remove();
        if (livingEntity != null)
            livingEntity.remove();
        dynamicEntities.remove(this);
    }

    private void setHitbox() {
        if (getSkeletonBlueprint().getHitbox() == null) return;
        NMSManager.getAdapter().setCustomHitbox(super.livingEntity, getSkeletonBlueprint().getHitbox().getWidthX() < getSkeletonBlueprint().getHitbox().getWidthZ() ? (float) getSkeletonBlueprint().getHitbox().getWidthX() : (float) getSkeletonBlueprint().getHitbox().getWidthZ(), (float) getSkeletonBlueprint().getHitbox().getHeight(), true);
    }

    @Override
    public void damage(Player player, double damage) {
        if (livingEntity == null) return;
        OBBHitDetection.applyDamage = true;
        livingEntity.damage(damage, player);
        OBBHitDetection.applyDamage = false;
        getSkeleton().tint();
        if (!livingEntity.isValid()) removeWithDeathAnimation();
    }

    @Override
    public void damage(Player player) {
        if (livingEntity == null) return;
        OBBHitDetection.applyDamage = true;
        player.attack(livingEntity);
        OBBHitDetection.applyDamage = false;
        getSkeleton().tint();
        if (!livingEntity.isValid()) removeWithDeathAnimation();
    }

    @Override
    public World getWorld() {
        if (livingEntity == null || !livingEntity.isValid()) return null;
        return livingEntity.getWorld();
    }

    @Override
    public Location getLocation() {
        if (livingEntity == null) return null;
        return livingEntity.getLocation();
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
        Location bodyLoc = livingEntity.getLocation().clone();

        // current body yaw (what Minecraft thinks the body is doing)
        float bodyYaw = NMSManager.getAdapter().getBodyRotation(livingEntity);
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

    @Override
    protected void updateHitbox() {
        getObbHitbox().update(getBodyLocation());
    }

    /**
     * Checks for collisions with nearby players and applies damage
     */
    private void checkPlayerCollisions() {
        if (livingEntity == null || !livingEntity.isValid()) {
            return;
        }

        // Check for nearby players (within 5 blocks)
        List<Player> nearbyPlayers = livingEntity.getWorld().getPlayers().stream()
                .filter(player -> player.getLocation().distanceSquared(livingEntity.getLocation()) < Math.pow(10, 2))
                .collect(Collectors.toList());

        // For each nearby player, check collision and apply damage if colliding
        for (Player player : nearbyPlayers) {
            if (isPlayerColliding(player)) {
                if (livingEntity.getAttribute(AttributeManager.getAttribute("generic_attack_damage")) != null)
                    livingEntity.attack(player);
                else {
                    //This is not ideal, the underlying entity should have a damage attribute
                    player.damage(customDamage, livingEntity);
                    if (!oneTimeDamageWarning) {
                        Logger.info("Damaged player " + player.getName() + " for " + customDamage + " damage using custom damage value!");
                        oneTimeDamageWarning = true;
                    }
                }
            }
        }
    }

    /**
     * Checks if a player is colliding with this entity
     */
    private boolean isPlayerColliding(Player player) {
        // Check for collision
        return getObbHitbox().isAABBCollidingWithOBB(player.getBoundingBox(), getObbHitbox());
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