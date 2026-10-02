package com.magmaguy.freeminecraftmodels.particles;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.ParticleKeyframe;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParticleImportTest {
    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        previousPlugin = MetadataHandler.PLUGIN;
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "FreeMinecraftModels";
                    throw new UnsupportedOperationException(method.getName());
                });
        ParticleEffectLibrary.setWarningSink(warnings::add);
    }

    @AfterEach
    void tearDown() {
        FileModelConverter.shutdown();
        ParticleEffectLibrary.setWarningSink(null);
        MetadataHandler.PLUGIN = previousPlugin;
    }

    private static Map<String, Object> particleKeyframe(double time, String effect, String locator, String file) {
        return Map.of("channel", "particle", "time", time, "data_points",
                List.of(Map.of("effect", effect, "locator", locator, "script", "", "file", file)));
    }

    private FileModelConverter convert(List<Map<String, Object>> keyframes) throws Exception {
        Path source = directory.resolve("particle_wand.bbmodel");
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", List.of(
                        Map.of("type", "locator", "uuid", "locator-tip", "name", "tip", "position", List.of(0, 14, 16)),
                        Map.of("type", "locator", "uuid", "locator-root", "name", "base", "position", List.of(0, 0, 0))),
                "outliner", List.of(
                        Map.of("name", "wand", "uuid", "bone-wand", "origin", List.of(0, 0, 0), "children", List.of("locator-tip")),
                        "locator-root"),
                "animations", List.of(Map.of("name", "idle", "loop", "loop", "length", 1.0, "animators",
                        Map.of("effects", Map.of("name", "Effects", "type", "effect", "keyframes", keyframes)))))));
        return new FileModelConverter(source.toFile());
    }

    @Test
    void importsKeyframesAndResolvesTheParticleFile() throws Exception {
        Files.copy(getClass().getResourceAsStream("/particles/particle_wand.json"), directory.resolve("particle_wand.json"));
        FileModelConverter converter = convert(List.of(
                particleKeyframe(0.5, "particle_wand", "tip", "../Downloads/particle_wand.json")));

        AnimationBlueprint idle = converter.getAnimationsBlueprint().getAnimations().get("idle");
        assertEquals(List.of(new ParticleKeyframe(10, "particle_wand", "tip", "particle_wand.json", "")),
                idle.getParticleKeyframes());
        ResolvedParticleEffect effect = converter.getParticleEffects().get("particle_wand");
        assertNotNull(effect);
        assertEquals("textures/blocks/wool_colored_white", effect.blueprint().texturePath());
        assertNull(effect.texture(), "The vanilla Bedrock texture is not beside the model");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("minecraft:particle_motion_collision")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("wool_colored_white")), warnings.toString());
        assertNotNull(converter.getSkeletonBlueprint().getLocatorByName("tip"));
        assertNotNull(converter.getSkeletonBlueprint().getLocatorByName("base"), "Root-level locators are registered");
    }

    @Test
    void findsEffectsByNameInAParticlesFolderAndUsesTheirTexture() throws Exception {
        Path particles = Files.createDirectories(directory.resolve("particles"));
        Files.writeString(particles.resolve("sparkle.json"), """
                {"particle_effect": {"description": {"identifier": "fmm:sparkle",
                  "basic_render_parameters": {"texture": "textures/particle/sparkle"}}, "components": {}}}""");
        Files.write(particles.resolve("sparkle.png"), new byte[]{1});
        FileModelConverter converter = convert(List.of(particleKeyframe(0, "sparkle", "tip", "")));
        ResolvedParticleEffect effect = converter.getParticleEffects().get("sparkle");
        assertEquals(particles.resolve("sparkle.png").toFile(), effect.texture());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void missingFilesDropOnlyTheirKeyframes() throws Exception {
        Files.copy(getClass().getResourceAsStream("/particles/particle_wand.json"), directory.resolve("particle_wand.json"));
        FileModelConverter converter = convert(List.of(
                particleKeyframe(0, "particle_wand", "tip", ""),
                particleKeyframe(0.25, "ghost", "tip", "ghost.json")));
        List<ParticleKeyframe> keyframes = converter.getAnimationsBlueprint().getAnimations().get("idle").getParticleKeyframes();
        assertEquals(1, keyframes.size());
        assertEquals("particle_wand", keyframes.getFirst().effect());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("ghost") && w.contains("particle_wand.bbmodel")), warnings.toString());
    }

    @Test
    void unknownLocatorsKeepTheKeyframeAndWarn() throws Exception {
        Files.copy(getClass().getResourceAsStream("/particles/particle_wand.json"), directory.resolve("particle_wand.json"));
        FileModelConverter converter = convert(List.of(particleKeyframe(0, "particle_wand", "nowhere", "")));
        assertEquals(1, converter.getAnimationsBlueprint().getAnimations().get("idle").getParticleKeyframes().size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("nowhere")), warnings.toString());
    }

    @Test
    void ignoresAModelDisplayJsonThatSharesTheEffectName() throws Exception {
        Files.writeString(directory.resolve("glow.json"), "{\"elements\": []}");
        Path particles = Files.createDirectories(directory.resolve("particles"));
        Files.writeString(particles.resolve("glow.json"), """
                {"particle_effect": {"description": {"identifier": "fmm:glow"}, "components": {}}}""");
        FileModelConverter converter = convert(List.of(particleKeyframe(0, "glow", "tip", "")));
        assertEquals("fmm:glow", converter.getParticleEffects().get("glow").blueprint().identifier());
    }

    @Test
    void findsParticleJsonFilesByEffectName() throws Exception {
        Files.writeString(directory.resolve("wand_particles.particle.json"), """
                {"particle_effect": {"description": {"identifier": "wand_particles"}, "components": {}}}""");
        FileModelConverter converter = convert(List.of(particleKeyframe(0, "wand_particles", "tip", "")));
        assertEquals("wand_particles", converter.getParticleEffects().get("wand_particles").blueprint().identifier());
    }
}
