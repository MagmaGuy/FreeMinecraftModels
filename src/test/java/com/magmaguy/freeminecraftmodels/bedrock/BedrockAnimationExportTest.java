package com.magmaguy.freeminecraftmodels.bedrock;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.ExpressionTrack;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.particles.molang.MolangContext;
import com.magmaguy.freeminecraftmodels.particles.molang.MolangParser;
import com.magmaguy.freeminecraftmodels.utils.TransformationType;
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
import java.util.SplittableRandom;

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
        converter = load("5.0");
    }

    /** The same model saved by Blockbench 5, which keys on its own axes, or by Blockbench 4, which keys in Bedrock's. */
    private FileModelConverter load(String format) throws Exception {
        Map<String, Object> keyframe = Map.of("channel", "position", "interpolation", "linear", "time", 0,
                "data_points", List.of(Map.of("x", 0, "y", 4, "z", 0)));
        Map<String, Object> expression = Map.of("channel", "rotation", "interpolation", "linear", "time", 0.25,
                "data_points", List.of(Map.of("x", "q.anim_time * 10", "y", "", "z", 5)));
        // A Blockbench program as the Driftjelly interact animation keys it: no return and no closing ;.
        Map<String, Object> program = Map.of("channel", "rotation", "interpolation", "linear", "time", 0,
                "data_points", List.of(Map.of("x", "v.w = math.sin(q.anim_time * 30);\nmath.atan2(1, 1) * v.w\n",
                        "y", 0, "z", 0)));
        // A number on x beside expressions on y and z, and a position keyed on every axis.
        Map<String, Object> turn = Map.of("channel", "rotation", "interpolation", "linear", "time", 0,
                "data_points", List.of(Map.of("x", 12, "y", "q.anim_time * 4", "z", "q.anim_time * 3")));
        Map<String, Object> slide = Map.of("channel", "position", "interpolation", "linear", "time", 0,
                "data_points", List.of(Map.of("x", "q.anim_time * 8", "y", 2, "z", "q.anim_time")));
        List<Object> animations = List.of(
                animation("walk", false, "", keyframe), animation("idle", false, "", keyframe),
                animation("death", false, "", keyframe), animation("attack", true, "0.5", keyframe),
                animation("spin", false, "", expression), animation("sway", false, "", program),
                animation("turn", false, "", turn), animation("slide", false, "", slide));
        Path source = directory.resolve("blend_export" + (format.equals("5.0") ? "" : "_format4") + ".bbmodel");
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", format, "model_format", "free"),
                "elements", List.of(),
                "outliner", List.of(Map.of("name", "body", "uuid", "bone-body", "origin", List.of(0, 0, 0),
                        "children", List.of())),
                "animations", animations)));
        return new FileModelConverter(source.toFile());
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
    void aFormat5ExpressionGoesOutWithXAndYNegated() throws Exception {
        // Blockbench 5 keys on its own axes; Bedrock reads x and y of a rotation and x of a position the other way.
        JsonObject animations = exportedAnimations(converter);
        JsonObject rotation = channel(animations, "spin", "rotation");
        assertEquals(List.of("0.25"), List.copyOf(rotation.keySet()), "the authored time, not every tick");
        assertEquals("-(q.anim_time * 10)", rotation.getAsJsonArray("0.25").get(0).getAsString());
        assertEquals(0, rotation.getAsJsonArray("0.25").get(1).getAsDouble(), 1e-9, "a blank axis is 0");
        assertEquals(5, rotation.getAsJsonArray("0.25").get(2).getAsDouble(), 1e-9, "z stays as written");
        assertEquals("v.w = math.sin(q.anim_time * 30);\nreturn -(math.atan2(1, 1) * v.w);",
                channel(animations, "sway", "rotation").getAsJsonArray("0").get(0).getAsString(),
                "a program returns its last line, negated, and ends with the ; Bedrock requires");
        JsonArray turn = channel(animations, "turn", "rotation").getAsJsonArray("0");
        assertEquals(-12, turn.get(0).getAsDouble(), 1e-9, "a number beside an expression is negated too");
        assertEquals("-(q.anim_time * 4)", turn.get(1).getAsString());
        assertEquals("q.anim_time * 3", turn.get(2).getAsString());
        JsonArray slide = channel(animations, "slide", "position").getAsJsonArray("0");
        assertEquals("-(q.anim_time * 8)", slide.get(0).getAsString());
        assertEquals(2, slide.get(1).getAsDouble(), 1e-9);
        assertEquals("q.anim_time", slide.get(2).getAsString());
    }

    @Test
    void aFormat4ExpressionGoesOutAsAuthored() throws Exception {
        // Blockbench 4 keys in Bedrock's convention already.
        JsonObject animations = exportedAnimations(load("4.10"));
        JsonObject rotation = channel(animations, "spin", "rotation");
        assertEquals("q.anim_time * 10", rotation.getAsJsonArray("0.25").get(0).getAsString());
        assertEquals(5, rotation.getAsJsonArray("0.25").get(2).getAsDouble(), 1e-9);
        assertEquals("v.w = math.sin(q.anim_time * 30);\nreturn math.atan2(1, 1) * v.w;",
                channel(animations, "sway", "rotation").getAsJsonArray("0").get(0).getAsString(),
                "a program returns its last line and ends with the ; Bedrock requires");
        JsonArray turn = channel(animations, "turn", "rotation").getAsJsonArray("0");
        assertEquals(12, turn.get(0).getAsDouble(), 1e-9);
        assertEquals("q.anim_time * 4", turn.get(1).getAsString());
        assertEquals("q.anim_time * 8", channel(animations, "slide", "position").getAsJsonArray("0").get(0).getAsString());
    }

    @Test
    void anExportedExpressionGivesThePoseJavaPlays() throws Exception {
        // The pose mixer's units are Bedrock's convention in radians and blocks (see BedrockPoseExportTest), so an
        // exported axis, evaluated as Bedrock would, has to give the pose Java samples: in degrees and pixels.
        for (String format : List.of("5.0", "4.10")) {
            FileModelConverter model = format.equals("5.0") ? converter : load(format);
            JsonObject animations = exportedAnimations(model);
            for (String name : List.of("spin", "sway", "turn", "slide")) {
                List<ExpressionTrack> tracks = model.getAnimationsBlueprint().getAnimations().get(name)
                        .getExpressionTracks().values().iterator().next();
                assertEquals(1, tracks.size(), name);
                ExpressionTrack track = tracks.getFirst();
                boolean rotation = track.channel() == TransformationType.ROTATION;
                JsonObject exported = channel(animations, name, rotation ? "rotation" : "position");
                JsonArray values = exported.getAsJsonArray(exported.keySet().iterator().next());
                for (int axis = 0; axis < 3; axis++) {
                    // Tick 5 is 0.25 s. Each side gets its own variables, as a program's assignment is kept.
                    double java = track.sample(axis, 5, at(0.25));
                    double pose = rotation ? Math.toDegrees(java) : java * 16;
                    JsonElement value = values.get(axis);
                    double bedrock = value.getAsJsonPrimitive().isNumber() ? value.getAsDouble()
                            : MolangParser.parse(value.getAsString()).evaluate(at(0.25));
                    assertEquals(pose, bedrock, 1e-4, "format " + format + ", " + name + " axis " + axis + ": " + value);
                }
            }
        }
    }

    private static MolangContext at(double seconds) {
        MolangContext context = new MolangContext(new SplittableRandom(1));
        context.set("query.anim_time", seconds);
        return context;
    }

    private JsonObject exportedAnimations(FileModelConverter model) throws Exception {
        Path root = directory.resolve("bundle-" + model.getID());
        Method writer = BedrockEntityBundleExporter.class.getDeclaredMethod("writeAnimations", File.class, String.class,
                FileModelConverter.class, List.class);
        writer.setAccessible(true);
        writer.invoke(null, root.toFile(), model.getID(), model, BedrockEntityBundleExporter.visualBones(model));
        return read(root.resolve("animations/" + model.getID() + ".animation.json")).getAsJsonObject("animations");
    }

    private static JsonObject channel(JsonObject animations, String animation, String channel) {
        return find(animations, animation).getAsJsonObject("bones").getAsJsonObject("body").getAsJsonObject(channel);
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
