package com.magmaguy.freeminecraftmodels.particles;

import com.magmaguy.freeminecraftmodels.particles.molang.MolangContext;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint.Billboard;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint.TextureRegion;
import com.magmaguy.freeminecraftmodels.utils.StringToResourcePackFilename;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The item-model sprites one particle effect needs on Java. Particle files often pick a random
 * texture variant per particle in Molang, so the UV expressions are sampled at import and every
 * variant/frame pair is baked. At runtime a particle uses the nearest baked variant.
 */
public final class ParticleSprites {
    private static final int MAX_VARIANTS = 16;
    private static final int SAMPLES = 64;

    private final String key;
    private final File texture;
    private final boolean whiteFallback;
    private final double textureWidth;
    private final double textureHeight;
    private final double width;
    private final double height;
    private final double stepU;
    private final double stepV;
    private final int frames;
    private final List<double[]> variants;

    private ParticleSprites(String key, File texture, boolean whiteFallback, double textureWidth, double textureHeight,
                            double width, double height, double stepU, double stepV, int frames,
                            List<double[]> variants) {
        this.key = key;
        this.texture = texture;
        this.whiteFallback = whiteFallback;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.width = width;
        this.height = height;
        this.stepU = stepU;
        this.stepV = stepV;
        this.frames = frames;
        this.variants = variants;
    }

    public static ParticleSprites of(String modelId, ResolvedParticleEffect effect) {
        String key = StringToResourcePackFilename.convert(modelId) + "/" + StringToResourcePackFilename.convert(effect.name());
        Billboard billboard = effect.blueprint().particle().billboard();
        TextureRegion region = billboard.region();
        if (effect.texture() == null || region == null) {
            // A plain white sprite has no regions worth telling apart; a missing region is the whole texture.
            return new ParticleSprites(key, effect.texture(), effect.texture() == null, 1, 1, 1, 1, 0, 0, 1,
                    List.<double[]>of(new double[]{0, 0}));
        }
        return new ParticleSprites(key, effect.texture(), false, billboard.textureWidth(), billboard.textureHeight(),
                region.width(), region.height(), region.stepU(), region.stepV(), region.frames(), variants(region));
    }

    private static List<double[]> variants(TextureRegion region) {
        List<double[]> found = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(42);
        for (int sample = 0; sample < SAMPLES && found.size() < MAX_VARIANTS; sample++) {
            MolangContext context = new MolangContext(random);
            for (int i = 1; i <= 4; i++) {
                double value = sample == 0 ? 0 : sample == 1 ? 0.9999 : random.nextDouble();
                context.set("variable.particle_random_" + i, value);
                context.set("variable.emitter_random_" + i, value);
            }
            double[] variant = {region.u().evaluate(context), region.v().evaluate(context)};
            if (found.stream().noneMatch(existing -> Math.abs(existing[0] - variant[0]) < 1e-6
                    && Math.abs(existing[1] - variant[1]) < 1e-6)) found.add(variant);
        }
        found.sort(Comparator.<double[]>comparingDouble(variant -> variant[1]).thenComparingDouble(variant -> variant[0]));
        return List.copyOf(found);
    }

    public String key() {
        return key;
    }

    public File texture() {
        return texture;
    }

    public boolean whiteFallback() {
        return whiteFallback;
    }

    public int spriteCount() {
        return variants.size() * frames;
    }

    public String itemModel(int index) {
        return "freeminecraftmodels:particle/" + key + "/" + index;
    }

    /** Sprite for a particle's sampled UV origin and current flipbook frame. */
    public int spriteIndex(double u, double v, int frame) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < variants.size(); i++) {
            double du = variants.get(i)[0] - u, dv = variants.get(i)[1] - v;
            double distance = du * du + dv * dv;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best * frames + Math.max(0, Math.min(frames - 1, frame));
    }

    /** Item-model UV of a sprite, in Minecraft's 0 to 16 face UV space: u0, v0, u1, v1. */
    public double[] uv(int index) {
        if (whiteFallback || textureWidth <= 0 || textureHeight <= 0) return new double[]{0, 0, 16, 16};
        double[] variant = variants.get(index / frames);
        int frame = index % frames;
        double u = variant[0] + stepU * frame, v = variant[1] + stepV * frame;
        return new double[]{u / textureWidth * 16, v / textureHeight * 16,
                (u + width) / textureWidth * 16, (v + height) / textureHeight * 16};
    }
}
