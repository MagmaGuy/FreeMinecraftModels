package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.core.RegisterModelEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.checkerframework.checker.nullness.qual.Nullable;

public class PlayerDisguiseEntity extends DynamicEntity {
    public PlayerDisguiseEntity(String entityID, Location targetLocation) {
        super(entityID, targetLocation);
    }

    @Nullable
    public static PlayerDisguiseEntity create(String entityID, Player targetPlayer) {
        FileModelConverter fileModelConverter = FileModelConverter.getConvertedFileModels().get(entityID);
        if (fileModelConverter == null) return null;
        PlayerDisguiseEntity dynamicEntity = new PlayerDisguiseEntity(entityID, targetPlayer.getLocation());
        dynamicEntity.spawn(targetPlayer);
        targetPlayer.setVisibleByDefault(false);
        Bukkit.getOnlinePlayers().forEach(player -> {
            if (player.getLocation().getWorld().equals(dynamicEntity.getLocation().getWorld())) {
                player.hideEntity(MetadataHandler.PLUGIN, targetPlayer);
            }
        });
        targetPlayer.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1));
        return dynamicEntity;
    }

    @Override
    public void remove() {
        getInteractionComponent().clearCallbacks();
        getSkeleton().remove();
        getLoadedModeledEntities().remove(this);
        underlyingEntity.getPersistentDataContainer().remove(RegisterModelEntity.ENTITY_KEY);
    }
}
