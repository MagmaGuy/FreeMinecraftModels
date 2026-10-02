package com.magmaguy.freeminecraftmodels.particles;

import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint;

import java.io.File;

/**
 * A particle effect a model uses, with the files it came from.
 *
 * @param name      effect short name from the keyframes
 * @param blueprint parsed Snowstorm effect
 * @param source    the particle JSON file
 * @param texture   the texture PNG, or null when it was not found (Java then shows a white sprite)
 */
public record ResolvedParticleEffect(String name, ParticleEffectBlueprint blueprint, File source, File texture) {
}
