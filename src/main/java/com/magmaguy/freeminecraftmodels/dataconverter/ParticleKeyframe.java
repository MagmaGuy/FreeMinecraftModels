package com.magmaguy.freeminecraftmodels.dataconverter;

/**
 * A Blockbench particle keyframe on an animation's Effects track.
 *
 * @param tick    animation frame the effect starts on
 * @param effect  effect short name; the Snowstorm file is looked up by it
 * @param locator locator name the effect follows, or empty for the model origin
 * @param file    file name Blockbench stored for the effect (without the author's folders), or empty
 * @param script  pre-effect Molang script, or empty
 */
public record ParticleKeyframe(int tick, String effect, String locator, String file, String script) {
}
