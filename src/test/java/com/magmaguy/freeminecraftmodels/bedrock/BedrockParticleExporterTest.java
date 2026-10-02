package com.magmaguy.freeminecraftmodels.bedrock;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.particles.ParticleEffectLibrary;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BedrockParticleExporterTest {
    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private FileModelConverter converter;

    @BeforeEach
    void convert() throws Exception {
        previousPlugin = MetadataHandler.PLUGIN;
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "FreeMinecraftModels";
                    throw new UnsupportedOperationException(method.getName());
                });
        ParticleEffectLibrary.setWarningSink(ignored -> {
        });
        Files.writeString(directory.resolve("spark.json"), """
                {"format_version": "1.10.0", "particle_effect": {"description": {"identifier": "",
                  "basic_render_parameters": {"material": "particles_alpha", "texture": "textures/particle/spark"}},
                  "components": {"minecraft:emitter_rate_steady": {"spawn_rate": 5, "max_particles": 10}}}}""");
        Files.write(directory.resolve("spark.png"), new byte[]{1, 2, 3});
        Files.writeString(directory.resolve("lantern.bbmodel"), new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", List.of(Map.of("type", "locator", "uuid", "locator-tip", "name", "tip", "position", List.of(2, 14, 16))),
                "outliner", List.of(Map.of("name", "body", "uuid", "bone-body", "origin", List.of(0, 0, 0),
                        "children", List.of("locator-tip"))),
                "animations", List.of(Map.of("name", "idle", "loop", "loop", "length", 1.0, "animators",
                        Map.of("effects", Map.of("name", "Effects", "type", "effect", "keyframes", List.of(
                                Map.of("channel", "particle", "time", 0.5, "data_points", List.of(
                                        Map.of("effect", "spark", "locator", "tip", "script", "v.size = 2", "file", "spark.json")))))))))));
        converter = new FileModelConverter(directory.resolve("lantern.bbmodel").toFile());
    }

    @AfterEach
    void cleanUp() {
        FileModelConverter.shutdown();
        ParticleEffectLibrary.setWarningSink(null);
        MetadataHandler.PLUGIN = previousPlugin;
    }

    @Test
    void writesNamespacedParticleFilesWithTheirTexture() throws Exception {
        Path root = directory.resolve("bundle");
        BedrockParticleExporter.writeParticles(root.toFile(), "lantern", converter);
        JsonObject description = JsonParser.parseString(Files.readString(root.resolve("particles/lantern_spark.json")))
                .getAsJsonObject().getAsJsonObject("particle_effect").getAsJsonObject("description");
        assertEquals("fmm:lantern_spark", description.get("identifier").getAsString());
        assertEquals("textures/fmm_particles/lantern/spark",
                description.getAsJsonObject("basic_render_parameters").get("texture").getAsString());
        assertTrue(Files.isRegularFile(root.resolve("textures/fmm_particles/lantern/spark.png")));
    }

    @Test
    void mapsEffectsLocatorsAndTimeline() {
        assertEquals("fmm:lantern_spark",
                BedrockParticleExporter.clientEntityEffects("lantern", converter).get("spark").getAsString());

        JsonObject timeline = BedrockParticleExporter.timeline(converter.getAnimationsBlueprint().getAnimations().get("idle"));
        JsonObject point = timeline.getAsJsonObject("0.5");
        assertEquals("spark", point.get("effect").getAsString());
        assertEquals("tip", point.get("locator").getAsString());
        assertEquals("v.size = 2;", point.get("pre_effect_script").getAsString());

        List<BoneBlueprint> visual = BedrockEntityBundleExporter.visualBones(converter);
        BoneBlueprint owner = converter.getSkeletonBlueprint().getLocatorByName("tip").getParentBone();
        JsonObject locators = BedrockParticleExporter.locators(owner, converter, visual);
        assertEquals("[-2.0,14.0,16.0]", locators.get("tip").toString(), "Bedrock geometry mirrors X");
    }
}
