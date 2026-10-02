package com.magmaguy.freeminecraftmodels.particles;

import com.google.gson.Gson;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.components.ParticleComponent;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.particles.runtime.ParticleSpriteRenderer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ParticleComponentTest {
    private static final AbstractPacketBundle BUNDLE = new AbstractPacketBundle() {
        @Override
        public void addPacket(Object packet, List<Player> viewers) {
        }

        @Override
        public void send() {
        }
    };
    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private ModeledEntity model;
    private final List<Location> spawned = new ArrayList<>();
    private final List<Location> removed = new ArrayList<>();
    private final List<UUID> viewers = List.of(UUID.randomUUID());

    @BeforeEach
    void loadModel() throws Exception {
        previousPlugin = MetadataHandler.PLUGIN;
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "FreeMinecraftModels";
                    throw new UnsupportedOperationException(method.getName());
                });
        ParticleEffectLibrary.setWarningSink(ignored -> {
        });
        ParticleComponent.setSpriteFactory(location -> {
            spawned.add(location);
            return (PacketModelEntity) Proxy.newProxyInstance(PacketModelEntity.class.getClassLoader(),
                    new Class<?>[]{PacketModelEntity.class}, (proxy, method, args) -> {
                        if (method.getName().equals("remove")) removed.add(location);
                        return method.getReturnType() == boolean.class ? false : null;
                    });
        });
        Files.writeString(directory.resolve("spark.json"), """
                {"particle_effect": {"components": {
                  "minecraft:emitter_rate_steady": {"spawn_rate": 20, "max_particles": 100},
                  "minecraft:emitter_lifetime_looping": {"active_time": 10},
                  "minecraft:particle_lifetime_expression": {"max_lifetime": 10}}}}""");
        Path source = directory.resolve("particle_prop.bbmodel");
        Map<String, Object> keyframe = Map.of("channel", "particle", "time", 0.1, "data_points",
                List.of(Map.of("effect", "spark", "locator", "tip", "script", "", "file", "spark.json")));
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", List.of(Map.of("type", "locator", "uuid", "locator-tip", "name", "tip",
                        "position", List.of(0, 16, 0))),
                "outliner", List.of(Map.of("name", "body", "uuid", "bone-body", "origin", List.of(0, 0, 0),
                        "children", List.of("locator-tip"))),
                "animations", List.of(
                        Map.of("name", "idle", "loop", "loop", "length", 0.5, "animators",
                                Map.of("effects", Map.of("name", "Effects", "type", "effect", "keyframes", List.of(keyframe)))),
                        Map.of("name", "wave", "loop", "loop", "length", 0.5, "animators", Map.of())))));
        FileModelConverter converter = new FileModelConverter(source.toFile());
        model = new ModeledEntity(converter.getID(), new Location(null, 0, 64, 0));
    }

    @AfterEach
    void discardModel() {
        ParticleComponent.setSpriteFactory(location -> {
            throw new IllegalStateException("No NMS in tests");
        });
        ParticleSpriteRenderer.resetViewerCounts();
        ParticleEffectLibrary.setWarningSink(null);
        FileModelConverter.shutdown();
        MetadataHandler.PLUGIN = previousPlugin;
    }

    private void run(int ticks) {
        for (int i = 0; i < ticks; i++) {
            model.getAnimationComponent().tick();
            model.getParticleComponent().tick(BUNDLE, viewers);
        }
    }

    @Test
    void keyframeStartsOneEmitterAtItsFrameAndRestartsItEachLoop() {
        run(2);
        assertEquals(0, model.getParticleComponent().activeEmitterCount(), "The keyframe sits on frame 2");
        run(1);
        assertEquals(1, model.getParticleComponent().activeEmitterCount());
        run(20);
        assertEquals(1, model.getParticleComponent().activeEmitterCount(), "Looping restarts the same emitter");
        assertFalse(spawned.isEmpty());
        assertEquals(65, spawned.getFirst().getY(), 1e-6, "Particles start at the locator, 16 pixels (one block) up");
    }

    @Test
    void changingAnimationStopsALoopingEmitter() {
        run(5);
        int before = spawned.size();
        model.getAnimationComponent().playAnimation("wave", false, true);
        run(10);
        int after = spawned.size();
        run(10);
        assertEquals(after, spawned.size(), "No particles spawn after the idle animation stopped");
        assertTrue(after >= before);
    }

    @Test
    void removingTheModelRemovesEveryParticle() {
        run(10);
        assertFalse(spawned.isEmpty());
        model.getParticleComponent().remove();
        assertEquals(spawned.size(), removed.size());
        assertEquals(0, model.getParticleComponent().activeEmitterCount());
    }

    @Test
    void nobodyWatchingPausesEmitters() {
        run(5);
        int count = spawned.size();
        for (int i = 0; i < 10; i++) {
            model.getAnimationComponent().tick();
            model.getParticleComponent().tick(BUNDLE, List.of());
        }
        assertEquals(count, spawned.size());
    }
}
