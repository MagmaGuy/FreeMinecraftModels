package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Projectile;
import org.bukkit.persistence.PersistentDataType;

/** Shared marker used by the magic engine and FMM's modeled-hitbox router. */
public final class MagicProjectileMarker {
    private MagicProjectileMarker() {
    }

    public static boolean isMarked(Projectile projectile) {
        return projectile != null && projectile.getPersistentDataContainer().has(
                key(), PersistentDataType.BYTE);
    }

    static void mark(Projectile projectile) {
        projectile.getPersistentDataContainer().set(key(), PersistentDataType.BYTE, (byte) 1);
    }

    private static NamespacedKey key() {
        return new NamespacedKey(MetadataHandler.PLUGIN, "magic_projectile");
    }
}
