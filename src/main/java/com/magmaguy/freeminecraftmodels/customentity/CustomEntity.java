package com.magmaguy.freeminecraftmodels.customentity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_19_R1.CraftWorld;

public class CustomEntity extends Pig {

    public static void spawnEntity(Location location) {

    }

    public CustomEntity(Level world) {
        super(EntityType.PIG, world);
        //Sets the bounding box, the actual thing I want
        super.setBoundingBox(new AABB(new Vec3(-1, -1, -1), new Vec3(1, 1, 1)));
    }

    public CustomEntity(Location location) {
        super(EntityType.PIG, ((CraftWorld) location.getWorld()).getHandle());

    }
}
