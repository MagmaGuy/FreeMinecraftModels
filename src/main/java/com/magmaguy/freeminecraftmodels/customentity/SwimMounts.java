package com.magmaguy.freeminecraftmodels.customentity;

import org.bukkit.entity.AbstractNautilus;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * The vanilla nautilus a diving mount swims as, where the server has one (1.21.11 and later). Every nautilus type and
 * the saddle slot are reached through {@link Nautili}, which only loads when the server has them, so this class
 * loads on older servers and reports no nautilus there.
 */
public final class SwimMounts {
    /** The carrier a diving pet's body spawns as. */
    public static final String CARRIER = "minecraft:nautilus";
    private static final boolean AVAILABLE = classExists("org.bukkit.entity.AbstractNautilus");

    private SwimMounts() {
    }

    /** Whether this server has the nautilus. */
    public static boolean available() {
        return AVAILABLE;
    }

    /** Whether this entity is a nautilus. */
    public static boolean isSwimMount(Entity entity) {
        return AVAILABLE && Nautili.is(entity);
    }

    /** Whether this entity is a nautilus wearing a saddle, which its first passenger steers. */
    public static boolean isSaddled(Entity entity) {
        return AVAILABLE && Nautili.saddled(entity);
    }

    /** Tames a nautilus to its owner and saddles it; vanilla takes a saddle only on a tame one. */
    public static void saddle(LivingEntity body, Player owner, ItemStack saddle) {
        if (AVAILABLE) Nautili.saddle(body, owner, saddle);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, SwimMounts.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }

    private static final class Nautili {
        static boolean is(Entity entity) {
            return entity instanceof AbstractNautilus;
        }

        static boolean saddled(Entity entity) {
            if (!(entity instanceof AbstractNautilus nautilus)) return false;
            EntityEquipment equipment = nautilus.getEquipment();
            ItemStack saddle = equipment == null ? null : equipment.getItem(EquipmentSlot.SADDLE);
            return saddle != null && !saddle.getType().isAir();
        }

        static void saddle(LivingEntity body, Player owner, ItemStack saddle) {
            if (!(body instanceof AbstractNautilus nautilus)) return;
            nautilus.setTamed(true);
            if (owner != null) nautilus.setOwner(owner);
            EntityEquipment equipment = nautilus.getEquipment();
            if (equipment != null) equipment.setItem(EquipmentSlot.SADDLE, saddle);
        }
    }
}
