package com.magmaguy.freeminecraftmodels.particles.runtime;

import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.SnowstormParser;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class ParticleEmitterTest {
    private static final double TICK = 1 / 20.0;
    private static final Quaterniond IDENTITY = new Quaterniond();

    private static ParticleEmitter emitter(String components) {
        ParticleEffectBlueprint effect = SnowstormParser.parse("""
                {"particle_effect": {"description": {"identifier": "fmm:test"}, "components": {%s}}}"""
                .formatted(components), "test.json");
        ParticleEmitter emitter = new ParticleEmitter(effect, new SplittableRandom(5), 1000);
        emitter.start();
        return emitter;
    }

    private static void run(ParticleEmitter emitter, int ticks) {
        for (int i = 0; i < ticks; i++) emitter.tick(new Vector3d(), IDENTITY, TICK);
    }

    @Test
    void steadyRateSpawnsItsAverageCount() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_steady": {"spawn_rate": 10, "max_particles": 100},
                "minecraft:emitter_lifetime_looping": {"active_time": 100},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 100}""");
        run(emitter, 20);
        assertEquals(10, emitter.particles().size());
        assertEquals(10, emitter.spawnedCount());
    }

    @Test
    void maxParticlesCapsLiveParticles() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_steady": {"spawn_rate": 100, "max_particles": 7},
                "minecraft:emitter_lifetime_looping": {"active_time": 100},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 100}""");
        run(emitter, 20);
        assertEquals(7, emitter.particles().size());
    }

    @Test
    void emitterCapOverridesTheFile() {
        ParticleEffectBlueprint effect = SnowstormParser.parse("""
                {"particle_effect": {"components": {
                  "minecraft:emitter_rate_steady": {"spawn_rate": 100, "max_particles": 400},
                  "minecraft:emitter_lifetime_looping": {"active_time": 100},
                  "minecraft:particle_lifetime_expression": {"max_lifetime": 100}}}}""", "cap.json");
        ParticleEmitter emitter = new ParticleEmitter(effect, new SplittableRandom(5), 25);
        emitter.start();
        run(emitter, 40);
        assertEquals(25, emitter.particles().size());
    }

    @Test
    void instantOnceEmitterBurstsOnceThenFinishes() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 5},
                "minecraft:emitter_lifetime_once": {"active_time": 1},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 0.5}""");
        run(emitter, 1);
        assertEquals(5, emitter.particles().size());
        run(emitter, 30);
        assertEquals(5, emitter.spawnedCount());
        assertTrue(emitter.isFinished());
    }

    @Test
    void onceLifetimeStopsSteadySpawning() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_steady": {"spawn_rate": 20, "max_particles": 100},
                "minecraft:emitter_lifetime_once": {"active_time": 1},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 100}""");
        run(emitter, 40);
        assertEquals(20, emitter.spawnedCount());
    }

    @Test
    void particlesExpireAtTheirLifetime() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:emitter_lifetime_once": {"active_time": 0.05},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 0.5}""");
        run(emitter, 8);
        assertEquals(1, emitter.particles().size());
        run(emitter, 4);
        assertEquals(0, emitter.particles().size());
        assertEquals(1, emitter.drainExpired().size());
    }

    @Test
    void dynamicMotionFollowsWinterskyIntegration() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:emitter_lifetime_once": {"active_time": 1},
                "minecraft:emitter_shape_point": {"direction": [1, 0, 0]},
                "minecraft:particle_initial_speed": 2,
                "minecraft:particle_motion_dynamic": {"linear_acceleration": [0, -10, 0], "linear_drag_coefficient": 0.5},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 10}""");
        run(emitter, 1);
        SimulatedParticle particle = emitter.particles().getFirst();
        Vector3d position = particle.worldPosition(new Vector3d(), IDENTITY, new Vector3d());
        assertEquals(0.0975, position.x, 1e-9);
        assertEquals(-0.025, position.y, 1e-9);
        run(emitter, 1);
        position = particle.worldPosition(new Vector3d(), IDENTITY, new Vector3d());
        assertEquals(0.1925625, position.x, 1e-9);
        assertEquals(-0.074375, position.y, 1e-9);
    }

    @Test
    void withoutMotionComponentParticlesStayPut() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:emitter_shape_point": {"direction": [1, 0, 0]},
                "minecraft:particle_initial_speed": 5""");
        run(emitter, 5);
        Vector3d position = emitter.particles().getFirst().worldPosition(new Vector3d(), IDENTITY, new Vector3d());
        assertEquals(0, position.length(), 1e-12);
    }

    @Test
    void worldSpaceParticlesStayBehindAndLocalSpaceParticlesFollow() {
        String components = """
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 10}""";
        ParticleEmitter world = emitter(components);
        ParticleEmitter local = emitter(components + ",\"minecraft:emitter_local_space\": {\"position\": true}");
        world.tick(new Vector3d(1, 0, 0), IDENTITY, TICK);
        local.tick(new Vector3d(1, 0, 0), IDENTITY, TICK);
        Vector3d moved = new Vector3d(5, 0, 0);
        world.tick(moved, IDENTITY, TICK);
        local.tick(moved, IDENTITY, TICK);
        assertEquals(1, world.particles().getFirst().worldPosition(moved, IDENTITY, new Vector3d()).x, 1e-12);
        assertEquals(5, local.particles().getFirst().worldPosition(moved, IDENTITY, new Vector3d()).x, 1e-12);
    }

    @Test
    void shapeOffsetAndDirectionFollowTheEmitterRotation() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:emitter_shape_point": {"offset": [1, 0, 0], "direction": [1, 0, 0]},
                "minecraft:particle_initial_speed": 1,
                "minecraft:particle_motion_dynamic": {},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 10}""");
        Quaterniond quarterTurn = new Quaterniond().rotateY(Math.toRadians(90));
        emitter.tick(new Vector3d(), quarterTurn, TICK);
        Vector3d position = emitter.particles().getFirst().worldPosition(new Vector3d(), quarterTurn, new Vector3d());
        assertEquals(0, position.x, 1e-9);
        assertEquals(-1.05, position.z, 1e-9, "Offset and velocity both rotate a quarter turn around Y");
    }

    @Test
    void spinIntegratesRotationRate() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:particle_initial_spin": {"rotation": 10, "rotation_rate": 90},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 10}""");
        run(emitter, 1);
        assertEquals(14.5, emitter.particles().getFirst().rotationDegrees(), 1e-9);
        run(emitter, 1);
        assertEquals(19, emitter.particles().getFirst().rotationDegrees(), 1e-9);
    }

    @Test
    void stretchedFlipbookSpreadsFramesOverTheLifetime() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 1},
                "minecraft:particle_appearance_billboard": {"size": [0.1, 0.1], "uv": {"texture_width": 64, "texture_height": 8,
                  "flipbook": {"base_UV": [0, 0], "size_UV": [8, 8], "step_UV": [8, 0], "max_frame": 8, "stretch_to_lifetime": true}}}""");
        run(emitter, 1);
        SimulatedParticle particle = emitter.particles().getFirst();
        assertEquals(0, particle.frame());
        run(emitter, 4);
        assertEquals(2, particle.frame(), "age 0.25s at 8 fps");
        run(emitter, 14);
        assertEquals(7, particle.frame(), "Frames stop at the last one without loop");
    }

    @Test
    void loopingFlipbookWraps() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 10},
                "minecraft:particle_appearance_billboard": {"size": [0.1, 0.1], "uv": {"texture_width": 32, "texture_height": 8,
                  "flipbook": {"base_UV": [0, 0], "size_UV": [8, 8], "step_UV": [8, 0], "frames_per_second": 20, "max_frame": 4, "loop": true}}}""");
        run(emitter, 6);
        assertTrue(emitter.particles().getFirst().frame() < 4);
    }

    @Test
    void gradientTintFollowsTheInterpolant() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 1},
                "minecraft:particle_appearance_tinting": {"color": {"interpolant": "v.particle_age / v.particle_lifetime",
                  "gradient": {"0.0": "#FF000000", "1.0": "#FFFFFFFF"}}}""");
        run(emitter, 1);
        SimulatedParticle particle = emitter.particles().getFirst();
        assertEquals(0xFF0D0D0D, particle.color(), "age 0.05 is 5% of the way to white");
        run(emitter, 9);
        assertEquals(0xFF808080, particle.color());
    }

    @Test
    void stopSpawningEndsALoopingEmitterOnceItsParticlesDie() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_steady": {"spawn_rate": 20, "max_particles": 100},
                "minecraft:emitter_lifetime_looping": {"active_time": 1},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 0.25}""");
        run(emitter, 10);
        emitter.stopSpawning();
        int spawned = emitter.spawnedCount();
        run(emitter, 10);
        assertEquals(spawned, emitter.spawnedCount());
        assertTrue(emitter.isFinished());
    }

    @Test
    void loopingEmitterRestartsAfterSleeping() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 2},
                "minecraft:emitter_lifetime_looping": {"active_time": 0.5, "sleep_time": 0.5},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 0.1}""");
        run(emitter, 25);
        assertEquals(4, emitter.spawnedCount(), "One burst at start and one after active plus sleep time");
    }

    @Test
    void curvesResolveInTheParticleContext() {
        // Curves live beside components, so build this effect directly.
        ParticleEffectBlueprint effect = SnowstormParser.parse("""
                {"particle_effect": {"curves": {"variable.grow": {"type": "linear", "input": "v.particle_age",
                    "horizontal_range": 1, "nodes": [0, 1]}},
                  "components": {"minecraft:emitter_rate_instant": {"num_particles": 1},
                    "minecraft:particle_lifetime_expression": {"max_lifetime": 1},
                    "minecraft:particle_appearance_billboard": {"size": ["v.grow", "v.grow"]}}}}""", "curve.json");
        ParticleEmitter emitter = new ParticleEmitter(effect, new SplittableRandom(5), 10);
        emitter.start();
        run(emitter, 10);
        assertEquals(0.5, emitter.particles().getFirst().width(), 1e-9);
    }
}
