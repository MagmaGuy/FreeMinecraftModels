package com.magmaguy.freeminecraftmodels.particles;

import com.magmaguy.freeminecraftmodels.dataconverter.AnimationBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationsBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.ParticleKeyframe;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.SnowstormParser;
import com.magmaguy.magmacore.util.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

/**
 * Finds the Snowstorm files a model's particle keyframes use. Blockbench stores only the
 * author's local path, so FMM looks next to the model by that file name and then by effect name
 * ({@code <effect>.particle.json}, then {@code <effect>.json}), in the model's folder and then its
 * {@code particles/} folder. Problems warn and degrade;
 * they never stop the model from loading.
 */
public final class ParticleEffectLibrary {
    private static volatile Consumer<String> warningSink;

    private ParticleEffectLibrary() {
    }

    /** Redirects warnings and info notices, for tests. {@code null} restores the plugin logger. */
    public static void setWarningSink(Consumer<String> sink) {
        warningSink = sink;
    }

    static void warn(String message) {
        Consumer<String> sink = warningSink;
        if (sink != null) sink.accept(message);
        else Logger.warn(message);
    }

    static void info(String message) {
        Consumer<String> sink = warningSink;
        if (sink != null) sink.accept(message);
        else Logger.info(message);
    }

    /**
     * Resolves every effect the model's animations use and removes keyframes whose effect could
     * not be loaded.
     */
    public static Map<String, ResolvedParticleEffect> resolve(File modelFile, AnimationsBlueprint animations,
                                                              SkeletonBlueprint skeleton) {
        if (animations == null || modelFile == null) return Map.of();
        Map<String, ResolvedParticleEffect> resolved = new HashMap<>();
        Set<String> missing = new HashSet<>();
        Set<String> reportedLocators = new HashSet<>();
        for (AnimationBlueprint animation : animations.getAnimations().values()) {
            for (ParticleKeyframe keyframe : animation.getParticleKeyframes()) {
                String effect = keyframe.effect();
                if (!resolved.containsKey(effect) && !missing.contains(effect)) {
                    ResolvedParticleEffect loaded = load(modelFile, keyframe);
                    if (loaded == null) {
                        missing.add(effect);
                        warn("Particle effect '" + effect + "' used by animation '" + animation.getAnimationName()
                                + "' in " + modelFile.getName() + " was not found. Put " + effect
                                + ".json next to the model or in a particles folder beside it.");
                    } else {
                        resolved.put(effect, loaded);
                    }
                }
                if (!keyframe.locator().isEmpty() && skeleton != null
                        && skeleton.getLocatorByName(keyframe.locator()) == null
                        && reportedLocators.add(keyframe.locator())) {
                    warn("Particle effect '" + effect + "' in " + modelFile.getName() + " follows locator '"
                            + keyframe.locator() + "', which the model does not have. It plays at the model origin.");
                }
            }
            animation.getParticleKeyframes().removeIf(keyframe -> missing.contains(keyframe.effect()));
        }
        return Map.copyOf(resolved);
    }

    private static ResolvedParticleEffect load(File modelFile, ParticleKeyframe keyframe) {
        File modelFolder = modelFile.getParentFile();
        File particleFolder = new File(modelFolder, "particles");
        Set<File> candidates = new LinkedHashSet<>();
        if (!keyframe.file().isBlank()) {
            candidates.add(new File(modelFolder, keyframe.file()));
            candidates.add(new File(particleFolder, keyframe.file()));
        }
        for (String name : List.of(keyframe.effect() + ".particle.json", keyframe.effect() + ".json")) {
            candidates.add(new File(modelFolder, name));
            candidates.add(new File(particleFolder, name));
        }

        for (File candidate : candidates) {
            if (!candidate.isFile()) continue;
            ParticleEffectBlueprint blueprint;
            try {
                blueprint = SnowstormParser.parse(Files.readString(candidate.toPath(), StandardCharsets.UTF_8),
                        candidate.getName());
            } catch (IOException | IllegalArgumentException notAParticle) {
                // A model's display JSON can share the effect's name; keep looking.
                continue;
            }
            if (!blueprint.unsupportedComponents().isEmpty()) {
                // Informational: the effect still loads, and nothing on the server can fix it.
                info("Particle effect '" + keyframe.effect() + "' (" + candidate.getName() + ") uses "
                        + String.join(", ", blueprint.unsupportedComponents())
                        + ", which Java players do not see yet. Bedrock players see the full effect.");
            }
            File texture = texture(blueprint.texturePath(), candidate.getParentFile(), modelFolder);
            if (texture == null && !blueprint.texturePath().isBlank()) {
                warn("Particle effect '" + keyframe.effect() + "' uses texture '" + blueprint.texturePath()
                        + "', which is not next to " + candidate.getName() + ". Java players see a plain white"
                        + " sprite tinted by the effect's color; put the PNG beside the particle file to fix that.");
            }
            return new ResolvedParticleEffect(keyframe.effect(), blueprint, candidate, texture);
        }
        return null;
    }

    private static File texture(String path, File particleFolder, File modelFolder) {
        if (path == null || path.isBlank()) return null;
        String normalized = path.replace('\\', '/');
        String baseName = normalized.substring(normalized.lastIndexOf('/') + 1);
        for (File candidate : List.of(
                new File(particleFolder, baseName + ".png"),
                new File(particleFolder, normalized + ".png"),
                new File(modelFolder, baseName + ".png"),
                new File(new File(modelFolder, "particles"), baseName + ".png"),
                new File(modelFolder, normalized + ".png"))) {
            if (candidate.isFile()) return candidate;
        }
        return null;
    }
}
