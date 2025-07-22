package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import org.bukkit.entity.Projectile;

@FunctionalInterface
public interface ModeledEntityHitByProjectileCallback {
    void onHitByProjectile(Projectile projectile, ModeledEntity entity);
}