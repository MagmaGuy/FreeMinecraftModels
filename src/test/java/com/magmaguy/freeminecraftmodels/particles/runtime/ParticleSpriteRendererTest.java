package com.magmaguy.freeminecraftmodels.particles.runtime;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.freeminecraftmodels.particles.ParticleSprites;
import com.magmaguy.freeminecraftmodels.particles.ResolvedParticleEffect;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.SnowstormParser;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Display;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ParticleSpriteRendererTest {
    private static final Quaterniond IDENTITY = new Quaterniond();
    private static final AbstractPacketBundle BUNDLE = new AbstractPacketBundle() {
        @Override
        public void addPacket(Object packet, List<Player> viewers) {
        }

        @Override
        public void send() {
        }
    };
    private final List<FakeSprite> sprites = new ArrayList<>();
    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();

    /** Records the calls a renderer makes on one packet sprite. */
    private static final class FakeSprite {
        final Location spawn;
        final List<String> calls = new ArrayList<>();
        final Set<UUID> viewers = new HashSet<>();
        boolean removed;
        Location lastMove;
        int moves;
        Vector3f lastScale;
        int lastColor = -1;

        FakeSprite(Location spawn) {
            this.spawn = spawn;
        }

        PacketModelEntity proxy() {
            return (PacketModelEntity) Proxy.newProxyInstance(PacketModelEntity.class.getClassLoader(),
                    new Class<?>[]{PacketModelEntity.class}, (proxy, method, args) -> {
                        calls.add(method.getName() + (args == null ? "" : Arrays.toString(args)));
                        switch (method.getName()) {
                            case "displayTo" -> viewers.add((UUID) args[0]);
                            case "hideFrom" -> viewers.remove((UUID) args[0]);
                            case "remove" -> removed = true;
                            case "teleport" -> {
                                lastMove = (Location) args[0];
                                moves++;
                            }
                            case "setTransformation" -> lastScale = (Vector3f) args[2];
                            case "setItemModel" -> lastColor = ((org.bukkit.Color) args[1]).asRGB();
                            case "hasViewers" -> {
                                return !viewers.isEmpty();
                            }
                            default -> {
                            }
                        }
                        return null;
                    });
        }
    }

    private ParticleSpriteRenderer renderer(ParticleEmitter emitter, int maxPerViewer) {
        ParticleEffectBlueprint effect = emitter.effect();
        ParticleSprites spriteSet = ParticleSprites.of("model",
                new ResolvedParticleEffect("spark", effect, new File("spark.json"), null));
        return new ParticleSpriteRenderer(spriteSet, effect, location -> {
            FakeSprite sprite = new FakeSprite(location);
            sprites.add(sprite);
            return sprite.proxy();
        }, maxPerViewer);
    }

    private static ParticleEmitter emitter(String components) {
        ParticleEmitter emitter = new ParticleEmitter(SnowstormParser.parse("""
                {"particle_effect": {"components": {%s}}}""".formatted(components), "spark.json"),
                new SplittableRandom(3), 100);
        emitter.start();
        return emitter;
    }

    private static void step(ParticleEmitter emitter, ParticleSpriteRenderer renderer, Collection<UUID> viewers, int tick) {
        Vector3d position = new Vector3d(10, 64, 10);
        emitter.tick(position, IDENTITY, 1 / 20.0);
        renderer.render(emitter, position, IDENTITY, null, viewers, BUNDLE, tick);
    }

    @AfterEach
    void resetViewerCounts() {
        ParticleSpriteRenderer.resetViewerCounts();
    }

    @Test
    void spawnsAFullBrightBillboardForEachParticleAndViewer() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 2},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 1},
                "minecraft:particle_appearance_billboard": {"size": [0.25, 0.5]},
                "minecraft:particle_appearance_tinting": {"color": "#FF336699"}""");
        ParticleSpriteRenderer renderer = renderer(emitter, 400);
        step(emitter, renderer, List.of(alice, bob), 1);
        assertEquals(2, sprites.size());
        FakeSprite sprite = sprites.getFirst();
        assertEquals(Set.of(alice, bob), sprite.viewers);
        assertEquals(10, sprite.spawn.getX(), 1e-9);
        assertTrue(sprite.calls.indexOf("teleport[" + sprite.spawn + "]") < sprite.calls.indexOf("displayTo[" + alice + "]"),
                "The sprite is placed before it is shown");
        assertTrue(sprite.calls.contains("initializeModel[" + sprite.spawn + ", freeminecraftmodels:particle/model/spark/0]"));
        assertTrue(sprite.calls.contains("setBillboard[" + Display.Billboard.CENTER + "]"));
        assertTrue(sprite.calls.contains("setBrightness[15, 15]"));
        assertEquals(0x336699, sprite.lastColor);
        assertEquals(new Vector3f(0.5f, 1f, 1f), sprite.lastScale, "Size is a half-extent; the quad is one block wide");
    }

    @Test
    void litParticlesKeepWorldLightAndVerticalFacingMapsToVertical() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:particle_appearance_lighting": {},
                "minecraft:particle_appearance_billboard": {"size": [0.1, 0.1], "facing_camera_mode": "rotate_y"}""");
        ParticleSpriteRenderer renderer = renderer(emitter, 400);
        step(emitter, renderer, List.of(alice), 1);
        List<String> calls = sprites.getFirst().calls;
        assertTrue(calls.contains("setBillboard[" + Display.Billboard.VERTICAL + "]"));
        assertTrue(calls.stream().noneMatch(call -> call.startsWith("setBrightness")));
    }

    @Test
    void movingParticlesGlideAndStillParticlesStayQuiet() {
        ParticleEmitter moving = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:emitter_shape_point": {"direction": [0, 1, 0]},
                "minecraft:particle_initial_speed": 2,
                "minecraft:particle_motion_dynamic": {},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 5}""");
        ParticleSpriteRenderer renderer = renderer(moving, 400);
        for (int tick = 1; tick <= 5; tick++) step(moving, renderer, List.of(alice), tick);
        FakeSprite sprite = sprites.getFirst();
        assertNotNull(sprite.lastMove);
        assertTrue(sprite.lastMove.getY() > 64.2, "position " + sprite.lastMove);

        sprites.clear();
        ParticleEmitter still = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 5}""");
        ParticleSpriteRenderer quiet = renderer(still, 400);
        for (int tick = 1; tick <= 6; tick++) step(still, quiet, List.of(alice), tick);
        assertEquals(1, sprites.getFirst().moves, "Only the placement before the first display");
        assertEquals(sprites.getFirst().spawn, sprites.getFirst().lastMove);
        assertTrue(sprites.getFirst().calls.stream().noneMatch(call -> call.startsWith("queueMetadataUpdate")));
    }

    @Test
    void expiredParticlesAreRemoved() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 1},
                "minecraft:emitter_lifetime_once": {"active_time": 0.05},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 0.2}""");
        ParticleSpriteRenderer renderer = renderer(emitter, 400);
        for (int tick = 1; tick <= 8; tick++) step(emitter, renderer, List.of(alice), tick);
        assertTrue(sprites.getFirst().removed);
        assertTrue(renderer.isEmpty());
        assertTrue(emitter.isFinished());
    }

    @Test
    void perViewerCapSkipsNewParticlesForThatViewerOnly() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 5},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 10}""");
        ParticleSpriteRenderer renderer = renderer(emitter, 3);
        step(emitter, renderer, List.of(alice), 1);
        assertEquals(3, sprites.stream().filter(sprite -> sprite.viewers.contains(alice)).count());
    }

    @Test
    void hideFromAndRemoveAllClearViewers() {
        ParticleEmitter emitter = emitter("""
                "minecraft:emitter_rate_instant": {"num_particles": 2},
                "minecraft:particle_lifetime_expression": {"max_lifetime": 10}""");
        ParticleSpriteRenderer renderer = renderer(emitter, 400);
        step(emitter, renderer, List.of(alice, bob), 1);
        renderer.hideFrom(alice);
        assertTrue(sprites.stream().noneMatch(sprite -> sprite.viewers.contains(alice)));
        renderer.removeAll();
        assertTrue(sprites.stream().allMatch(sprite -> sprite.removed));
        assertTrue(renderer.isEmpty());
    }
}
