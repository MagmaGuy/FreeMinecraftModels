package com.magmaguy.freeminecraftmodels.bedrock;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.customentity.BedrockCustomEntityBridgeRegistry;
import com.magmaguy.easyminecraftgoals.customentity.BukkitCustomEntity;
import com.magmaguy.easyminecraftgoals.customentity.CustomEntityPropertySchema;
import com.magmaguy.easyminecraftgoals.customentity.FakeCustomEntity;
import com.magmaguy.easyminecraftgoals.customentity.PackedBooleanPropertySet;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BedrockModeledEntity {
    private final ModeledEntity modeledEntity;
    private final List<String> animations;
    private final String identifier;
    private final CustomEntityPropertySchema propertySchema;
    private final float width;
    private final float height;
    private FakeCustomEntity fakeCustomEntity;
    private BukkitCustomEntity bukkitCustomEntity;
    private Location lastLocation;
    private float lastScale = Float.NaN;
    private String activeAnimation;
    private Map<String, Object> lastProperties = new LinkedHashMap<>();

    public BedrockModeledEntity(ModeledEntity modeledEntity, FileModelConverter converter) {
        this.modeledEntity = modeledEntity;
        this.animations = BedrockEntityBundleExporter.exportedAnimationNames(converter);
        this.identifier = BedrockEntityBundleExporter.identifier(modeledEntity.getEntityID());

        CustomEntityPropertySchema.Builder schema = CustomEntityPropertySchema.builder()
                .addPackedBooleans(BedrockEntityBundleExporter.PROPERTY_NAMESPACE + ":anim", animations.size());
        float computedWidth = 1.0f;
        float computedHeight = 2.0f;
        if (modeledEntity.getSkeletonBlueprint().getHitbox() != null) {
            computedWidth = (float) Math.max(
                    modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX(),
                    modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ());
            computedHeight = (float) modeledEntity.getSkeletonBlueprint().getHitbox().getHeight();
        }
        this.width = computedWidth;
        this.height = computedHeight;
        this.propertySchema = schema.build();

        this.fakeCustomEntity = NMSManager.getAdapter().fakeCustomEntityBuilder()
                .identifier(identifier)
                .carrierEntityType(EntityType.PIG)
                .dimensions(width, height)
                .scale((float) modeledEntity.getScaleModifier())
                .tracked(false)
                .propertySchema(propertySchema)
                .build(bedrockCarrierLocation(modeledEntity.getLocation()));
        applyProperties(initialProperties());
    }

    public boolean isAvailable() {
        return BedrockCustomEntityBridgeRegistry.isAvailable()
                && (fakeCustomEntity != null || bukkitCustomEntity != null);
    }

    public boolean isUsingUnderlyingEntity() {
        return bukkitCustomEntity != null;
    }

    public void bindToUnderlyingEntity(Entity entity) {
        if (entity == null || !entity.isValid()) {
            return;
        }
        Set<UUID> previousViewers = new LinkedHashSet<>();
        if (fakeCustomEntity != null) {
            previousViewers.addAll(fakeCustomEntity.getViewers());
            fakeCustomEntity.remove();
            fakeCustomEntity = null;
        }
        if (bukkitCustomEntity != null) {
            bukkitCustomEntity.remove();
        }
        bukkitCustomEntity = NMSManager.getAdapter().bukkitCustomEntityBuilder()
                .identifier(identifier)
                .carrierEntityType(entity.getType())
                .dimensions(width, height)
                .scale((float) modeledEntity.getScaleModifier())
                .tracked(false)
                .propertySchema(propertySchema)
                .build(entity);
        bukkitCustomEntity.setProperties(lastProperties);

        for (UUID uuid : previousViewers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                displayTo(player);
            }
        }
    }

    public void displayTo(Player player) {
        if (!isAvailable() || player == null) {
            return;
        }
        if (bukkitCustomEntity != null) {
            displayUnderlyingTo(player);
            return;
        }
        fakeCustomEntity.displayTo(player);
    }

    public void hideFrom(UUID uuid) {
        if (fakeCustomEntity != null) {
            fakeCustomEntity.hideFrom(uuid);
        }
        if (bukkitCustomEntity != null) {
            BukkitCustomEntity currentHandle = bukkitCustomEntity;
            currentHandle.forgetViewer(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && MetadataHandler.PLUGIN != null) {
                Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN,
                        () -> player.hideEntity(MetadataHandler.PLUGIN, currentHandle.entity()));
            }
        }
    }

    public void remove() {
        if (fakeCustomEntity != null) {
            fakeCustomEntity.remove();
            fakeCustomEntity = null;
        }
        if (bukkitCustomEntity != null) {
            bukkitCustomEntity.remove();
            bukkitCustomEntity = null;
        }
    }

    public void tick() {
        if (!isAvailable()) {
            return;
        }
        Location location = bedrockCarrierLocation(modeledEntity.getLocation());
        if (fakeCustomEntity != null && location != null
                && (lastLocation == null || lastLocation.distanceSquared(location) > 0.0001
                || lastLocation.getYaw() != location.getYaw() || lastLocation.getPitch() != location.getPitch())) {
            fakeCustomEntity.teleport(location);
            lastLocation = location.clone();
        }
        float scale = (float) modeledEntity.getScaleModifier();
        if (Float.compare(scale, lastScale) != 0) {
            if (fakeCustomEntity != null) fakeCustomEntity.setScale(scale);
            if (bukkitCustomEntity != null) bukkitCustomEntity.setScale(scale);
            lastScale = scale;
        }
    }

    public void playAnimation(String animationName) {
        if (animationName == null || animations.isEmpty() || animationName.equals(activeAnimation)) {
            return;
        }
        activeAnimation = animationName;
        applyProperties(animationProperties(animationName));
    }

    public void stopAnimations() {
        activeAnimation = null;
        applyProperties(animationProperties(null));
    }

    private void displayUnderlyingTo(Player player) {
        if (MetadataHandler.PLUGIN == null || !MetadataHandler.PLUGIN.isEnabled()) {
            bukkitCustomEntity.prepareSpawnFor(player);
            return;
        }
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
            Player current = Bukkit.getPlayer(uuid);
            if (current == null || !current.isOnline() || bukkitCustomEntity == null || !bukkitCustomEntity.isValid()) {
                return;
            }
            bukkitCustomEntity.prepareSpawnFor(current);
            Bukkit.getScheduler().runTaskLater(MetadataHandler.PLUGIN, () -> {
                Player delayed = Bukkit.getPlayer(uuid);
                if (delayed == null || !delayed.isOnline() || bukkitCustomEntity == null || !bukkitCustomEntity.isValid()) {
                    return;
                }
                delayed.showEntity(MetadataHandler.PLUGIN, bukkitCustomEntity.entity());
            }, 1L);
            scheduleUnderlyingSync(uuid, 4L);
            scheduleUnderlyingSync(uuid, 10L);
        });
    }

    private void scheduleUnderlyingSync(UUID uuid, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(MetadataHandler.PLUGIN, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && bukkitCustomEntity != null && bukkitCustomEntity.isValid()) {
                bukkitCustomEntity.syncTo(player);
            }
        }, delayTicks);
    }

    private void applyProperties(Map<String, Object> properties) {
        lastProperties = new LinkedHashMap<>(properties);
        if (fakeCustomEntity != null) fakeCustomEntity.setProperties(lastProperties);
        if (bukkitCustomEntity != null) bukkitCustomEntity.setProperties(lastProperties);
    }

    private Map<String, Object> initialProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.putAll(animationProperties(defaultAnimation()));
        return properties;
    }

    private Map<String, Object> animationProperties(String animationName) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<Boolean> enabled = new ArrayList<>();
        for (String animation : animations) {
            enabled.add(animationName != null && animation.equalsIgnoreCase(animationName));
        }
        List<Integer> packedAnimations = PackedBooleanPropertySet.packBooleans(enabled);
        for (int i = 0; i < packedAnimations.size(); i++) {
            properties.put(BedrockEntityBundleExporter.animationPropertyName(i), packedAnimations.get(i));
        }
        return properties;
    }

    private String defaultAnimation() {
        if (animations.contains("spawn")) {
            return "spawn";
        }
        if (animations.contains("idle")) {
            return "idle";
        }
        return null;
    }

    private Location bedrockCarrierLocation(Location location) {
        if (location == null) {
            return null;
        }
        return location.clone();
    }
}
