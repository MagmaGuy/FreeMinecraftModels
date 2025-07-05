package com.magmaguy.freeminecraftmodels.customentity;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface ModeledEntityRightClickCallback {
    void onRightClick(Player player, ModeledEntity entity);
}
