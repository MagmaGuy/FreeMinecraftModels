package com.magmaguy.freeminecraftmodels.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.LocatorBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.ParticleKeyframe;
import com.magmaguy.freeminecraftmodels.particles.ResolvedParticleEffect;
import com.magmaguy.freeminecraftmodels.particles.held.HeldItemParticles;
import com.magmaguy.freeminecraftmodels.utils.StringToResourcePackFilename;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Passes Blockbench particles through to Bedrock: the Snowstorm files go into the bundle's
 * {@code particles/} folder under namespaced identifiers, locators go onto geometry bones, and
 * particle keyframes become animation particle timelines. Bedrock clients then play the original
 * effect themselves, including components Java cannot show.
 */
public final class BedrockParticleExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private BedrockParticleExporter() {
    }

    public static String identifier(String modelId, String effect) {
        return "fmm:" + StringToResourcePackFilename.convert(modelId + "_" + effect);
    }

    /** Writes each effect's particle file (and its texture, when FMM has it) into the bundle. */
    public static void writeParticles(File root, String modelId, FileModelConverter converter) throws IOException {
        for (ResolvedParticleEffect effect : converter.getParticleEffects().values()) {
            JsonObject json = JsonParser.parseString(Files.readString(effect.source().toPath(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject particle = json.getAsJsonObject("particle_effect");
            JsonObject description = particle.has("description") ? particle.getAsJsonObject("description") : new JsonObject();
            particle.add("description", description);
            description.addProperty("identifier", identifier(modelId, effect.name()));
            if (effect.texture() != null) {
                String key = StringToResourcePackFilename.convert(modelId) + "/" + StringToResourcePackFilename.convert(effect.name());
                File texture = new File(root, "textures/fmm_particles/" + key + ".png");
                Files.createDirectories(texture.getParentFile().toPath());
                Files.copy(effect.texture().toPath(), texture.toPath(), StandardCopyOption.REPLACE_EXISTING);
                JsonObject render = description.has("basic_render_parameters")
                        ? description.getAsJsonObject("basic_render_parameters") : new JsonObject();
                render.addProperty("texture", "textures/fmm_particles/" + key);
                description.add("basic_render_parameters", render);
            }
            // Without a texture beside the file, the original path stays: vanilla Bedrock textures resolve natively.
            File file = new File(root, "particles/" + StringToResourcePackFilename.convert(modelId + "_" + effect.name()) + ".json");
            Files.createDirectories(file.getParentFile().toPath());
            Files.writeString(file.toPath(), GSON.toJson(json), StandardCharsets.UTF_8);
        }
    }

    /**
     * Describes a held item's particles for ResourcePackManager, which adds them to the item's
     * Bedrock attachable: effect identifiers, locators in Java item model pixels and the looping
     * particle timeline. Written beside the Java pack's item models, not into the Bedrock bundle.
     */
    public static void writeHeldItemSidecar(File freeMinecraftModelsAssets, String itemId, FileModelConverter converter)
            throws IOException {
        AnimationBlueprint animation = HeldItemParticles.heldAnimation(converter);
        if (animation == null || converter.getParticleEffects().isEmpty()) return;
        JsonObject locators = new JsonObject();
        for (ParticleKeyframe keyframe : animation.getParticleKeyframes()) {
            LocatorBlueprint locator = converter.getSkeletonBlueprint().getLocatorByName(keyframe.locator());
            if (locator == null || locators.has(keyframe.locator())) continue;
            JsonArray position = new JsonArray();
            position.add(locator.getPosition().x + 8);
            position.add(locator.getPosition().y);
            position.add(locator.getPosition().z + 8);
            locators.add(keyframe.locator(), position);
        }
        JsonObject sidecar = new JsonObject();
        sidecar.add("effects", clientEntityEffects(converter.getID(), converter));
        sidecar.add("locators", locators);
        sidecar.addProperty("length", Math.max(0.05, animation.getDuration() / 20.0));
        sidecar.add("particle_effects", timeline(animation));
        File file = new File(freeMinecraftModelsAssets, "rspm_item_particles/display/" + itemId + ".json");
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), GSON.toJson(sidecar), StandardCharsets.UTF_8);
    }

    /** The client entity's effect short names mapped to their bundle identifiers. */
    public static JsonObject clientEntityEffects(String modelId, FileModelConverter converter) {
        JsonObject effects = new JsonObject();
        converter.getParticleEffects().keySet().stream().sorted()
                .forEach(effect -> effects.addProperty(effect, identifier(modelId, effect)));
        return effects;
    }

    /**
     * Locators that belong to {@code bone}, in Bedrock geometry space. A locator under a bone that
     * is not exported moves to its nearest exported ancestor, or to the first exported bone.
     */
    public static JsonObject locators(BoneBlueprint bone, FileModelConverter converter, List<BoneBlueprint> visualBones) {
        JsonObject locators = new JsonObject();
        if (visualBones.isEmpty()) return locators;
        List<LocatorBlueprint> sorted = new ArrayList<>(converter.getSkeletonBlueprint().getLocatorMap().values());
        sorted.sort(Comparator.comparing(LocatorBlueprint::getName));
        for (LocatorBlueprint locator : sorted) {
            BoneBlueprint owner = locator.getParentBone();
            while (owner != null && !visualBones.contains(owner)) owner = owner.getParent();
            if (owner == null) owner = visualBones.getFirst();
            if (owner != bone) continue;
            JsonArray position = new JsonArray();
            position.add(-locator.getPosition().x);
            position.add(locator.getPosition().y);
            position.add(locator.getPosition().z);
            locators.add(locator.getName(), position);
        }
        return locators;
    }

    /** The animation's particle keyframes as a Bedrock {@code particle_effects} timeline. */
    public static JsonObject timeline(AnimationBlueprint animation) {
        JsonObject timeline = new JsonObject();
        Map<String, JsonArray> byTime = new TreeMap<>(Comparator.comparingDouble(Double::parseDouble));
        for (ParticleKeyframe keyframe : animation.getParticleKeyframes()) {
            JsonObject point = new JsonObject();
            point.addProperty("effect", keyframe.effect());
            if (!keyframe.locator().isEmpty()) point.addProperty("locator", keyframe.locator());
            if (!keyframe.script().isBlank()) {
                String script = keyframe.script().trim();
                point.addProperty("pre_effect_script", script.endsWith(";") ? script : script + ";");
            }
            byTime.computeIfAbsent(timeKey(keyframe.tick()), ignored -> new JsonArray()).add(point);
        }
        byTime.forEach((time, points) -> timeline.add(time, points.size() == 1 ? points.get(0) : points));
        return timeline;
    }

    private static String timeKey(int tick) {
        String text = String.format(Locale.ROOT, "%.4f", tick / 20.0);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
