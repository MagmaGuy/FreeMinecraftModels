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

    public BedrockModeledEntity(ModeledEntity modeledEntity,
                                FileModelConverter converter,
                                Location initialLocation) {
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

        try {
            this.fakeCustomEntity = NMSManager.getAdapter().fakeCustomEntityBuilder()
                    .identifier(identifier)
                    .carrierEntityType(EntityType.PIG)
                    .dimensions(width, height)
                    .scale((float) modeledEntity.getScaleModifier())
                    .tracked(false)
                    .propertySchema(propertySchema)
                    .build(bedrockCarrierLocation(initialLocation));
            applyProperties(initialProperties());
        } catch (RuntimeException | Error initializationFailure) {
            // A builder can allocate/register the carrier before a later property
            // initialization step fails. Do not strand that partially initialized
            // entity merely because the owning ModeledEntity never receives this
            // constructor's result.
            if (fakeCustomEntity != null) {
                try {
                    fakeCustomEntity.remove();
                } catch (Throwable cleanupFailure) {
                    initializationFailure.addSuppressed(cleanupFailure);
                } finally {
                    fakeCustomEntity = null;
                }
            }
            throw initializationFailure;
        }
    }

    public boolean isAvailable() {
        return BedrockCustomEntityBridgeRegistry.isAvailable()
                && (fakeCustomEntity != null || bukkitCustomEntity != null);
    }

    public boolean isUsingUnderlyingEntity() {
        return bukkitCustomEntity != null;
    }

    /**
     * Returns the entity ID a Bedrock client can target for this presentation.
     * A Bukkit-backed presentation reuses the real entity ID; a fake custom
     * presentation owns its packet carrier ID.
     */
    public int getTargetableEntityId() {
        if (bukkitCustomEntity != null && bukkitCustomEntity.isValid()) {
            return bukkitCustomEntity.entity().getEntityId();
        }
        return fakeCustomEntity == null ? -1 : fakeCustomEntity.getEntityId();
    }

    public void bindToUnderlyingEntity(Entity entity) {
        if (entity == null || !entity.isValid()) {
            return;
        }
        Set<UUID> previousViewers = new LinkedHashSet<>();
        if (fakeCustomEntity != null) {
            previousViewers.addAll(fakeCustomEntity.getViewers());
        }

        // Build and initialize the replacement before retiring the current
        // backend. Builders register bridge definitions and can throw after
        // allocating a handle; destroying the old carrier first made one
        // transient failure permanently remove Bedrock rendering for this
        // modeled entity.
        BukkitCustomEntity replacement = null;
        try {
            replacement = NMSManager.getAdapter().bukkitCustomEntityBuilder()
                    .identifier(identifier)
                    .carrierEntityType(entity.getType())
                    .dimensions(width, height)
                    .scale((float) modeledEntity.getScaleModifier())
                    .tracked(false)
                    .propertySchema(propertySchema)
                    .build(entity);
            replacement.setProperties(lastProperties);
        } catch (RuntimeException | Error bindingFailure) {
            if (replacement != null) {
                try {
                    replacement.remove();
                } catch (Throwable cleanupFailure) {
                    bindingFailure.addSuppressed(cleanupFailure);
                }
            }
            throw bindingFailure;
        }

        FakeCustomEntity previousFake = fakeCustomEntity;
        BukkitCustomEntity previousBukkit = bukkitCustomEntity;
        fakeCustomEntity = null;
        bukkitCustomEntity = replacement;
        if (previousFake != null) {
            previousFake.remove();
        }
        if (previousBukkit != null) {
            previousBukkit.remove();
        }

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
            if (player != null && player.isOnline()
                    && MetadataHandler.PLUGIN != null
                    && MetadataHandler.PLUGIN.isEnabled()) {
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
        if (fakeCustomEntity != null) {
            // Only compute the carrier location when there is a fake carrier to move
            // (the bukkit-entity backend follows its underlying entity on its own).
            Location location = bedrockCarrierLocation(modeledEntity.getLocation());
            if (location != null && hasCarrierMoved(location)) {
                fakeCustomEntity.teleport(location);
                lastLocation = location.clone();
            }
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
        // animationProperties already builds a fresh map; no extra copy needed
        // (applyProperties defensively copies again anyway).
        return animationProperties(defaultAnimation());
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

    /**
     * Whether the carrier needs a teleport to match {@code location}. A world
     * change (or missing world) always counts as movement — this tick runs off
     * the async model clock, and Location#distanceSquared throws on cross-world
     * comparisons, which would otherwise kill the tick after a cross-world
     * teleport.
     */
    private boolean hasCarrierMoved(Location location) {
        if (lastLocation == null) return true;
        if (lastLocation.getWorld() == null || location.getWorld() == null
                || !lastLocation.getWorld().equals(location.getWorld())) return true;
        return lastLocation.distanceSquared(location) > 0.0001
                || lastLocation.getYaw() != location.getYaw()
                || lastLocation.getPitch() != location.getPitch();
    }

    private Location bedrockCarrierLocation(Location location) {
        if (location == null) {
            return null;
        }
        return location.clone();
    }
}
