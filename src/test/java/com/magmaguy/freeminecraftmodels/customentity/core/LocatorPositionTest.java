package com.magmaguy.freeminecraftmodels.customentity.core;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.LocatorBlueprint;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.joml.Quaterniond;
import org.joml.Vector3d;
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

class LocatorPositionTest {
    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private ModeledEntity model;
    private LocatorBlueprint tip;

    @BeforeEach
    void loadModel() throws Exception {
        previousPlugin = MetadataHandler.PLUGIN;
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "FreeMinecraftModels";
                    throw new UnsupportedOperationException(method.getName());
                });
        Path source = directory.resolve("locator_probe.bbmodel");
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", List.of(Map.of("type", "locator", "uuid", "locator-tip", "name", "tip",
                        "position", List.of(0, 8, 16))),
                "outliner", List.of(Map.of("name", "wand", "uuid", "bone-wand", "origin", List.of(0, 8, 0),
                        "children", List.of("locator-tip",
                                Map.of("name", "m_probe", "uuid", "bone-probe", "origin", List.of(0, 8, 16),
                                        "children", List.of())))),
                "animations", List.of(Map.of("name", "tilt", "loop", "loop", "length", 0.5, "animators",
                        Map.of("bone-wand", Map.of("name", "wand", "type", "bone", "keyframes", List.of(
                                Map.of("channel", "rotation", "interpolation", "linear", "time", 0,
                                        "data_points", List.of(Map.of("x", 90, "y", 0, "z", 0)))))))))));
        FileModelConverter converter = new FileModelConverter(source.toFile());
        model = new ModeledEntity(converter.getID(), new Location(null, 10, 64, 20, 30, 0));
        tip = converter.getSkeletonBlueprint().getLocatorByName("tip");
    }

    @AfterEach
    void discardModel() {
        FileModelConverter.shutdown();
        MetadataHandler.PLUGIN = previousPlugin;
    }

    private Vector3d locator() {
        Vector3d position = new Vector3d();
        model.getSkeleton().transformBones();
        model.getSkeleton().locatorTransform(tip, position, new Quaterniond());
        return position;
    }

    private Location probe() {
        return model.getSkeleton().getBones().stream()
                .filter(bone -> bone.getBoneBlueprint().getOriginalBoneName().equals("m_probe"))
                .findFirst().orElseThrow().getBoneLocation();
    }

    @Test
    void locatorSitsWhereAMountPointAtTheSameSpotSits() {
        Vector3d locator = locator();
        Location probe = probe();
        assertEquals(probe.getX(), locator.x, 1e-6);
        assertEquals(probe.getY(), locator.y, 1e-6);
        assertEquals(probe.getZ(), locator.z, 1e-6);
        assertEquals(1, Math.hypot(locator.x - 10, locator.z - 20), 1e-6, "One block in front of the pivot");
        assertEquals(64.5, locator.y, 1e-6);
    }

    @Test
    void locatorFollowsItsAnimatedBone() {
        model.getAnimationComponent().playAnimation("tilt", false, true);
        model.getAnimationComponent().tick();
        Vector3d locator = locator();
        Location probe = probe();
        assertEquals(probe.getY(), locator.y, 1e-6);
        assertEquals(0, Math.hypot(locator.x - 10, locator.z - 20), 1e-6, "A 90 degree tilt makes the tip vertical");
        assertEquals(1, Math.abs(locator.y - 64.5), 1e-6);
    }

    @Test
    void missingLocatorUsesTheModelOrigin() {
        Vector3d origin = new Vector3d();
        model.getSkeleton().locatorTransform(null, origin, new Quaterniond());
        assertEquals(new Vector3d(10, 64, 20), origin);
    }
}
