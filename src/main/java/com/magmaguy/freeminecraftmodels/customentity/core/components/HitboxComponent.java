package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.PacketEntityInteractionManager;
import com.magmaguy.easyminecraftgoals.internal.PacketInteractionContext;
import com.magmaguy.easyminecraftgoals.internal.PacketInteractionEntity;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.OBBHitDetection;
import com.magmaguy.freeminecraftmodels.customentity.core.OrientedBoundingBox;
import com.magmaguy.freeminecraftmodels.packets.PacketEntityDisplayHelper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class HitboxComponent {
    enum InteractionAliasKind {
        NATIVE_BACKING,
        CLIENT_ONLY_CARRIER
    }

    private static final double PLAYER_COLLISION_RANGE_SQUARED = 100.0; // 10 blocks
    private final ModeledEntity modeledEntity;
    private OrientedBoundingBox obbHitbox = null;
    private PacketInteractionEntity packetInteractionEntity = null;
    private final InteractionAliasRegistry interactionAliases = new InteractionAliasRegistry(
            PacketEntityInteractionManager.getInstance());

    public HitboxComponent(ModeledEntity modeledEntity) {
        this.modeledEntity = modeledEntity;
    }

    public OrientedBoundingBox getObbHitbox() {
        if (obbHitbox == null) {
            if (modeledEntity.getSkeletonBlueprint().getHitbox() != null) {
                obbHitbox = new OrientedBoundingBox(
                        modeledEntity.getSkeleton().getCurrentLocation(),
                        //For some reason the width is the Z axis, not the X axis
                        modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ(),
                        modeledEntity.getSkeletonBlueprint().getHitbox().getHeight(),
                        //For some reason the width is the X axis, not the Z axis
                        modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX());
                obbHitbox.setAssociatedEntity(modeledEntity);
                return obbHitbox;
            } else {
                obbHitbox = new OrientedBoundingBox(modeledEntity.getSkeleton().getCurrentLocation(), 1, 2, 1);
                obbHitbox.setAssociatedEntity(modeledEntity);
                return obbHitbox;
            }
        } else return obbHitbox;
    }

    /**
     * Async!
     */
    public void tick(com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle packetBundle) {
        // Models without contact callbacks keep the cheap OBB transform on the
        // asynchronous packet clock. Contact-enabled models are refreshed by
        // tickPrimaryThread(), because collision scans and callbacks touch
        // Bukkit player/world state and must never run here.
        if (modeledEntity.getInteractionComponent()
                .getHitboxContactCallback() == null) {
            getObbHitbox().update(modeledEntity.getLocation());
        }

        // Update the client-side interaction entity position (sends packets) — only when moved.
        updatePacketInteractionEntityPosition(packetBundle);
    }

    /**
     * Refreshes contact-enabled hitboxes and fires contact callbacks on the
     * primary thread. Invoked every two ticks by ModeledEntitiesClock.
     */
    public void tickPrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "Model hitbox contacts must run on the primary thread");
        }
        if (modeledEntity.getInteractionComponent()
                .getHitboxContactCallback() == null) return;
        Location location = modeledEntity.getLocation();
        if (location == null) return;
        getObbHitbox().update(location);
        checkPlayerCollisions();
    }

    /**
     * Checks for collisions with nearby players and fires appropriate events
     */
    public void checkPlayerCollisions() {
        if (modeledEntity.getWorld() == null) return;

        Location entityLocation = modeledEntity.getLocation();
        // Single pass: range prefilter and collision check per player — no
        // intermediate scratch list needed.
        for (Player player : modeledEntity.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(entityLocation) < PLAYER_COLLISION_RANGE_SQUARED
                    && isPlayerColliding(player)) {
                // Fire the appropriate hitbox contact event
                modeledEntity.getInteractionComponent().callHitboxContactEvent(player);
            }
        }
    }

    /**
     * Checks if a player is colliding with this entity's OBB hitbox
     */
    protected boolean isPlayerColliding(Player player) {
        OrientedBoundingBox obb = getObbHitbox();
        org.bukkit.util.BoundingBox aabb = player.getBoundingBox();
        // Cheap reject plus exact SAT against one asynchronously-published pose.
        return obb.intersectsAABBCoherently(aabb);
    }

    public void setCustomHitboxOnUnderlyingEntity() {
        if (modeledEntity.getSkeletonBlueprint().getHitbox() == null) return;
        NMSManager.getAdapter().setCustomHitbox(modeledEntity.getUnderlyingEntity(), modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX() < modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ() ? (float) modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX() : (float) modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ(), (float) modeledEntity.getSkeletonBlueprint().getHitbox().getHeight(), true);
    }

    /**
     * Creates a packet-only Interaction entity for click detection.
     * This entity is invisible to players but receives their clicks.
     * Should be called after the model is spawned.
     * Props get a compact client-targetable interaction surface for entity-use
     * input that Bukkit may not otherwise expose. Dynamic models deliberately
     * stay on exact OBB air/block routing plus their backing-ID alias: a single
     * axis-aligned square large enough to cover a long or rotated OBB would
     * swallow unrelated clicks in its empty corners.
     */
    public void createPacketInteractionEntity() {
        if (packetInteractionEntity != null) return;
        if (modeledEntity.getLocation() == null) return;
        if (modeledEntity.getUnderlyingEntity() != null
                && !(modeledEntity instanceof PropEntity)) return;

        InteractionDimensions dimensions = interactionDimensions(modeledEntity.getLocation());

        try {
            packetInteractionEntity = NMSManager.getAdapter().createPacketInteractionEntity(
                    modeledEntity.getLocation(),
                    dimensions.width(),
                    dimensions.height()
            );
            lastInteractionWidth = dimensions.width();
            lastInteractionHeight = dimensions.height();

            packetInteractionEntity.setContextualRightClickCallback((player, entity, context) -> {
                if (shouldRoutePacketInteraction(context)) {
                    OBBHitDetection.routePacketRightClick(player, modeledEntity);
                }
            });

            packetInteractionEntity.setContextualLeftClickCallback((player, entity, context) -> {
                if (shouldRoutePacketInteraction(context)) {
                    OBBHitDetection.routePacketLeftClick(player, modeledEntity);
                }
            });

            // Show to all current viewers
            for (UUID viewerUUID : modeledEntity.getViewers()) {
                Player viewer = Bukkit.getPlayer(viewerUUID);
                PacketEntityDisplayHelper.displayToPlayer(packetInteractionEntity, viewer);
            }
        } catch (UnsupportedOperationException e) {
            // This version doesn't support packet interaction entities
            // Fall back to OBB-based detection only
            removePacketInteractionEntity();
        } catch (RuntimeException | Error activationFailure) {
            removePacketInteractionEntity();
            throw activationFailure;
        }
    }

    /**
     * Reconciles every client-targetable entity ID owned by this model with the
     * shared packet interaction dispatcher. The Bukkit backing ID covers debug
     * visibility and tracking races; the Bedrock carrier covers fake custom
     * entities whose client-visible ID differs from the backing entity.
     */
    public void refreshPacketInteractionAliases() {
        Integer nativeBackingEntityId = modeledEntity.getUnderlyingEntity() == null
                ? null
                : modeledEntity.getUnderlyingEntity().getEntityId();
        Integer clientOnlyCarrierEntityId = null;
        if (modeledEntity.getBedrockModeledEntity() != null) {
            int carrierId = modeledEntity.getBedrockModeledEntity().getTargetableEntityId();
            if (carrierId >= 0) clientOnlyCarrierEntityId = carrierId;
        }

        interactionAliases.reconcile(
                desiredInteractionAliases(nativeBackingEntityId, clientOnlyCarrierEntityId),
                (player, context) -> {
                    if (context.isAttack()) {
                        OBBHitDetection.routePacketLeftClick(player, modeledEntity);
                    } else {
                        OBBHitDetection.routePacketRightClick(player, modeledEntity);
                    }
                });
    }

    /**
     * Native backing entities always stay on Bukkit's authoritative,
     * cancellable interaction path. A client-only carrier has no native path,
     * so it routes attacks and main-hand use through the model while passing
     * off-hand use.
     */
    static PacketEntityInteractionManager.RoutingDecision aliasRoutingDecision(
            InteractionAliasKind aliasKind,
            PacketInteractionContext context) {
        if (aliasKind == InteractionAliasKind.NATIVE_BACKING) {
            return PacketEntityInteractionManager.RoutingDecision.PASS;
        }
        if (context.isAttack()) {
            return PacketEntityInteractionManager.RoutingDecision.ROUTE;
        }
        return context.hand() == EquipmentSlot.HAND
                ? PacketEntityInteractionManager.RoutingDecision.ROUTE
                : PacketEntityInteractionManager.RoutingDecision.PASS;
    }

    /**
     * Packet-only interaction entities have no native server target to receive a
     * passed packet. Consume their off-hand packets without treating them as the
     * model's primary interaction, while preserving attacks and main-hand use.
     */
    static boolean shouldRoutePacketInteraction(PacketInteractionContext context) {
        return context.isAttack() || context.hand() == EquipmentSlot.HAND;
    }

    static Map<Integer, InteractionAliasKind> desiredInteractionAliases(
            Integer nativeBackingEntityId,
            Integer clientOnlyCarrierEntityId) {
        Map<Integer, InteractionAliasKind> aliases = new LinkedHashMap<>();
        if (clientOnlyCarrierEntityId != null && clientOnlyCarrierEntityId >= 0) {
            aliases.put(clientOnlyCarrierEntityId, InteractionAliasKind.CLIENT_ONLY_CARRIER);
        }
        if (nativeBackingEntityId != null && nativeBackingEntityId >= 0) {
            aliases.put(nativeBackingEntityId, InteractionAliasKind.NATIVE_BACKING);
        }
        return Map.copyOf(aliases);
    }

    static final class InteractionAliasRegistry {
        private final PacketEntityInteractionManager interactionManager;
        private final Map<Integer, RegisteredInteractionAlias> registrations =
                new LinkedHashMap<>();

        InteractionAliasRegistry(PacketEntityInteractionManager interactionManager) {
            this.interactionManager = interactionManager;
        }

        void reconcile(
                Map<Integer, InteractionAliasKind> desiredAliases,
                PacketEntityInteractionManager.ContextualInteractionHandler handler) {
            registrations.entrySet().removeIf(entry -> {
                InteractionAliasKind desiredKind = desiredAliases.get(entry.getKey());
                if (desiredKind == entry.getValue().kind()) return false;
                entry.getValue().registration().close();
                return true;
            });

            desiredAliases.forEach((entityId, aliasKind) ->
                    registrations.computeIfAbsent(entityId, ignored ->
                            new RegisteredInteractionAlias(
                                    aliasKind,
                                    interactionManager.registerContextHandler(
                                            entityId,
                                            context -> aliasRoutingDecision(aliasKind, context),
                                            handler))));
        }

        void clear() {
            registrations.values().forEach(
                    alias -> alias.registration().close());
            registrations.clear();
        }

        private record RegisteredInteractionAlias(
                InteractionAliasKind kind,
                PacketEntityInteractionManager.Registration registration) {
        }
    }

    /** Returns the exact packet Interaction UUID exposed to clients, when supported. */
    public UUID getPacketInteractionEntityUuid() {
        return packetInteractionEntity == null ? null : packetInteractionEntity.getUniqueId();
    }

    /** Returns the current client Interaction envelope width, or zero when unavailable. */
    public float getPacketInteractionEntityWidth() {
        return packetInteractionEntity == null ? 0F : packetInteractionEntity.getWidth();
    }

    /** Returns the current client Interaction envelope height, or zero when unavailable. */
    public float getPacketInteractionEntityHeight() {
        return packetInteractionEntity == null ? 0F : packetInteractionEntity.getHeight();
    }

    /** Keeps the prop packet surface inside the narrower modeled dimension. */
    static float compactPacketInteractionWidth(double widthX, double widthZ) {
        return (float) Math.min(Math.abs(widthX), Math.abs(widthZ));
    }

    /**
     * Updates the packet interaction entity's position.
     * Should be called during tick.
     */
    private Location lastInteractionLocation = null;
    private float lastInteractionWidth = Float.NaN;
    private float lastInteractionHeight = Float.NaN;

    private void updatePacketInteractionEntityPosition(com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle packetBundle) {
        // Snapshot the field: tick() runs async, removePacketInteractionEntity()
        // on the main thread can null this between the check and the teleport.
        PacketInteractionEntity entity = packetInteractionEntity;
        if (entity == null) return;
        Location location = modeledEntity.getLocation();
        if (location == null) return;

        InteractionDimensions dimensions = interactionDimensions(location);
        if (Float.compare(lastInteractionWidth, dimensions.width()) != 0
                || Float.compare(lastInteractionHeight, dimensions.height()) != 0) {
            entity.setSize(dimensions.width(), dimensions.height());
            lastInteractionWidth = dimensions.width();
            lastInteractionHeight = dimensions.height();
        }

        // Change detection: this used to teleport the interaction entity EVERY tick, unbundled,
        // even for a perfectly still model — a hidden per-model packet per tick that bypassed the
        // bundler. Skip when the position/rotation hasn't changed (gated by the same toggle as the
        // bone dirty-check so it can be A/B compared / rolled back).
        if (DefaultConfig.skipUnchangedBoneUpdates
                && lastInteractionLocation != null
                && lastInteractionLocation.getWorld() == location.getWorld()
                && lastInteractionLocation.distanceSquared(location) < 1.0E-8
                && lastInteractionLocation.getYaw() == location.getYaw()
                && lastInteractionLocation.getPitch() == location.getPitch()) {
            return;
        }

        // Bundled teleport (rides the clock bundle instead of a direct unbundled send). Falls back
        // to a direct send when packetBundle is null (non-tick callers).
        entity.teleport(location, packetBundle);
        lastInteractionLocation = location.clone();
    }

    private InteractionDimensions interactionDimensions(Location location) {
        double scale = modeledEntity.getScaleModifier();
        if (!Double.isFinite(scale) || scale <= 0D) scale = 1D;
        if (modeledEntity.getSkeletonBlueprint().getHitbox() == null) {
            return new InteractionDimensions((float) scale, (float) (2D * scale));
        }
        float width = compactPacketInteractionWidth(
                modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX(),
                modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ());
        float height = (float) Math.abs(
                modeledEntity.getSkeletonBlueprint().getHitbox().getHeight());
        return new InteractionDimensions(width * (float) scale, height * (float) scale);
    }

    /**
     * Shows the packet interaction entity to a player.
     * Should be called when a player starts viewing the model.
     */
    public void showPacketInteractionEntityTo(Player player) {
        showPacketInteractionEntityTo(player, null);
    }

    public void showPacketInteractionEntityTo(Player player, com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle packetBundle) {
        if (packetInteractionEntity == null) return;
        PacketEntityDisplayHelper.displayToPlayer(packetInteractionEntity, player, packetBundle);
    }

    /**
     * Hides the packet interaction entity from a player.
     * Should be called when a player stops viewing the model.
     * Accepts UUID to work even when player is offline.
     */
    public void hidePacketInteractionEntityFrom(UUID uuid) {
        if (packetInteractionEntity == null) return;
        packetInteractionEntity.hideFrom(uuid);
    }

    /**
     * Removes the packet interaction entity.
     * Should be called when the model is removed.
     */
    public void removePacketInteractionEntity() {
        interactionAliases.clear();
        if (packetInteractionEntity != null) {
            packetInteractionEntity.remove();
            packetInteractionEntity = null;
        }
        lastInteractionLocation = null;
        lastInteractionWidth = Float.NaN;
        lastInteractionHeight = Float.NaN;
    }

    private record InteractionDimensions(float width, float height) {
    }
}
