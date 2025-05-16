package com.magmaguy.freeminecraftmodels.customentity.core;

import org.bukkit.World;
import org.bukkit.entity.Player;

public interface ModeledEntityInterface {
    void damage(Player player);

    World getWorld();

    void damage(Player player, double damage);
}
