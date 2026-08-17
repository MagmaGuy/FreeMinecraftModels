package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.props.PropBlocks;
import com.magmaguy.freeminecraftmodels.customentity.core.components.PropBlockComponent;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.HitboxBlueprint;
import com.magmaguy.freeminecraftmodels.listeners.ArmorStandListener;
import com.magmaguy.freeminecraftmodels.scripting.LuaPropTable;
import com.magmaguy.freeminecraftmodels.scripting.PropScriptManager;
import com.magmaguy.freeminecraftmodels.utils.ImmutableMapSnapshots;
import com.magmaguy.magmacore.util.ChunkLocationChecker;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class PropEntity extends StaticEntity {
    public static final NamespacedKey propNamespacedKey = new NamespacedKey(MetadataHandler.PLUGIN, "prop");
    // ConcurrentHashMap like the sibling registries (DynamicEntity, ModeledEntity):
    // remove() can run off the async model clock while the main thread iterates.
    private static final Map<UUID, PropEntity> propEntities = new java.util.concurrent.ConcurrentHashMap<>();
    // Props ignore click callbacks for this long after spawn so the placement click can't trigger them.
    private static final long POST_SPAWN_INTERACTION_GRACE_MS = 500L;
    @Getter
    private final PropBlockComponent propBlockComponent = new PropBlockComponent(this);
    @Getter
    private boolean persistent = true;
    @Getter
    private boolean voxelize = false;
    @Getter
    private boolean solidify = false;
    private String chunkHash;
    private final long spawnTimeMillis = System.currentTimeMillis();

    public void setVoxelizeConfig(boolean voxelize, boolean solidify) {
        this.voxelize = voxelize;
        this.solidify = solidify && voxelize;
    }

    public void applySolidify() {
        if (!solidify || !voxelize) return;

        FileModelConverter converter = FileModelConverter.getModel(getEntityID());
        if (converter == null) return;

        HitboxBlueprint hitbox = converter.getSkeletonBlueprint().getHitbox();
        int modelX, modelY, modelZ;
        if (hitbox != null) {
            modelX = Math.max(1, (int) Math.ceil(hitbox.getWidthX() - 0.4));
            modelY = Math.max(1, (int) Math.ceil(hitbox.getHeight() - 0.4));
            modelZ = Math.max(1, (int) Math.ceil(hitbox.getWidthZ() - 0.4));
        } else {
            modelX = modelY = modelZ = 1;
        }

        // Rotate footprint to match yaw — same logic as ModelItemListener
        float yaw = getSpawnLocation().getYaw() % 360;
        if (yaw < 0) yaw += 360;
        int rotation = Math.round(yaw / 90f) % 4;
        int footX, footZ;
        if (rotation == 1 || rotation == 3) {
            footX = modelZ;
            footZ = modelX;
        } else {
            footX = modelX;
            footZ = modelZ;
        }

        // Generate barriers in world-space using the rotated footprint
        List<PropBlocks> barriers = new ArrayList<>();
        int offsetX = -(footX / 2);
        int offsetZ = -(footZ / 2);

        for (int x = 0; x < footX; x++) {
            for (int y = 0; y < modelY; y++) {
                for (int z = 0; z < footZ; z++) {
                    barriers.add(new PropBlocks(
                            new org.bukkit.util.Vector(offsetX + x, y, offsetZ + z),
                            Material.BARRIER
                    ));
                }
            }
        }

        setPropBlocks(barriers);
    }

    public PropEntity(String entityID, Location spawnLocation) {
        super(requireValidModelId(entityID), spawnLocation);
        initializePropEntity();
    }

    public PropEntity(String entityID, ArmorStand armorStand) {
        super(requireValidModelId(entityID), armorStand.getLocation());
        initializePropEntity();

        // Use the normal activation path so this restored prop is not exposed
        // to the asynchronous model clock until its entity and displays are
        // completely initialized.
        super.spawn(armorStand);
        PropScriptManager.onPropSpawn(this);
    }

    @Override
    protected void onSpawnComplete() {
        if (underlyingEntity == null) return;
        chunkHash = ChunkLocationChecker.chunkToString(underlyingEntity.getLocation().getChunk());
        propEntities.put(underlyingEntity.getUniqueId(), this);
    }

    public static void onStartup() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk loadedChunk : world.getLoadedChunks()) {
                removeDuplicatePropsInChunk(loadedChunk);
                for (Entity entity : loadedChunk.getEntities()) {
                    if (entity instanceof ArmorStand armorStand) {
                        String propEntityID = getPropEntityID(armorStand);
                        if (propEntityID == null) continue;
                        respawnPropEntityFromArmorStand(propEntityID, armorStand);
                    }
                }
            }
        }
    }

    public static void shutdown() {
        propEntities.clear();
    }

    public static PropEntity spawnPropEntity(String entityID, Location spawnLocation) {
        if (!isValidModelId(entityID)) {
            Logger.warn("[FMM Props] Refused to spawn prop with unknown model ID '" + entityID + "'.");
            return null;
        }
        if (spawnLocation != null && spawnLocation.getWorld() != null) {
            removeDuplicatePropsInChunk(spawnLocation.getChunk());
        }
        if (hasLoadedPropOnSameBlock(entityID, spawnLocation)) {
            Logger.warn("[FMM Props] Prevented duplicate prop spawn for model '" + entityID + "' at "
                    + formatBlockLocation(spawnLocation) + ".");
            return null;
        }
        PropEntity propEntity = new PropEntity(entityID, spawnLocation);
        propEntity.spawn();
        return propEntity;
    }

    public static PropEntity respawnPropEntityFromArmorStand(String entityID, ArmorStand armorStand) {
        return respawnPropEntityFromArmorStand(entityID, armorStand, null);
    }

    /**
     * @param knownChunkEntities entities the caller already holds for this chunk, or
     *                           {@code null} to look them up on demand.
     */
    static PropEntity respawnPropEntityFromArmorStand(String entityID, ArmorStand armorStand, Collection<? extends Entity> knownChunkEntities) {
        FileModelConverter fileModelConverter = FileModelConverter.getModel(entityID);
        if (fileModelConverter == null || fileModelConverter.getSkeletonBlueprint() == null) {
            //Intentional design: skip silently. Placed props can reference models from packs that are
            //currently uninstalled; that is a normal state, and this runs per armor stand per chunk
            //load, so logging here would spam the console (the prop restores again once the pack is back).
            return null;
        }
        if (removeIfDuplicateProp(entityID, armorStand, knownChunkEntities)) return null;
        if (propEntities.containsKey(armorStand.getUniqueId())) {
            return propEntities.get(armorStand.getUniqueId());
        }
        PropEntity propEntity = new PropEntity(entityID, armorStand);
        return propEntity;
    }

    private static String requireValidModelId(String entityID) {
        if (!isValidModelId(entityID)) {
            throw new IllegalArgumentException("Unknown or invalid FreeMinecraftModels prop model ID: " + entityID);
        }
        return entityID;
    }

    static boolean isValidModelId(String entityID) {
        if (entityID == null || entityID.isBlank()) return false;
        FileModelConverter converter = FileModelConverter.getModel(entityID);
        return converter != null && converter.getSkeletonBlueprint() != null;
    }

    public static HashMap<UUID, PropEntity> getPropEntities() {
        return ImmutableMapSnapshots.hashMapCopyOf(propEntities);
    }

    public static boolean isPropEntity(ArmorStand armorStand) {
        return armorStand.getPersistentDataContainer().has(propNamespacedKey, PersistentDataType.STRING);
    }

    public static String getPropEntityID(ArmorStand armorStand) {
        return armorStand.getPersistentDataContainer().get(propNamespacedKey, PersistentDataType.STRING);
    }

    public static boolean hasLoadedPropOnSameBlock(String entityID, Location location) {
        return findLoadedPropOnSameBlock(entityID, location, null, null) != null;
    }

    /**
     * Convenience overload for callers that are not inside a chunk-load callback
     * (startup sweep, tests). Do not call this from a chunk-load listener; see
     * {@link PropEntityEvents#onEntitiesLoadEvent(EntitiesLoadEvent)}.
     */
    static int removeDuplicatePropsInChunk(Chunk chunk) {
        return removeDuplicateProps(Arrays.asList(chunk.getEntities()));
    }

    static int removeDuplicateProps(Collection<? extends Entity> entities) {
        Map<PropBlockKey, List<ArmorStand>> propsByBlock = new HashMap<>();

        for (Entity entity : entities) {
            if (!(entity instanceof ArmorStand armorStand) || !armorStand.isValid()) continue;

            String propEntityID = getPropEntityID(armorStand);
            if (propEntityID == null) continue;

            PropBlockKey key = PropBlockKey.from(propEntityID, armorStand.getLocation());
            if (key == null) continue;

            propsByBlock.computeIfAbsent(key, unused -> new ArrayList<>()).add(armorStand);
        }

        int removed = 0;
        for (Map.Entry<PropBlockKey, List<ArmorStand>> entry : propsByBlock.entrySet()) {
            List<ArmorStand> props = entry.getValue();
            if (props.size() < 2) continue;

            ArmorStand kept = choosePropToKeep(props);
            int removedForBlock = 0;
            for (ArmorStand armorStand : props) {
                if (armorStand.getUniqueId().equals(kept.getUniqueId())) continue;
                removeDuplicatePropArmorStand(armorStand);
                removedForBlock++;
            }

            removed += removedForBlock;
            alertDuplicatePropsRemoved(entry.getKey(), kept.getUniqueId(), removedForBlock);
        }

        return removed;
    }

    private static boolean removeIfDuplicateProp(String entityID, ArmorStand armorStand, Collection<? extends Entity> knownChunkEntities) {
        if (armorStand == null || !armorStand.isValid()) return true;

        ArmorStand existing = findLoadedPropOnSameBlock(entityID, armorStand.getLocation(), armorStand.getUniqueId(), knownChunkEntities);
        if (existing == null) return false;

        PropBlockKey key = PropBlockKey.from(entityID, armorStand.getLocation());
        removeDuplicatePropArmorStand(armorStand);
        if (key != null) alertDuplicatePropsRemoved(key, existing.getUniqueId(), 1);
        return true;
    }

    /**
     * @param knownChunkEntities entities the caller already holds for this chunk, or
     *                           {@code null} to look them up. Callers running inside a
     *                           chunk-load callback must supply the list: looking it up
     *                           there forces a re-entrant entity-manager tick that
     *                           corrupts the server's chunk-unload iteration.
     */
    private static ArmorStand findLoadedPropOnSameBlock(String entityID, Location location, UUID ignoredUuid, Collection<? extends Entity> knownChunkEntities) {
        if (entityID == null || location == null || location.getWorld() == null) return null;

        Collection<? extends Entity> candidates = knownChunkEntities != null
                ? knownChunkEntities
                : Arrays.asList(location.getChunk().getEntities());

        for (Entity entity : candidates) {
            if (!(entity instanceof ArmorStand armorStand) || !armorStand.isValid()) continue;
            if (ignoredUuid != null && ignoredUuid.equals(armorStand.getUniqueId())) continue;
            if (!entityID.equals(getPropEntityID(armorStand))) continue;
            if (!isSameBlock(location, armorStand.getLocation())) continue;
            return armorStand;
        }

        return null;
    }

    private static boolean isSameBlock(Location first, Location second) {
        if (first == null || second == null || first.getWorld() == null || second.getWorld() == null) return false;
        return first.getWorld().getUID().equals(second.getWorld().getUID())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private static ArmorStand choosePropToKeep(List<ArmorStand> props) {
        for (ArmorStand armorStand : props) {
            if (propEntities.containsKey(armorStand.getUniqueId())) return armorStand;
        }
        return props.get(0);
    }

    private static void removeDuplicatePropArmorStand(ArmorStand armorStand) {
        UUID duplicateUuid = armorStand.getUniqueId();
        PropEntity wrappedDuplicate = propEntities.get(duplicateUuid);
        if (wrappedDuplicate != null) {
            wrappedDuplicate.remove(false);
        } else {
            propEntities.remove(duplicateUuid);
            LuaPropTable.invalidate(duplicateUuid);
        }
        armorStand.remove();
    }

    private static void alertDuplicatePropsRemoved(PropBlockKey key, UUID keptUuid, int removed) {
        Logger.warn("[FMM Props] Removed " + removed + " duplicate prop armor stand(s) for model '"
                + key.entityID() + "' at " + key.formatLocation() + ". Kept " + keptUuid + ".");
    }

    private static String formatBlockLocation(Location location) {
        if (location == null || location.getWorld() == null) return "unknown location";
        return location.getWorld().getName() + " "
                + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private record PropBlockKey(String entityID, UUID worldId, String worldName, int blockX, int blockY, int blockZ) {
        static PropBlockKey from(String entityID, Location location) {
            if (entityID == null || location == null || location.getWorld() == null) return null;
            return new PropBlockKey(
                    entityID,
                    location.getWorld().getUID(),
                    location.getWorld().getName(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        }

        String formatLocation() {
            return worldName + " " + blockX + "," + blockY + "," + blockZ;
        }
    }

    private void initializePropEntity() {
        getDamageableComponent().setInternalHealth(3);
        setLeftClickCallback((player, entity) -> {
            if (isWithinPostSpawnGrace()) return;
            if (!PropScriptManager.onPropLeftClick(this, player)) {
                entity.damage(player);
            }
            resendFakeBlocks(player);
        });
        setRightClickCallback((player, entity) -> {
            if (isWithinPostSpawnGrace()) return;
            if (!PropScriptManager.onPropRightClick(this, player)) {
                if (getMountPointManager() != null && getMountPointManager().hasMountPoints()) {
                    getMountPointManager().tryMount(player);
                }
            }
            resendFakeBlocks(player);
        });
        // No showFakePropBlocksToAllPlayers() here: at construction time the skeleton
        // has zero viewers and propBlocks is empty, so the call was a guaranteed no-op.
    }

    private boolean isWithinPostSpawnGrace() {
        return System.currentTimeMillis() - spawnTimeMillis < POST_SPAWN_INTERACTION_GRACE_MS;
    }

    private void resendFakeBlocks(org.bukkit.entity.Player player) {
        if (propBlockComponent.getPropBlocks().isEmpty()) return;
        Bukkit.getScheduler().runTaskLater(MetadataHandler.PLUGIN, () -> {
            if (isRemoved()) return;
            propBlockComponent.showFakePropBlocksToPlayer(player);
        }, 1L);
    }

    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
        underlyingEntity.setPersistent(persistent);
    }

    @Override
    public void spawn() {
        ArmorStandListener.bypass = true;
        try {
            super.spawn(getSpawnLocation().getWorld().spawn(getSpawnLocation(), EntityType.ARMOR_STAND.getEntityClass(), entity -> {
                ArmorStand armorStand = (ArmorStand) entity;
                armorStand.setVisibleByDefault(false);
                armorStand.setVisible(false);
                armorStand.setMarker(true);
                armorStand.setSmall(true);
                // Base-entity INVISIBLE flag — required for Bedrock/Geyser, which does not honour
                // setVisibleByDefault for entities it fetches at NMS level.
                armorStand.setInvisible(true);
                armorStand.setGravity(false);
                armorStand.setInvulnerable(true);
                armorStand.setPersistent(true);
                armorStand.getPersistentDataContainer().set(propNamespacedKey, PersistentDataType.STRING, getEntityID());
            }));
        } finally {
            // This flag suppresses ArmorStandListener while the persistent
            // backing stand is being constructed. A failed World#spawn or
            // display initialization must never leave every later armor-stand
            // event bypassed for the lifetime of the server.
            ArmorStandListener.bypass = false;
        }
        PropScriptManager.onPropSpawn(this);
    }

    public void setCustomDataString(NamespacedKey customNamespacedKey, String data) {
        underlyingEntity.getPersistentDataContainer().set(customNamespacedKey, PersistentDataType.STRING, data);
    }

    public String getCustomDataString(NamespacedKey customNamespacedKey) {
        return underlyingEntity.getPersistentDataContainer().get(customNamespacedKey, PersistentDataType.STRING);
    }

    @Override
    public void remove() {
        remove(true);
    }

    public void remove(boolean showRealBlocks) {
        UUID underlyingUuid = underlyingEntity != null ? underlyingEntity.getUniqueId() : null;
        boolean removePersistentBackingEntity = persistent && isDying() && underlyingUuid != null;

        PropScriptManager.onPropRemove(this);
        LuaPropTable.invalidate(this);
        super.remove();
        if (showRealBlocks) showRealBlocksToAllPlayers();
        if (underlyingUuid != null) propEntities.remove(underlyingUuid);
        // Non-persistent props: ModeledEntity.remove() already despawns the underlying
        // entity (thread-safely, via the primary thread when needed) — no duplicate
        // remove() call needed here.
        if (removePersistentBackingEntity) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (underlyingEntity != null) underlyingEntity.remove();
                }
            }.runTask(MetadataHandler.PLUGIN);
        }
    }

    public void permanentlyRemove() {
        remove();
        if (underlyingEntity != null)
            new BukkitRunnable() {
                @Override
                public void run() {
                    underlyingEntity.remove();
                }
            }.runTask(MetadataHandler.PLUGIN);
    }

    //PropBlockComponent

    /**
     * Sets the prop blocks for this entity. Prop blocks are the fake blocks that are shown to players when they are near the entity.
     * These blocks are not actually placed in the world.
     * The recommended use is to replace real blocks with either air or barriers, depending on your needs.
     * Vectors are relative to the entity's spawn location, and will soon be used in configuration files.
     * Locations are the absolute locations of the blocks, only used by the API.
     *
     * @param propBlocks
     */
    public void setPropBlocks(List<PropBlocks> propBlocks) {
        propBlockComponent.setPropBlocks(propBlocks);
    }

    /**
     * Shows the fake prop blocks to a player.
     *
     * @param player Player to show the prop blocks to.
     */
    public void showFakePropBlocksToPlayer(Player player) {
        propBlockComponent.showFakePropBlocksToPlayer(player);
    }

    /**
     * Shows the fake prop blocks to all current entity viewers.
     */
    public void showFakePropBlocksToAllPlayers() {
        propBlockComponent.showFakePropBlocksToAllPlayers();
    }

    /**
     * Shows the real blocks to a player.
     *
     * @param player Player to show the real blocks to.
     */
    public void showRealBlocksToPlayer(Player player) {
        propBlockComponent.showRealBlocksToPlayer(player);
    }

    /**
     * Shows the real blocks to all current entity viewers.
     */
    public void showRealBlocksToAllPlayers() {
        propBlockComponent.showRealBlocksToAllPlayers();
    }

    public static class PropEntityEvents implements Listener {
        @EventHandler
        public void onArmorStandInteract(PlayerInteractEntityEvent event) {
            if (event.getRightClicked() instanceof ArmorStand armorStand && isPropEntity(armorStand))
                event.setCancelled(true);
        }

        /**
         * Restores props from their armor stands once the server has actually
         * finished loading a chunk's entities.
         * <p>
         * This deliberately listens to {@link EntitiesLoadEvent} rather than
         * {@code ChunkLoadEvent}. {@code ChunkLoadEvent} is fired from
         * {@code LevelChunk#loadCallback} while the chunk map's callback executor is
         * still mid-update, and at that point the chunk's entities are not loaded yet,
         * so {@code Chunk#getEntities()} forces a re-entrant
         * {@code PersistentEntitySectionManager#tick()}. That re-entrant tick runs
         * {@code processUnloads()}'s {@code chunksToUnload.removeIf(...)} on top of the
         * in-progress chunk load, which corrupts the server's own fastutil
         * {@code LongOpenHashSet} iterator and eventually kills the world tick.
         * {@link EntitiesLoadEvent#getEntities()} hands us the already-materialized
         * list instead, so no forced tick is needed.
         */
        @EventHandler(priority = EventPriority.LOWEST)
        public void onEntitiesLoadEvent(EntitiesLoadEvent event) {
            // Copied because the dedupe pass removes armor stands as it goes.
            List<Entity> loadedEntities = new ArrayList<>(event.getEntities());
            removeDuplicateProps(loadedEntities);
            for (Entity entity : loadedEntities) {
                if (entity instanceof ArmorStand armorStand) {
                    if (!armorStand.isValid()) continue;
                    String propEntityID = getPropEntityID(armorStand);
                    if (propEntityID == null) continue;
                    respawnPropEntityFromArmorStand(propEntityID, armorStand, loadedEntities);
                }
            }
        }

        //todo: well this isn't going to scale well
        @EventHandler
        public void onChunkUnloadEvent(ChunkUnloadEvent event) {
            String chunkHash = ChunkLocationChecker.chunkToString(event.getChunk());
            Collection<PropEntity> propEntitiesClone = new ArrayList<>(PropEntity.propEntities.values());
            for (PropEntity value : propEntitiesClone) {
                if (value.chunkHash.equals(chunkHash)) {
                    value.remove(false);
                }
            }
        }
    }
}
