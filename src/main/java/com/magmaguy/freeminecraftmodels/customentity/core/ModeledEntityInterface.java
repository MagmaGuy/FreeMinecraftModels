package com.magmaguy.freeminecraftmodels.customentity.core;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

public interface ModeledEntityInterface {
    BoundingBox getHitbox();

    World getWorld();

    void damage(Player player, double damage);
}
