package com.magmaguy.freeminecraftmodels.api.magic;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/** Stable capability boundary for FMM-owned magic weapons. */
public final class MagicWeaponAPI {
    public static final int CAPABILITY_VERSION = 6;
    public static final String DEFAULT_WAND_ID = "fmm_default_arcane_wand";
    public static final String DEFAULT_STAFF_ID = "fmm_default_arcane_staff";

    private MagicWeaponAPI() {
    }

    public static int capabilityVersion() {
        return CAPABILITY_VERSION;
    }

    public static boolean isOperational() {
        MagicWeaponService service = service();
        return service != null && service.isOperational();
    }

    public static boolean isServiceAvailable() {
        return service() != null;
    }

    public static boolean isWeapon(String itemId) {
        MagicWeaponService service = service();
        return service != null && service.isWeapon(localId(itemId));
    }

    /**
     * Adds FMM's magic identity and default presentation to an externally-created item.
     * Existing names, lore, levels and enchantments remain untouched.
     */
    public static boolean applyWeaponData(ItemStack itemStack, String itemId) {
        MagicWeaponService service = service();
        return service != null && service.applyWeaponData(itemStack, localId(itemId));
    }

    /** Returns the authored family, or null when the item/provider is unavailable. */
    public static MagicWeaponKind weaponKind(String itemId) {
        MagicWeaponService service = service();
        return service == null ? null : service.weaponKind(localId(itemId));
    }

    private static String localId(String id) {
        if (id == null) return "";
        return id.startsWith("freeminecraftmodels:") ? id.substring("freeminecraftmodels:".length()) : id;
    }

    /** Registers one optional progression adapter. FMM still owns every damage application. */
    public static boolean registerResolver(Plugin owner, MagicAttackResolver resolver) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(resolver, "resolver");
        MagicWeaponService service = service();
        return service != null && service.registerResolver(owner, resolver);
    }

    public static void unregisterResolver(Plugin owner) {
        if (owner == null) return;
        MagicWeaponService service = service();
        if (service != null) service.unregisterResolver(owner);
    }

    private static MagicWeaponService service() {
        return Bukkit.getServicesManager().load(MagicWeaponService.class);
    }
}
