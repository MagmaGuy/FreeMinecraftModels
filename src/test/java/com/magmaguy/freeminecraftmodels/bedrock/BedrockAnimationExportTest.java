package com.magmaguy.freeminecraftmodels.bedrock;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The Bedrock export carries the same override, weight and fade times as the Java mixer. */
class BedrockAnimationExportTest {
    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private int previousIdleWalk;
    private int previousDeath;
    private FileModelConverter converter;

    @BeforeEach
    void convert() throws Exception {
        previousPlugin = MetadataHandler.PLUGIN;
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "FreeMinecraftModels";
                    throw new UnsupportedOperationException(method.getName());
                });
        previousIdleWalk = DefaultConfig.idleWalkBlendTicks;
        previousDeath = DefaultConfig.deathBlendTicks;
        DefaultConfig.idleWalkBlendTicks = 5;
        DefaultConfig.deathBlendTicks = 4;
        Map<String, Object> keyframe = Map.of("channel", "position", "interpolation", "linear", "time", 0,
                "data_points", List.of(Map.of("x", 0, "y", 4, "z", 0)));
        List<Object> animations = List.of(
                animation("walk", false, "", keyframe), animation("idle", false, "", keyframe),
                animation("death", false, "", keyframe), animation("attack", true, "0.5", keyframe));
        Path source = directory.resolve("blend_export.bbmodel");
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", List.of(),
                "outliner", List.of(Map.of("name", "body", "uuid", "bone-body", "origin", List.of(0, 0, 0),
                        "children", List.of())),
                "animations", animations)));
        converter = new FileModelConverter(source.toFile());
    }

    @AfterEach
    void cleanUp() {
        DefaultConfig.idleWalkBlendTicks = previousIdleWalk;
        DefaultConfig.deathBlendTicks = previousDeath;
        FileModelConverter.shutdown();
        MetadataHandler.PLUGIN = previousPlugin;
    }

    @Test
    void animationsCarryOverrideAndWeight() throws Exception {
        Path root = directory.resolve("bundle");
        invoke("writeAnimations", root);
        JsonObject animations = read(root.resolve("animations/" + converter.getID() + ".animation.json"))
                .getAsJsonObject("animations");
        JsonObject attack = find(animations, "attack");
        JsonObject idle = find(animations, "idle");
        assertTrue(attack.get("override_previous_animation").getAsBoolean());
        assertEquals(0.5, attack.get("blend_weight").getAsDouble(), 1e-9);
        assertFalse(idle.has("override_previous_animation"));
        assertFalse(idle.has("blend_weight"));
    }

    @Test
    void controllersFadeIdleWalkAndDeathOnly() throws Exception {
        Path root = directory.resolve("bundle");
        invoke("writeAnimationControllers", root);
        JsonObject controllers = read(root.resolve("animation_controllers/" + converter.getID()
                + ".animation_controllers.json")).getAsJsonObject("animation_controllers");
        for (String name : List.of("idle", "walk")) {
            JsonObject states = find(controllers, name).getAsJsonObject("states");
            assertEquals(0.25, states.getAsJsonObject("play").get("blend_transition").getAsDouble(), 1e-9, name);
            assertEquals(0.25, states.getAsJsonObject("stop").get("blend_transition").getAsDouble(), 1e-9, name);
        }
        JsonObject death = find(controllers, "death").getAsJsonObject("states");
        assertEquals(0.2, death.getAsJsonObject("stop").get("blend_transition").getAsDouble(), 1e-9, "fade in");
        assertFalse(death.getAsJsonObject("play").has("blend_transition"), "no fade out");
        JsonObject attack = find(controllers, "attack").getAsJsonObject("states");
        assertFalse(attack.getAsJsonObject("play").has("blend_transition"));
        assertFalse(attack.getAsJsonObject("stop").has("blend_transition"));
    }

    @Test
    void baseAnimationsRunFirst() {
        assertEquals(List.of("idle", "walk", "death", "attack", "zz_custom"),
                BedrockEntityBundleExporter.animateOrder(List.of("attack", "death", "idle", "walk", "zz_custom")));
    }

    private void invoke(String method, Path root) throws Exception {
        Method writer = BedrockEntityBundleExporter.class.getDeclaredMethod(method, File.class, String.class,
                FileModelConverter.class, List.class);
        writer.setAccessible(true);
        writer.invoke(null, root.toFile(), converter.getID(), converter, BedrockEntityBundleExporter.visualBones(converter));
    }

    private static JsonObject read(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    /** Entries are keyed by a hex-encoded name; match the one ending in this animation's hex. */
    private static JsonObject find(JsonObject entries, String animation) {
        String hex = java.util.HexFormat.of().formatHex(animation.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return entries.entrySet().stream().filter(entry -> entry.getKey().endsWith(".a_" + hex))
                .findFirst().orElseThrow(() -> new AssertionError("No entry for " + animation + " in " + entries.keySet()))
                .getValue().getAsJsonObject();
    }

    private static Map<String, Object> animation(String name, boolean override, String blendWeight, Object keyframe) {
        Map<String, Object> animation = new HashMap<>();
        animation.put("name", name);
        animation.put("loop", "loop");
        animation.put("length", 0.5);
        animation.put("override", override);
        animation.put("blend_weight", blendWeight);
        animation.put("animators", Map.of("bone-body", Map.of("name", "body", "type", "bone", "keyframes", List.of(keyframe))));
        return animation;
    }
}
