package com.magmaguy.freeminecraftmodels.customentity.core;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.joml.Vector3f;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ridden bone is culled by a box built from its display reach, so the reach has to bound every cube of the bone
 * around the point its display stands on, however the bone is turned, moved or scaled.
 */
class DisplayReachTest {
    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private ModeledEntity model;

    @BeforeEach
    void loadModel() throws Exception {
        previousPlugin = MetadataHandler.PLUGIN;
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "FreeMinecraftModels";
                    case "getDataFolder" -> directory.toFile();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Path source = directory.resolve("reach_probe.bbmodel");
        // An arm hanging from its shoulder with a hand cube, and a fin turned 45 degrees about a point outside it.
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "resolution", Map.of("width", 16, "height", 16),
                "elements", List.of(
                        cube("cube-arm", List.of(-2, 4, -2), List.of(2, 16, 2), null, null),
                        cube("cube-hand", List.of(-3, 0, -3), List.of(3, 4, 3), null, null),
                        cube("cube-fin", List.of(6, 10, -1), List.of(10, 12, 1), List.of(0, 0, 45), List.of(2, 8, 0))),
                "outliner", List.of(Map.of("name", "arm", "uuid", "bone-arm", "origin", List.of(0, 16, 0),
                        "children", List.of("cube-arm", "cube-hand", "cube-fin"))),
                "animations", List.of(Map.of("name", "swing", "loop", "loop", "length", 1, "animators",
                        Map.of("bone-arm", Map.of("name", "arm", "type", "bone", "keyframes", List.of(
                                Map.of("channel", "rotation", "interpolation", "linear", "time", 0,
                                        "data_points", List.of(Map.of("x", 70, "y", 30, "z", -50))),
                                Map.of("channel", "position", "interpolation", "linear", "time", 0,
                                        "data_points", List.of(Map.of("x", 3, "y", -9, "z", 5))),
                                Map.of("channel", "scale", "interpolation", "linear", "time", 0,
                                        "data_points", List.of(Map.of("x", 1.5, "y", 1.5, "z", 1.5)))))))))));
        FileModelConverter converter = new FileModelConverter(source.toFile());
        model = new ModeledEntity(converter.getID(), new Location(null, 10, 64, 20, 30, 0));
    }

    private static Map<String, Object> cube(String uuid, List<Integer> from, List<Integer> to,
                                            List<Integer> rotation, List<Integer> origin) {
        Map<String, Object> faces = Map.of("north", Map.of("uv", List.of(0, 0, 1, 1), "texture", 0));
        java.util.HashMap<String, Object> cube = new java.util.HashMap<>(Map.of("type", "cube", "uuid", uuid,
                "name", uuid, "from", from, "to", to, "faces", faces));
        if (rotation != null) cube.put("rotation", rotation);
        if (origin != null) cube.put("origin", origin);
        return cube;
    }

    @AfterEach
    void discardModel() {
        FileModelConverter.shutdown();
        MetadataHandler.PLUGIN = previousPlugin;
    }

    private Bone arm() {
        return model.getSkeleton().getBones().stream()
                .filter(bone -> bone.getBoneBlueprint().getOriginalBoneName().equals("arm"))
                .findFirst().orElseThrow();
    }

    @Test
    void reachCoversTheCubesAndTheTurnedFin() {
        BoneBlueprint arm = arm().getBoneBlueprint();
        // The cubes' box runs from (-3, 0, -3) to (10, 16, 3) pixels, so the display stands on (3.5, 8, 0).
        Vector3f center = arm.getModelCenter().mul(16f);
        assertEquals(3.5f, center.x, 1e-4f);
        assertEquals(8f, center.y, 1e-4f);
        assertEquals(0f, center.z, 1e-4f);
        // Each cube counts its centre's distance from the display plus its half diagonal. The fin turns about
        // (2, 8, 0), so its centre (8, 11, 0) stays sqrt(45) from there, which lies 1.5 from the display.
        double fin = 1.5D + Math.sqrt(45D) + Math.sqrt(6D);
        double hand = Math.sqrt(3.5D * 3.5D + 6D * 6D) + Math.sqrt(22D);
        double armCube = Math.sqrt(3.5D * 3.5D + 2D * 2D) + Math.sqrt(44D);
        assertEquals(Math.max(fin, Math.max(hand, armCube)) / 16D, arm.getDisplayReach(), 1e-9);
    }

    @Test
    void reachHoldsThroughAnAnimatedPose() {
        model.getAnimationComponent().playAnimation("swing", false, true);
        model.getAnimationComponent().tick();
        model.getSkeleton().transformBones();
        BoneTransforms transforms = arm().getBoneTransforms();
        BoneBlueprint blueprint = arm().getBoneBlueprint();
        double[] display = new double[3];
        transforms.globalPoint(blueprint.getModelCenter(), display);
        // How far one block along each model axis lands from the display is the pose's scale on that axis.
        double[] corner = new double[3];
        double scale = 0D;
        for (Vector3f axis : new Vector3f[]{new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)}) {
            transforms.globalPoint(axis.add(blueprint.getModelCenter()), corner);
            scale = Math.max(scale, Math.sqrt(Math.pow(corner[0] - display[0], 2) + Math.pow(corner[1] - display[1], 2)
                    + Math.pow(corner[2] - display[2], 2)));
        }
        assertEquals(1.5D, scale, 1e-4, "The pose is scaled, so the reach has to scale with it");
        double radius = blueprint.getDisplayReach() * scale;
        for (int[] point : new int[][]{{-3, 0, -3}, {3, 0, 3}, {-2, 16, 2}, {2, 16, -2}, {3, 4, -3}}) {
            transforms.globalPoint(new Vector3f(point[0], point[1], point[2]).div(16f), corner);
            double distance = Math.sqrt(Math.pow(corner[0] - display[0], 2) + Math.pow(corner[1] - display[1], 2)
                    + Math.pow(corner[2] - display[2], 2));
            assertTrue(distance <= radius + 1e-6, "Corner " + java.util.Arrays.toString(point) + " lies "
                    + distance + " from the display, outside the reach " + radius);
        }
    }

    @Test
    void cubesAboveTheEntityGetABoxStandingOnIt() {
        float[] box = BoneTransforms.cullingBoxFor(-0.3D, 1.2D, 0.5D);
        assertTrue(box[1] >= 1.2F, "The box reaches the highest cube");
        assertTrue(box[0] / 2F >= 0.5F, "The box reaches the furthest cube");
        assertTrue(BoneTransforms.cullingBoxHolds(box[0], box[1], -0.3D, 1.2D, 0.5D));
    }

    @Test
    void cubesBelowTheEntityGetABoxHangingFromIt() {
        // A centred pet's lower tentacles hang a block under the seat its bones ride.
        float[] box = BoneTransforms.cullingBoxFor(-1.23D, -0.75D, 0.6D);
        assertTrue(box[1] <= -1.23F, "The box hangs down past the lowest cube");
        assertTrue(BoneTransforms.cullingBoxHolds(box[0], box[1], -1.23D, -0.75D, 0.6D));
        assertTrue(BoneTransforms.cullingBoxHolds(box[0], box[1], -1.0D, 0.4D, 0.6D),
                "Viewers widen the box by half a block, so cubes up to the margin above the entity still fit");
        assertFalse(BoneTransforms.cullingBoxHolds(box[0], box[1], -1.0D, 0.5D, 0.6D));
    }

    @Test
    void cubesReachingFarBothWaysGetNoBox() {
        float[] box = BoneTransforms.cullingBoxFor(-0.8D, 0.7D, 0.4D);
        assertEquals(0F, box[0]);
        assertEquals(0F, box[1]);
        assertFalse(BoneTransforms.cullingBoxHolds(0F, 0F, 0D, 0.1D, 0.1D), "No box holds nothing");
    }

    @Test
    void aBoxStopsHoldingCubesThatLeaveIt() {
        float[] up = BoneTransforms.cullingBoxFor(0D, 1D, 0.5D);
        assertFalse(BoneTransforms.cullingBoxHolds(up[0], up[1], -0.5D, 1D, 0.5D), "Cubes dipping below the margin");
        assertFalse(BoneTransforms.cullingBoxHolds(up[0], up[1], 0D, up[1] + 0.01D, 0.5D), "Cubes rising past the top");
        assertFalse(BoneTransforms.cullingBoxHolds(up[0], up[1], 0D, 1D, up[0] / 2D + 0.01D), "Cubes reaching past the side");
    }
}
