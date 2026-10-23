package com.magmaguy.freeminecraftmodels.customentity.core;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.joml.Quaterniond;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A body tilt turns the whole model in world space about the middle of its height, whatever way it faces. */
class BodyTiltTransformTest {
    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private ModeledEntity model;

    @BeforeEach
    void loadModel() throws Exception {
        previousPlugin = MetadataHandler.PLUGIN;
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "FreeMinecraftModels";
                    throw new UnsupportedOperationException(method.getName());
                });
        Path source = directory.resolve("tilt_probe.bbmodel");
        // No cubes: the model counts as 0 to 2 blocks tall, so it tilts about 1 block above its feet.
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", List.of(),
                "outliner", List.of(Map.of("name", "body", "uuid", "bone-body", "origin", List.of(0, 0, 0),
                        "children", List.of(Map.of("name", "m_top", "uuid", "bone-top", "origin", List.of(0, 32, 0),
                                "children", List.of())))))));
        FileModelConverter converter = new FileModelConverter(source.toFile());
        model = new ModeledEntity(converter.getID(), new Location(null, 10, 64, 20, 30, 0));
    }

    @AfterEach
    void discardModel() {
        FileModelConverter.shutdown();
        MetadataHandler.PLUGIN = previousPlugin;
    }

    private Location top() {
        model.getSkeleton().transformBones();
        return model.getSkeleton().getBones().stream()
                .filter(bone -> bone.getBoneBlueprint().getOriginalBoneName().equals("m_top"))
                .findFirst().orElseThrow().getBoneLocation();
    }

    @Test
    void anUprightModelIsUntouched() {
        assertNull(model.getBodyTilt());
        Location top = top();
        assertEquals(10D, top.getX(), 1e-6);
        assertEquals(66D, top.getY(), 1e-6);
        assertEquals(20D, top.getZ(), 1e-6);
    }

    @Test
    void aTiltTurnsTheModelAboutItsMiddle() {
        assertEquals(1D, model.getBodyTiltPivotHeight(), 1e-9);
        // A quarter turn about +z lays the top over toward -x.
        model.setBodyTilt(new Quaterniond().rotationZ(Math.toRadians(90)));
        Location top = top();
        assertEquals(9D, top.getX(), 1e-6);
        assertEquals(65D, top.getY(), 1e-6);
        assertEquals(20D, top.getZ(), 1e-6);
    }

    @Test
    void aHalfTurnStandsTheModelOnItsHead() {
        model.setBodyTilt(new Quaterniond().rotationX(Math.toRadians(180)));
        Location top = top();
        assertEquals(10D, top.getX(), 1e-6);
        assertEquals(64D, top.getY(), 1e-6);
        assertEquals(20D, top.getZ(), 1e-6);
    }

    @Test
    void clearingTheTiltStandsItUpright() {
        model.setBodyTilt(new Quaterniond().rotationZ(Math.toRadians(90)));
        top();
        model.setBodyTilt(null);
        assertEquals(66D, top().getY(), 1e-6);
    }
}
