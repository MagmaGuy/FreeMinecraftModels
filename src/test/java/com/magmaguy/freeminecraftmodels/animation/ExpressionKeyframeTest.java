package com.magmaguy.freeminecraftmodels.animation;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.components.AnimationComponent;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.Keyframe;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keyframes whose value is a MoLang expression: evaluated per tick at the playback's time, blended with their
 * neighbours like numbers, and read in radians with the format-5 sign convention. A broken expression warns
 * once at load and plays as 0.
 */
class ExpressionKeyframeTest {
    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private boolean installedServer;
    private FileModelConverter converter;
    private AnimationComponent animation;
    private Bone body;

    @BeforeEach
    void fakePlugin() {
        previousPlugin = MetadataHandler.PLUGIN;
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "FreeMinecraftModels";
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @AfterEach
    void discardModel() throws Exception {
        FileModelConverter.shutdown();
        MetadataHandler.PLUGIN = previousPlugin;
        if (installedServer) {
            // Bukkit's singleton can only be set once; later test classes install their own, so give it back.
            Field server = Bukkit.class.getDeclaredField("server");
            server.setAccessible(true);
            server.set(null, null);
        }
    }

    @Test
    void expressionKeyframesEvaluateAtThePlaybackTimeThenInterpolate() throws Exception {
        load(animation("wave", List.of(
                rotation(0, "q.anim_time * 10"),
                rotation(1.0, 20))));
        assertTrue(animation.playAnimation("wave", false, true));

        animation.tick();
        assertEquals(0, body.getAnimationRotation().x, 1e-6, "frame 0: the expression reads anim_time 0");
        for (int tick = 1; tick <= 10; tick++) animation.tick();
        // frame 10 is 0.5 s: the expression gives 5 and the next key 20, blended halfway to 12.5 degrees.
        assertEquals(-Math.toRadians(12.5), body.getAnimationRotation().x, 1e-6, "frame 10");
        for (int tick = 11; tick <= 20; tick++) animation.tick();
        assertEquals(-Math.toRadians(20), body.getAnimationRotation().x, 1e-6, "frame 20 holds the last key");
    }

    @Test
    void aBrokenExpressionWarnsAndPlaysAsZero() throws Exception {
        List<String> warnings = captureWarnings();
        load(animation("broken", List.of(rotation(0, "math.nope(1)"), rotation(1.0, 20))));

        AnimationBlueprint blueprint = converter.getAnimationsBlueprint().getAnimations().get("broken");
        Keyframe first = blueprint.getBoneKeyframes().values().iterator().next().get(0);
        assertFalse(first.isDynamic());
        assertEquals(0, first.getDataX());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("math.nope(1)")
                && warning.contains("broken") && warning.contains(converter.getID())), warnings.toString());

        assertTrue(animation.playAnimation("broken", false, true));
        animation.tick();
        assertEquals(0, body.getAnimationRotation().x, 1e-6);
        for (int tick = 1; tick <= 10; tick++) animation.tick();
        assertEquals(-Math.toRadians(10), body.getAnimationRotation().x, 1e-6, "0 blends toward the next key");
    }

    private void load(Object... animations) throws Exception {
        Path source = directory.resolve("expression_probe.bbmodel");
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", List.of(),
                "outliner", List.of(Map.of("name", "body", "uuid", "bone-body", "origin", List.of(0, 0, 0),
                        "children", List.of())),
                "animations", List.of(animations))));
        converter = new FileModelConverter(source.toFile());
        ModeledEntity model = new ModeledEntity(converter.getID(), new Location(null, 0, 0, 0));
        animation = model.getAnimationComponent();
        body = model.getSkeleton().getBones().stream()
                .filter(bone -> bone.getBoneBlueprint().getOriginalBoneName().equals("body"))
                .findFirst().orElseThrow();
    }

    /** Routes the plugin logger, which needs a Bukkit server, into a list. */
    private List<String> captureWarnings() {
        if (Bukkit.getServer() == null) {
            Bukkit.setServer((Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                    new Class<?>[]{Server.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getBukkitVersion" -> "26.3-R0.1-SNAPSHOT";
                        case "getLogger" -> java.util.logging.Logger.getLogger("FMM-expression-test");
                        default -> null;
                    }));
            installedServer = true;
        }
        List<String> warnings = new ArrayList<>();
        Bukkit.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return warnings;
    }

    private static Map<String, Object> rotation(double time, Object x) {
        return Map.of("channel", "rotation", "interpolation", "linear", "time", time,
                "data_points", List.of(Map.of("x", x, "y", 0, "z", 0)));
    }

    private static Map<String, Object> animation(String name, List<Map<String, Object>> keyframes) {
        return Map.of("name", name, "loop", "loop", "length", 1.5,
                "animators", Map.of("bone-body", Map.of("name", "body", "type", "bone", "keyframes", keyframes)));
    }
}
