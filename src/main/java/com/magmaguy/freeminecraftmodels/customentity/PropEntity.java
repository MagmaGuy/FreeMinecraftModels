package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.PropEntityLeftClickEvent;
import com.magmaguy.freeminecraftmodels.api.PropEntityRightClickEvent;
import com.magmaguy.freeminecraftmodels.config.props.PropsConfig;
import com.magmaguy.freeminecraftmodels.config.props.PropsConfigFields;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;

public class PropEntity extends StaticEntity {
    public static final NamespacedKey propNamespacedKey = new NamespacedKey(MetadataHandler.PLUGIN, "prop");
    public static HashMap<ArmorStand, PropEntity> propEntities = new HashMap<>();
    private ArmorStand armorStand;
    private PropsConfigFields propsConfigFields;
    private double health = 3;

    public PropEntity(String entityID, Location spawnLocation) {
        super(entityID, spawnLocation);
        propsConfigFields = PropsConfig.getPropsConfigs().get(entityID + ".yml");
        if (propsConfigFields == null) {
            Logger.warn("Failed to initialize PropEntity: PropsConfigFields not found for entityID: " + entityID);
            return;
        }
        armorStand = (ArmorStand) spawnLocation.getWorld().spawn(spawnLocation, EntityType.ARMOR_STAND.getEntityClass(), entity -> {
            entity.setVisibleByDefault(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(true);
            entity.getPersistentDataContainer().set(propNamespacedKey, PersistentDataType.STRING, entityID);
        });
    }

    public PropEntity(String entityID, ArmorStand armorStand) {
        super(entityID, armorStand.getLocation());
        propsConfigFields = PropsConfig.getPropsConfigs().get(entityID + ".yml");
        if (propsConfigFields == null) {
            Logger.warn("Failed to initialize PropEntity: PropsConfigFields not found for entityID: " + entityID);
            return;
        }
        this.armorStand = armorStand;
    }

    public static void onStartup() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk loadedChunk : world.getLoadedChunks()) {
                for (Entity entity : loadedChunk.getEntities()) {
                    if (entity instanceof ArmorStand armorStand) {
                        String propEntityID = getPropEntityID(armorStand);
                        if (propEntityID == null) continue;
                        spawnPropEntity(propEntityID, armorStand);
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

    public static void spawnPropEntity(String entityID, Location spawnLocation) {
        PropEntity propEntity = new PropEntity(entityID, spawnLocation);
        propEntity.spawn();
    }

    public static void spawnPropEntity(String entityID, ArmorStand armorStand) {
        PropEntity propEntity = new PropEntity(entityID, armorStand);
        propEntity.spawn();
    }

    public static boolean isPropEntity(ArmorStand armorStand) {
        return armorStand.getPersistentDataContainer().has(propNamespacedKey, PersistentDataType.STRING);
    }

    public static String getPropEntityID(ArmorStand armorStand) {
        return armorStand.getPersistentDataContainer().get(propNamespacedKey, PersistentDataType.STRING);
    }

    @Override
    public void damageByLivingEntity(LivingEntity livingEntity) {
        if (propsConfigFields.isOnlyRemovableByOPs() && !livingEntity.isOp()) return;
        if (armorStand == null) {
            permanentlyRemove();
            Logger.warn("Failed to damage PropEntity: ArmorStand is null!");
            return;
        }
        health -= 1;
        getSkeleton().tint();
        if (!armorStand.isValid() || health <= 0) permanentlyRemove();
    }

    @Override
    public void damageByLivingEntity(LivingEntity livingEntity, double damage) {
        if (propsConfigFields.isOnlyRemovableByOPs() && !livingEntity.isOp()) return;
        if (armorStand == null) {
            permanentlyRemove();
            Logger.warn("Failed to damage PropEntity: ArmorStand is null!");
            return;
        }
        health -= 1;
        getSkeleton().tint();
        if (!armorStand.isValid() || health <= 0) permanentlyRemove();
    }

    @Override
    public void remove() {
        super.remove();
        propEntities.remove(armorStand);
    }

    @Override
    protected void shutdownRemove() {
        remove();
    }

    public void permanentlyRemove() {
        remove();
        if (armorStand != null) armorStand.remove();
    }

    @Override
    public void triggerLeftClickEvent(Player player) {
        super.triggerLeftClickEvent(player);
        PropEntityLeftClickEvent event = new PropEntityLeftClickEvent(player, this);
        Bukkit.getPluginManager().callEvent(event);
    }

    @Override
    public void triggerRightClickEvent(Player player) {
        super.triggerRightClickEvent(player);
        PropEntityRightClickEvent event = new PropEntityRightClickEvent(player, this);
        Bukkit.getPluginManager().callEvent(event);
    }

    public static class PropEntityEvents implements Listener {
        @EventHandler
        public void onArmorStandInteract(PlayerInteractEntityEvent event) {
            if (event.getRightClicked() instanceof ArmorStand armorStand && isPropEntity(armorStand))
                event.setCancelled(true);
        }

        @EventHandler
        public void onChunkLoadEvent(ChunkLoadEvent event) {
            for (Entity entity : event.getChunk().getEntities()) {
                if (entity instanceof ArmorStand armorStand) {
                    String propEntityID = getPropEntityID(armorStand);
                    if (propEntityID == null) continue;
                    spawnPropEntity(propEntityID, armorStand);
                }
            }
        }

        @EventHandler
        private void onChunkUnloadEvent(ChunkUnloadEvent event) {
            for (Entity entity : event.getChunk().getEntities()) {
                if (entity instanceof ArmorStand armorStand) {
                    PropEntity propEntity = propEntities.get(armorStand);
                    if (propEntity == null) continue;
                    propEntity.remove();
                }
            }
        }
    }
}
