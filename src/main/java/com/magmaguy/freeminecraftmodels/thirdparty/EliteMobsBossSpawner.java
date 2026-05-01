package com.magmaguy.freeminecraftmodels.thirdparty;

import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/**
 * Thin direct-API wrapper around EliteMobs's {@link CustomBossEntity} spawn path.
 * Isolated in its own class so that its {@code com.magmaguy.elitemobs.*} imports
 * only trigger class loading when callers actually invoke {@link #spawn}; until
 * then the Bukkit plugin check in the caller short-circuits and EliteMobs's
 * classes are never referenced.
 */
public final class EliteMobsBossSpawner {

    private EliteMobsBossSpawner() {
    }

    public static LivingEntity spawn(String filename, Location spawnLocation) {
        CustomBossEntity boss = CustomBossEntity.createCustomBossEntity(filename);
        if (boss == null) return null;
        boss.spawn(spawnLocation, false);
        return boss.getLivingEntity();
    }
}
