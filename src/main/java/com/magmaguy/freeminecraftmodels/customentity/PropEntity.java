package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.props.PropBlocks;
import com.magmaguy.freeminecraftmodels.config.props.PropsConfig;
import com.magmaguy.freeminecraftmodels.config.props.PropsConfigFields;
import com.magmaguy.freeminecraftmodels.customentity.core.components.PropBlockComponent;
import com.magmaguy.magmacore.util.ChunkLocationChecker;
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
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class PropEntity extends StaticEntity {
    public static final NamespacedKey propNamespacedKey = new NamespacedKey(MetadataHandler.PLUGIN, "prop");
    @Getter
    public static HashMap<UUID, PropEntity> propEntities = new HashMap<>();
    private final String entityID;
    @Getter
    private final PropBlockComponent propBlockComponent = new PropBlockComponent(this);
    private PropsConfigFields propsConfigFields;
    @Getter
    private boolean persistent = true;
    private String chunkHash;

    public PropEntity(String entityID, Location spawnLocation) {
        super(entityID, spawnLocation);
        this.entityID = entityID;
        propsConfigFields = PropsConfig.getPropsConfigs().get(entityID + ".yml");
        initializePropEntity();
    }

    public PropEntity(String entityID, ArmorStand armorStand) {
        super(entityID, armorStand.getLocation());
        this.entityID = entityID;
        propsConfigFields = PropsConfig.getPropsConfigs().get(entityID + ".yml");
        initializePropEntity();
        spawn(armorStand);
        propEntities.put(armorStand.getUniqueId(), this);
        chunkHash = ChunkLocationChecker.chunkToString(underlyingEntity.getLocation().getChunk());
    }

    private void initializePropEntity() {
        getDamageableComponent().setInternalHealth(3);
        setLeftClickCallback((player, entity) -> entity.damage(player));
        propBlockComponent.showFakePropBlocksToAllPlayers();
    }

    public static void onStartup() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk loadedChunk : world.getLoadedChunks()) {
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

    public static PropEntity spawnPropEntity(String entityID, Location location, PropsConfigFields config) {
        PropEntity propEntity = new PropEntity(entityID, location);
        propEntity.propsConfigFields = config;
        propEntity.spawn();
        return propEntity;
    }

    public static PropEntity spawnPropEntity(String entityID, Location spawnLocation) {
        PropEntity propEntity = new PropEntity(entityID, spawnLocation);
        propEntity.spawn();
        return propEntity;
    }

    public static PropEntity respawnPropEntityFromArmorStand(String entityID, ArmorStand armorStand) {
        if (propEntities.containsKey(armorStand.getUniqueId())) {
            return propEntities.get(armorStand.getUniqueId());
        }
        PropEntity propEntity = new PropEntity(entityID, armorStand);
        return propEntity;
    }

    public static boolean isPropEntity(ArmorStand armorStand) {
        return armorStand.getPersistentDataContainer().has(propNamespacedKey, PersistentDataType.STRING);
    }

    public static String getPropEntityID(ArmorStand armorStand) {
        return armorStand.getPersistentDataContainer().get(propNamespacedKey, PersistentDataType.STRING);
    }

    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
        underlyingEntity.setPersistent(persistent);
    }

    public void spawn() {
        super.spawn(getSpawnLocation().getWorld().spawn(getSpawnLocation(), EntityType.ARMOR_STAND.getEntityClass(), entity -> {
            entity.setVisibleByDefault(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(true);
            entity.getPersistentDataContainer().set(propNamespacedKey, PersistentDataType.STRING, entityID);
        }));
        chunkHash = ChunkLocationChecker.chunkToString(underlyingEntity.getLocation().getChunk());
        propEntities.put(underlyingEntity.getUniqueId(), this);
    }

    public void setCustomDataString(NamespacedKey customNamespacedKey, String data) {
        underlyingEntity.getPersistentDataContainer().set(customNamespacedKey, PersistentDataType.STRING, data);
    }

    public String getCustomDataString(NamespacedKey customNamespacedKey) {
        return underlyingEntity.getPersistentDataContainer().get(customNamespacedKey, PersistentDataType.STRING);
    }

    @Override
    public void remove() {
        super.remove();
        showRealBlocksToAllPlayers();
        propEntities.remove(underlyingEntity.getUniqueId());
        if (!persistent) underlyingEntity.remove();
        if (isDying() && underlyingEntity != null) underlyingEntity.remove();
    }

    public void permanentlyRemove() {
        remove();
        if (underlyingEntity != null) underlyingEntity.remove();
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

        @EventHandler(priority = EventPriority.LOWEST)
        public void onChunkLoadEvent(ChunkLoadEvent event) {
            for (Entity entity : event.getChunk().getEntities()) {
                if (entity instanceof ArmorStand armorStand) {
                    String propEntityID = getPropEntityID(armorStand);
                    if (propEntityID == null) continue;
                    respawnPropEntityFromArmorStand(propEntityID, armorStand);
                }
            }
        }

        //todo: well this isn't going to scale well
        @EventHandler
        private void onChunkUnloadEvent(ChunkUnloadEvent event) {
            String chunkHash = ChunkLocationChecker.chunkToString(event.getChunk());
            Collection<PropEntity> propEntitiesClone = new ArrayList<>(PropEntity.propEntities.values());
            for (PropEntity value : propEntitiesClone) {
                if (value.chunkHash.equals(chunkHash)) {
                    value.remove();
                }
            }
        }
    }
}
