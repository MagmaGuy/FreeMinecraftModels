package com.magmaguy.freeminecraftmodels.animation;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.components.AnimationComponent;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Temporary check for the layered animation controller: idle/walk cross-fade with both playing, interrupted fades,
 * the action layer's Bedrock override rule and blend weight, custom-over-built-in priority, and the death fade.
 * Positions are read in blocks (Blockbench pixels / 16) on the y axis, which no format version flips.
 */
@EnabledIfEnvironmentVariable(named = "NIGHTBREAK_TEMP_TICKET_TESTS", matches = "true")
class AnimationBlendingTemporaryTest {
    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private int previousIdleWalk;
    private int previousDeath;
    private FileModelConverter converter;
    private AnimationComponent animation;
    private Bone body;
    private Bone arm;

    @BeforeEach
    void loadModel() throws Exception {
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

        List<Object> animations = List.of(
                // Idle moves the body one pixel per tick, so a fade shows whether idle keeps playing.
                animation("idle", "loop", 1.0, false, "", Map.of(
                        "body", List.of(key(0, 0), key(1.0, 20)), "arm", List.of(key(0, 16)))),
                animation("walk", "loop", 1.0, false, "", Map.of(
                        "body", List.of(key(0, 32)), "arm", List.of(key(0, 32)))),
                animation("attack", "once", 0.5, true, "", Map.of("arm", List.of(key(0, 48)))),
                animation("attack_melee", "once", 0.5, false, "", Map.of("arm", List.of(key(0, 48)))),
                animation("attack_ranged", "once", 0.5, false, "0.5", Map.of("arm", List.of(key(0, 48)))),
                animation("damaged", "once", 0.5, true, "", Map.of("arm", List.of(key(0, 80)))),
                animation("death", "once", 0.5, false, "", Map.of(
                        "body", List.of(key(0, -16)), "arm", List.of(key(0, -16)))),
                animation("sneak", "loop", 0.5, false, "", Map.of("body", List.of(key(0, 8)))),
                animation("jump", "once", 0.2, false, "", Map.of("body", List.of(key(0, 4)))));
        Path source = directory.resolve("blend_probe.bbmodel");
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", List.of(),
                "outliner", List.of(Map.of("name", "body", "uuid", "bone-body", "origin", List.of(0, 0, 0),
                        "children", List.of(Map.of("name", "arm", "uuid", "bone-arm", "origin", List.of(0, 8, 0),
                                "children", List.of())))),
                "animations", animations)));
        converter = new FileModelConverter(source.toFile());
        ModeledEntity model = new ModeledEntity(converter.getID(), new Location(null, 0, 0, 0));
        animation = model.getAnimationComponent();
        body = bone(model, "body");
        arm = bone(model, "arm");
    }

    @AfterEach
    void discardModel() {
        DefaultConfig.idleWalkBlendTicks = previousIdleWalk;
        DefaultConfig.deathBlendTicks = previousDeath;
        FileModelConverter.shutdown();
        MetadataHandler.PLUGIN = previousPlugin;
    }

    @Test
    void idleAndWalkCrossFadeWhileBothPlay() {
        tick(3); // idle frames 0, 1, 2
        assertEquals(idleBody(2), y(body), 1e-5);
        assertTrue(animation.playAnimation("walk", false, true));
        for (int fadeTick = 1; fadeTick <= 5; fadeTick++) {
            tick(1);
            double weight = smoothstep(fadeTick / 5.0);
            // Idle keeps advancing underneath: frame 2 + fadeTick.
            assertEquals(idleBody(2 + fadeTick) + (2 - idleBody(2 + fadeTick)) * weight, y(body), 1e-5,
                    "body at fade tick " + fadeTick);
            assertEquals(1 + weight, y(arm), 1e-5, "arm at fade tick " + fadeTick);
        }
        tick(1);
        assertEquals(2, y(body), 1e-5, "The fade completes on walk");
    }

    @Test
    void aSwitchDuringAFadeStartsFromTheHalfBlendedPose() {
        tick(1);
        animation.playAnimation("walk", false, true);
        tick(2);
        double halfBlended = y(body);
        assertTrue(halfBlended > idleBody(2) && halfBlended < 2, "Mid-fade pose " + halfBlended);
        animation.playAnimation("idle", false, true);
        tick(1);
        // Frozen half-blended pose toward a fresh idle at frame 0, one fade tick in.
        assertEquals(halfBlended + (idleBody(0) - halfBlended) * smoothstep(0.2), y(body), 1e-5);
    }

    @Test
    void actionsReplaceOrAddOnTheBonesTheyKeyByOverrideAndWeight() {
        tick(1);
        animation.playAnimation("attack", false, false);
        tick(1);
        assertEquals(3, y(arm), 1e-5, "Override replaces the keyed arm");
        assertEquals(idleBody(1), y(body), 1e-5, "The unkeyed body keeps playing idle");

        animation.playAnimation("attack_melee", false, false);
        tick(1);
        assertEquals(1 + 3, y(arm), 1e-5, "Without override the attack adds to idle's arm");

        animation.playAnimation("attack_ranged", false, false);
        tick(1);
        assertEquals(1 + 1.5, y(arm), 1e-5, "Blend weight 0.5 halves the added attack");
        tick(10);
        assertEquals(1, y(arm), 1e-5, "The finished attack leaves the base alone");
    }

    @Test
    void customAnimationsOutrankBuiltInsUntilALoopIsReplacedExplicitly() {
        tick(1);
        animation.playAnimation("damaged", false, false);
        tick(1);
        assertEquals(5, y(arm), 1e-5);
        assertTrue(animation.playAnimation("attack", false, false), "An outranked attack is still accepted");
        tick(1);
        assertEquals(5, y(arm), 1e-5, "damaged is custom, so the attack is ignored");

        animation.playAnimation("sneak", false, true);
        tick(1);
        assertEquals(0.5, y(body), 1e-5, "A takeover replaces the base");
        assertEquals(0, y(arm), 1e-5, "and clears the action; the arm it does not key rests");
        animation.playAnimation("attack", false, false);
        tick(1);
        assertEquals(0, y(arm), 1e-5, "Attacks are ignored during a takeover");

        animation.playAnimation("walk", false, true);
        tick(1);
        assertEquals(2, y(body), 1e-5, "An explicit walk replaces a looping takeover at once");

        animation.playAnimation("jump", false, false);
        tick(1);
        animation.playAnimation("idle", false, true);
        tick(1);
        assertEquals(0.25, y(body), 1e-5, "A one-shot takeover finishes before the requested idle");
        tick(2); // jump frames 2, 3
        tick(1);
        assertEquals(idleBody(0), y(body), 1e-5, "Then the requested idle shows");
        assertEquals(1, y(arm), 1e-5);
    }

    @Test
    void deathFadesFromTheHeldPoseAndRefusesLaterRequests() {
        tick(1);
        animation.playAnimation("attack", false, false);
        tick(1);
        double heldBody = y(body);
        assertEquals(3, y(arm), 1e-5);
        assertTrue(animation.playAnimation("death", false, false));
        for (int fadeTick = 1; fadeTick <= 4; fadeTick++) {
            tick(1);
            double weight = smoothstep(fadeTick / 4.0);
            assertEquals(heldBody + (-1 - heldBody) * weight, y(body), 1e-5, "body at fade tick " + fadeTick);
            assertEquals(3 + (-1 - 3) * weight, y(arm), 1e-5, "arm, attack included, at fade tick " + fadeTick);
        }
        assertFalse(animation.playAnimation("idle", false, true));
        assertFalse(animation.playAnimation("attack", false, false));
        assertFalse(animation.playAnimation("sneak", false, true));
        tick(1);
        assertEquals(-1, y(body), 1e-5);
    }

    @Test
    void blockbenchFieldsAreRead() {
        Map<String, AnimationBlueprint> blueprints = converter.getAnimationsBlueprint().getAnimations();
        assertTrue(blueprints.get("attack").isOverride());
        assertFalse(blueprints.get("attack_melee").isOverride());
        assertEquals(0.5, blueprints.get("attack_ranged").getBlendWeight(), 1e-9);
        assertEquals(1, blueprints.get("idle").getBlendWeight(), 1e-9, "Empty weights count as 1");
    }

    private void tick(int ticks) {
        for (int i = 0; i < ticks; i++) animation.tick();
    }

    private static double y(Bone bone) {
        return bone.getAnimationTranslation().y;
    }

    private static double idleBody(int frame) {
        return frame / 16.0;
    }

    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }

    private static Bone bone(ModeledEntity model, String name) {
        return model.getSkeleton().getBones().stream()
                .filter(bone -> bone.getBoneBlueprint().getOriginalBoneName().equals(name))
                .findFirst().orElseThrow();
    }

    private static Map<String, Object> key(double time, double y) {
        return Map.of("channel", "position", "interpolation", "linear", "time", time,
                "data_points", List.of(Map.of("x", 0, "y", y, "z", 0)));
    }

    private static Map<String, Object> animation(String name, String loop, double length, boolean override,
                                                 String blendWeight, Map<String, List<Map<String, Object>>> tracks) {
        Map<String, Object> animators = new HashMap<>();
        tracks.forEach((bone, keys) -> animators.put("bone-" + bone,
                Map.of("name", bone, "type", "bone", "keyframes", new ArrayList<>(keys))));
        Map<String, Object> animation = new HashMap<>();
        animation.put("name", name);
        animation.put("loop", loop);
        animation.put("length", length);
        animation.put("override", override);
        animation.put("blend_weight", blendWeight);
        animation.put("start_delay", "");
        animation.put("loop_delay", "");
        animation.put("anim_time_update", "");
        animation.put("animators", animators);
        return animation;
    }
}
