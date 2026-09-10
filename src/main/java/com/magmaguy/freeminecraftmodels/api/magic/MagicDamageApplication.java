package com.magmaguy.freeminecraftmodels.api.magic;

/**
 * One-shot sink supplied by FreeMinecraftModels to the active damage resolver.
 * A finite value at or below zero intentionally resolves the impact without damage.
 */
@FunctionalInterface
public interface MagicDamageApplication {
    void apply(double damage);
}
