package com.magmaguy.freeminecraftmodels.particles.snowstorm;

import com.magmaguy.freeminecraftmodels.particles.molang.MolangContext;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint.*;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class SnowstormParserTest {
    private static final MolangContext CONTEXT = new MolangContext(new SplittableRandom(1));

    private static ParticleEffectBlueprint parse(String components) {
        return SnowstormParser.parse("""
                {"format_version": "1.10.0", "particle_effect": {
                  "description": {"identifier": "fmm:test", "basic_render_parameters": {
                    "material": "particles_blend", "texture": "textures/particle/sparkle"}},
                  "components": {%s}}}""".formatted(components), "test.json");
    }

    @Test
    void readsDescriptionAndSteadyRate() {
        ParticleEffectBlueprint effect = parse("""
                "minecraft:emitter_rate_steady": {"spawn_rate": 10, "max_particles": "5 * 2"},
                "minecraft:emitter_lifetime_once": {"active_time": 2}""");
        assertEquals("fmm:test", effect.identifier());
        assertEquals("textures/particle/sparkle", effect.texturePath());
        assertEquals("particles_blend", effect.material());
        Rate.Steady steady = assertInstanceOf(Rate.Steady.class, effect.emitter().rate());
        assertEquals(10, steady.rate().evaluate(CONTEXT));
        assertEquals(10, steady.max().evaluate(CONTEXT));
        Lifetime.Once once = assertInstanceOf(Lifetime.Once.class, effect.emitter().lifetime());
        assertEquals(2, once.active().evaluate(CONTEXT));
    }

    @Test
    void absentComponentsUseBlockbenchPreviewDefaults() {
        ParticleEffectBlueprint effect = parse("");
        Rate.Steady steady = assertInstanceOf(Rate.Steady.class, effect.emitter().rate());
        assertEquals(4, steady.rate().evaluate(CONTEXT));
        assertEquals(100, steady.max().evaluate(CONTEXT));
        assertInstanceOf(Lifetime.Looping.class, effect.emitter().lifetime());
        assertEquals(ShapeType.POINT, effect.emitter().shape().type());
        assertFalse(effect.particle().dynamicMotion(), "No motion component means particles do not move");
        assertEquals(FacingMode.ROTATE_XYZ, effect.particle().billboard().facing());
        assertEquals(0xFFFFFFFF, ((Tint.Static) effect.particle().tint()).argb());
        assertFalse(effect.particle().lit());
    }

    @Test
    void readsInstantRateLoopingLifetimeAndShapes() {
        ParticleEffectBlueprint sphere = parse("""
                "minecraft:emitter_rate_instant": {"num_particles": 5},
                "minecraft:emitter_lifetime_looping": {"active_time": 1, "sleep_time": 0.5},
                "minecraft:emitter_shape_sphere": {"offset": [0, 1, 0], "radius": 2, "surface_only": true, "direction": "inwards"}""");
        assertEquals(5, ((Rate.Instant) sphere.emitter().rate()).count().evaluate(CONTEXT));
        Lifetime.Looping looping = (Lifetime.Looping) sphere.emitter().lifetime();
        assertEquals(0.5, looping.sleep().evaluate(CONTEXT));
        Shape shape = sphere.emitter().shape();
        assertEquals(ShapeType.SPHERE, shape.type());
        assertEquals(1, shape.offset().y().evaluate(CONTEXT));
        assertEquals(2, shape.radius().evaluate(CONTEXT));
        assertTrue(shape.surfaceOnly());
        assertEquals(DirectionMode.INWARDS, shape.directionMode());

        Shape box = parse("""
                "minecraft:emitter_shape_box": {"half_dimensions": [1, 2, 3], "direction": [0, 1, 0]}""")
                .emitter().shape();
        assertEquals(ShapeType.BOX, box.type());
        assertEquals(3, box.halfDimensions().z().evaluate(CONTEXT));
        assertEquals(DirectionMode.VECTOR, box.directionMode());
        assertEquals(1, box.direction().y().evaluate(CONTEXT));

        Shape disc = parse("""
                "minecraft:emitter_shape_disc": {"radius": 1, "plane_normal": "x"}""").emitter().shape();
        assertEquals(ShapeType.DISC, disc.type());
        assertEquals(1, disc.planeNormal().x().evaluate(CONTEXT));
        assertEquals(DirectionMode.OUTWARDS, disc.directionMode());
    }

    @Test
    void readsMotionSpinLifetimeAndSpeed() {
        Particle particle = parse("""
                "minecraft:particle_initial_speed": 3,
                "minecraft:particle_initial_spin": {"rotation": 45, "rotation_rate": 90},
                "minecraft:particle_motion_dynamic": {"linear_acceleration": [0, -9.8, 0], "linear_drag_coefficient": 0.5,
                  "rotation_acceleration": 10, "rotation_drag_coefficient": 0.1},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 2, "expiration_expression": "v.particle_age > 1"}""")
                .particle();
        assertEquals(3, particle.linearSpeed().evaluate(CONTEXT));
        assertNull(particle.velocity());
        assertEquals(45, particle.initialRotation().evaluate(CONTEXT));
        assertEquals(90, particle.rotationRate().evaluate(CONTEXT));
        assertTrue(particle.dynamicMotion());
        assertEquals(-9.8, particle.linearAcceleration().y().evaluate(CONTEXT), 1e-9);
        assertEquals(0.5, particle.linearDrag().evaluate(CONTEXT));
        assertEquals(10, particle.rotationAcceleration().evaluate(CONTEXT));
        assertEquals(2, particle.maxLifetime().evaluate(CONTEXT));

        Particle vectorSpeed = parse("""
                "minecraft:particle_initial_speed": [1, 2, 3]""").particle();
        assertEquals(2, vectorSpeed.velocity().y().evaluate(CONTEXT));
    }

    @Test
    void readsBillboardUvAndFlipbook() {
        Billboard billboard = parse("""
                "minecraft:particle_appearance_billboard": {"size": [0.25, "0.5"], "facing_camera_mode": "lookat_y",
                  "uv": {"texture_width": 32, "texture_height": 64, "flipbook": {"base_UV": [0, 8], "size_UV": [8, 8],
                    "step_UV": [8, 0], "frames_per_second": 10, "max_frame": 4, "stretch_to_lifetime": true, "loop": false}}}""")
                .particle().billboard();
        assertEquals(0.25, billboard.width().evaluate(CONTEXT));
        assertEquals(0.5, billboard.height().evaluate(CONTEXT));
        assertEquals(FacingMode.LOOKAT_Y, billboard.facing());
        assertEquals(32, billboard.textureWidth());
        assertEquals(64, billboard.textureHeight());
        TextureRegion flipbook = billboard.region();
        assertEquals(4, flipbook.frames());
        assertEquals(8, flipbook.v().evaluate(CONTEXT));
        assertEquals(8, flipbook.stepU());
        assertEquals(10, flipbook.framesPerSecond().evaluate(CONTEXT));
        assertTrue(flipbook.stretchToLifetime());
        assertFalse(flipbook.loop());
        assertEquals(4, billboard.frameCount());

        Billboard staticUv = parse("""
                "minecraft:particle_appearance_billboard": {"size": [0.1, 0.1],
                  "uv": {"texture_width": 16, "texture_height": 16, "uv": [4, 4], "uv_size": [8, 8]}}""")
                .particle().billboard();
        TextureRegion region = staticUv.region();
        assertEquals(4, region.u().evaluate(CONTEXT));
        assertEquals(8, region.width());
        assertEquals(1, staticUv.frameCount());

        assertNull(parse("""
                "minecraft:particle_appearance_billboard": {"size": [0.1, 0.1]}""").particle().billboard().region(),
                "No uv block means the whole texture");
    }

    @Test
    void parsesDalisWandParticle() throws Exception {
        String json = new String(getClass().getResourceAsStream("/particles/particle_wand.json").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        ParticleEffectBlueprint effect = SnowstormParser.parse(json, "particle_wand.json");
        assertEquals("", effect.identifier());
        assertEquals("textures/blocks/wool_colored_white", effect.texturePath());
        assertEquals(30, ((Rate.Steady) effect.emitter().rate()).rate().evaluate(CONTEXT));
        assertEquals(DirectionMode.INWARDS, effect.emitter().shape().directionMode());
        assertTrue(effect.particle().dynamicMotion());
        assertTrue(effect.particle().lit());
        assertEquals(java.util.List.of("minecraft:particle_motion_collision"), effect.unsupportedComponents());
        TextureRegion region = effect.particle().billboard().region();
        assertEquals(8, region.frames());
        MolangContext particle = new MolangContext(new SplittableRandom(1));
        particle.set("variable.particle_random_3", 0.75);
        assertEquals(8, region.u().evaluate(particle), "Random UV variants stay expressions");
        assertInstanceOf(Tint.Gradient.class, effect.particle().tint());
    }

    @Test
    void readsTintingFormats() {
        assertEquals(0xFF336699, ((Tint.Static) parse("""
                "minecraft:particle_appearance_tinting": {"color": "#336699"}""").particle().tint()).argb());
        assertEquals(0x80336699, ((Tint.Static) parse("""
                "minecraft:particle_appearance_tinting": {"color": "#80336699"}""").particle().tint()).argb());
        assertEquals(0xFFFF0000, ((Tint.Static) parse("""
                "minecraft:particle_appearance_tinting": {"color": [1, 0, 0, 1]}""").particle().tint()).argb());
        assertInstanceOf(Tint.Expression.class, parse("""
                "minecraft:particle_appearance_tinting": {"color": ["v.particle_random_1", 0, 0, 1]}""").particle().tint());

        Tint.Gradient mapped = (Tint.Gradient) parse("""
                "minecraft:particle_appearance_tinting": {"color": {"interpolant": "v.particle_age", "gradient":
                  {"0.0": "#FF0000", "2.0": "#0000FF"}}}""").particle().tint();
        assertEquals(2, mapped.stops().size());
        assertEquals(1.0, mapped.stops().get(1).position());
        assertEquals(2.0, mapped.range());

        Tint.Gradient array = (Tint.Gradient) parse("""
                "minecraft:particle_appearance_tinting": {"color": {"interpolant": "v.particle_age",
                  "gradient": ["#FF0000", "#00FF00", "#0000FF"]}}""").particle().tint();
        assertEquals(0.5, array.stops().get(1).position());
        assertEquals(1.0, array.range());

        assertTrue(parse("\"minecraft:particle_appearance_lighting\": {}").particle().lit());
    }

    @Test
    void reportsUnsupportedComponentsAndStillParses() {
        ParticleEffectBlueprint effect = parse("""
                "minecraft:particle_motion_collision": {"collision_radius": 0.1},
                "minecraft:emitter_rate_steady": {"spawn_rate": 2, "max_particles": 3}""");
        assertEquals(java.util.List.of("minecraft:particle_motion_collision"), effect.unsupportedComponents());
        assertInstanceOf(Rate.Steady.class, effect.emitter().rate());
    }

    @Test
    void readsInitializationAndLocalSpace() {
        ParticleEffectBlueprint effect = parse("""
                "minecraft:emitter_initialization": {"creation_expression": "v.size = 2;", "per_update_expression": "v.t = v.emitter_age;"},
                "minecraft:emitter_local_space": {"position": true, "rotation": false},
                "minecraft:particle_initialization": {"per_update_expression": "v.p = 1;"}""");
        MolangContext context = new MolangContext(new SplittableRandom(1));
        effect.emitter().creation().evaluate(context);
        assertEquals(2, context.get("variable.size"));
        assertTrue(effect.emitter().localPosition());
        assertFalse(effect.emitter().localRotation());
        effect.particle().perUpdate().evaluate(context);
        assertEquals(1, context.get("variable.p"));
    }

    @Test
    void readsCurves() {
        ParticleEffectBlueprint effect = SnowstormParser.parse("""
                {"particle_effect": {"description": {"identifier": "fmm:curve"},
                  "curves": {"variable.ramp": {"type": "linear", "input": "v.particle_age", "horizontal_range": 1,
                    "nodes": [0, 10]}},
                  "components": {}}}""", "curve.json");
        Curve curve = effect.curves().get("variable.ramp");
        assertEquals(CurveType.LINEAR, curve.type());
        MolangContext context = new MolangContext(new SplittableRandom(1));
        context.set("variable.particle_age", 0.25);
        assertEquals(2.5, curve.evaluate(context), 1e-9);
    }

    @Test
    void rejectsFilesWithoutAParticleEffect() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SnowstormParser.parse("{\"format_version\": \"1.10.0\"}", "broken.json"));
        assertTrue(failure.getMessage().contains("broken.json"));
    }
}
