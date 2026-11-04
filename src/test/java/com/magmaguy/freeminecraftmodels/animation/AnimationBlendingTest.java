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
 * The layered animation controller: idle/walk cross-fade with both playing, interrupted fades, the action layer's
 * Bedrock override rule and blend weight, custom-over-built-in priority, and the death fade.
 * Positions are read in blocks (Blockbench pixels / 16) on the y axis, which no format version flips.
 */
class AnimationBlendingTest {
    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private int previousIdleWalk;
    private int previousDeath;
    private int previousLongIdleMin;
    private int previousLongIdleMax;
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
        previousLongIdleMin = DefaultConfig.longIdleMinSeconds;
        previousLongIdleMax = DefaultConfig.longIdleMaxSeconds;
        DefaultConfig.idleWalkBlendTicks = 5;
        DefaultConfig.deathBlendTicks = 4;
        DefaultConfig.longIdleMinSeconds = 0;
        DefaultConfig.longIdleMaxSeconds = 0;

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
                animation("jump", "once", 0.2, false, "", Map.of("body", List.of(key(0, 4)))),
                // Marked as a loop in Blockbench, as Dali's are; it still plays once.
                animation("long_idle", "loop", 0.5, false, "", Map.of("body", List.of(key(0, 64)))),
                // What the swim tests set as a swimming mob's animation.
                animation("slide", "loop", 0.5, false, "", Map.of("body", List.of(key(0, 24)))));
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
        DefaultConfig.longIdleMinSeconds = previousLongIdleMin;
        DefaultConfig.longIdleMaxSeconds = previousLongIdleMax;
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
    void aCustomActionOutranksBuiltInAttacks() {
        tick(1);
        animation.playAnimation("damaged", false, false);
        tick(1);
        assertEquals(5, y(arm), 1e-5);
        assertTrue(animation.playAnimation("attack", false, false), "An outranked attack is still accepted");
        tick(1);
        assertEquals(5, y(arm), 1e-5, "damaged is custom, so the attack is ignored");
    }

    @Test
    void aTakeoverReplacesTheBaseAndEndsTheAction() {
        tick(1);
        animation.playAnimation("damaged", false, false);
        tick(1);
        animation.playAnimation("sneak", false, true);
        tick(1);
        assertEquals(0.5, y(body), 1e-5, "A takeover replaces the base");
        assertEquals(0, y(arm), 1e-5, "and ends the action; the arm it does not key rests");
    }

    @Test
    void attacksAreIgnoredDuringATakeover() {
        animation.playAnimation("sneak", false, true);
        tick(1);
        animation.playAnimation("attack", false, false);
        tick(1);
        assertEquals(0, y(arm), 1e-5);
    }

    @Test
    void anExplicitWalkReplacesALoopingTakeoverAtOnce() {
        animation.playAnimation("sneak", false, true);
        tick(1);
        assertEquals(0.5, y(body), 1e-5);
        animation.playAnimation("walk", false, true);
        tick(1);
        assertEquals(2, y(body), 1e-5);
    }

    @Test
    void aOneShotTakeoverFinishesBeforeTheRequestedIdle() {
        animation.playAnimation("walk", false, true);
        tick(5);
        assertEquals(2, y(body), 1e-5, "Walking before the jump");
        animation.playAnimation("jump", false, false);
        tick(1);
        animation.playAnimation("idle", false, true);
        tick(1);
        assertEquals(0.25, y(body), 1e-5, "The jump keeps playing");
        tick(2); // jump frames 2, 3
        tick(1);
        assertEquals(idleBody(0), y(body), 1e-5, "Then the requested idle shows, not the walk");
        assertEquals(1, y(arm), 1e-5);
    }

    @Test
    void deathFadesFromTheHeldPoseActionIncluded() {
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
    }

    @Test
    void deathRefusesLaterRequests() {
        assertTrue(animation.playAnimation("death", false, false));
        assertFalse(animation.playAnimation("idle", false, true));
        assertFalse(animation.playAnimation("attack", false, false));
        assertFalse(animation.playAnimation("sneak", false, true));
        tick(5);
        assertEquals(-1, y(body), 1e-5, "Death shows, not the refused sneak");
        assertEquals(-1, y(arm), 1e-5, "nor the refused attack");
    }

    @Test
    void longIdlePlaysOnceAfterAQuietSpellThenIdleReturns() {
        DefaultConfig.idleWalkBlendTicks = 0;
        DefaultConfig.longIdleMinSeconds = 1;
        DefaultConfig.longIdleMaxSeconds = 1;
        tick(20);
        assertEquals(idleBody(19), y(body), 1e-5, "Idle for a second: 20 frames");
        tick(1);
        assertEquals(LONG_IDLE_BODY, y(body), 1e-5, "then long_idle");
        tick(9);
        assertEquals(LONG_IDLE_BODY, y(body), 1e-5, "to its last frame");
        tick(1);
        assertEquals(idleBody(0), y(body), 1e-5, "then idle again from its start, not a second long_idle");
        tick(19);
        assertEquals(idleBody(19), y(body), 1e-5);
        tick(1);
        assertEquals(LONG_IDLE_BODY, y(body), 1e-5, "and another quiet second brings it back");
    }

    @Test
    void longIdleFadesInAndOutLikeIdleAndWalk() {
        DefaultConfig.longIdleMinSeconds = 1;
        DefaultConfig.longIdleMaxSeconds = 1;
        tick(21);
        // Idle keeps playing underneath, at its frame 20 % 20.
        assertEquals(idleBody(0) + (LONG_IDLE_BODY - idleBody(0)) * smoothstep(0.2), y(body), 1e-5);
    }

    @Test
    void anAttackRestartsTheQuietSpell() {
        DefaultConfig.idleWalkBlendTicks = 0;
        DefaultConfig.longIdleMinSeconds = 1;
        DefaultConfig.longIdleMaxSeconds = 1;
        tick(15);
        animation.playAnimation("attack", false, false);
        tick(15);
        assertNotEquals(LONG_IDLE_BODY, y(body), 1e-5, "no long_idle 30 ticks in: the attack restarted the wait");
        tick(20);
        assertEquals(LONG_IDLE_BODY, y(body), 1e-5, "a full quiet second after the attack brings it");
    }

    @Test
    void aTakeoverDuringLongIdleReturnsToIdle() {
        DefaultConfig.idleWalkBlendTicks = 0;
        DefaultConfig.longIdleMinSeconds = 1;
        DefaultConfig.longIdleMaxSeconds = 1;
        tick(21);
        assertEquals(LONG_IDLE_BODY, y(body), 1e-5);
        animation.playAnimation("jump", false, false);
        tick(1);
        assertEquals(0.25, y(body), 1e-5, "The jump takes over");
        tick(4);
        assertEquals(idleBody(0), y(body), 1e-5, "and idle follows it, not the cut-off long_idle");
    }

    @Test
    void noLongIdleWhenItIsTurnedOff() {
        DefaultConfig.idleWalkBlendTicks = 0;
        for (int i = 0; i < 200; i++) {
            tick(1);
            assertNotEquals(LONG_IDLE_BODY, y(body), 1e-5, "tick " + i);
        }
    }

    @Test
    void aSwimmingMobPlaysItsSwimAnimationInPlaceOfIdleAndIdlesAgainAshore() {
        DefaultConfig.idleWalkBlendTicks = 0;
        animation.setSwimAnimation("slide");
        tick(3);
        assertEquals(idleBody(2), y(body), 1e-5, "On land it idles");
        animation.setSwimming(true);
        tick(1);
        assertEquals(SLIDE_BODY, y(body), 1e-5, "In water it slides");
        tick(10);
        assertEquals(SLIDE_BODY, y(body), 1e-5, "and keeps sliding while it swims");
        animation.setSwimming(false);
        tick(1);
        assertEquals(idleBody(0), y(body), 1e-5, "Out of the water it idles again");
    }

    @Test
    void swimmingCrossFadesLikeIdleAndWalk() {
        animation.setSwimAnimation("slide");
        tick(1);
        animation.setSwimming(true);
        tick(1);
        assertEquals(idleBody(1) + (SLIDE_BODY - idleBody(1)) * smoothstep(0.2), y(body), 1e-5);
    }

    @Test
    void aSwimmingMobWithoutTheSwimAnimationKeepsIdling() {
        DefaultConfig.idleWalkBlendTicks = 0;
        animation.setSwimAnimation("paddle");
        animation.setSwimming(true);
        tick(3);
        assertEquals(idleBody(2), y(body), 1e-5, "The model has no paddle, so it idles in water too");
    }

    @Test
    void clearingTheSwimAnimationMidSwimIdlesAgain() {
        DefaultConfig.idleWalkBlendTicks = 0;
        animation.setSwimAnimation("slide");
        animation.setSwimming(true);
        tick(2);
        assertEquals(SLIDE_BODY, y(body), 1e-5);
        animation.setSwimAnimation(null);
        tick(1);
        assertEquals(idleBody(0), y(body), 1e-5);
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

    private static final double LONG_IDLE_BODY = 4;
    private static final double SLIDE_BODY = 1.5;

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
