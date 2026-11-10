package com.magmaguy.freeminecraftmodels.bedrock;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Bedrock client draws a keyed pose where Java draws it. Bedrock's side is modelled as Blockbench shows a Bedrock
 * model: a bone's geometry rotation and its animation rotation add up to (x, y, z), which turns the bone by
 * Rz(z) · Ry(−y) · Rx(−x) about its pivot on Blockbench axes, and an animation position (x, y, z) moves it by
 * (−x, y, z). The Java side is the model's own bone positions after the pose mixer played the same animation.
 * <p>
 * The pose is a sitting one: thighs turned forward by a rest rotation, and a torso, an arm and a shin held to one
 * side by constant keys, as the Adventurers' Guild table NPCs are built. A sign error on any axis moves a probe.
 */
class BedrockPoseExportTest {
    private static final float[] YAWS = {0F, 37.5F, 90F, -170F};
    // Empty mount-point bones, so their positions are the points themselves; never exported to Bedrock.
    private static final Map<String, Vector3d> PROBES = Map.of(
            "m_hand", new Vector3d(6, 10, 2), "m_foot", new Vector3d(-2, 0, 1));
    // Each probe's exported ancestors, root first.
    private static final Map<String, List<String>> CHAINS = Map.of(
            "m_hand", List.of("body", "torso", "arm"), "m_foot", List.of("body", "thigh", "shin"));

    @TempDir
    Path directory;
    private Plugin previousPlugin;

    @BeforeEach
    void fakePlugin() {
        previousPlugin = MetadataHandler.PLUGIN;
        File dataFolder = directory.resolve("data").toFile();
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "FreeMinecraftModels";
                    case "getDataFolder" -> dataFolder;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @AfterEach
    void discardModel() {
        FileModelConverter.shutdown();
        MetadataHandler.PLUGIN = previousPlugin;
    }

    @Test
    void aFormat5KeyGoesOutWithXAndYNegated() throws Exception {
        // Blockbench 5 keeps keys on its own axes, as it keeps rest rotations, and negates them for Bedrock.
        Exported exported = export(load("5.0"));
        assertVector(exported.geometry("thigh").getAsJsonArray("rotation"), -90, -20, -15, "rest rotation");
        assertVector(exported.key("arm", "rotation"), -40, -25, -10, "rotation key");
        assertVector(exported.key("arm", "position"), -2, 3, -1, "position key");
        assertVector(exported.key("torso", "rotation"), -10, 0, 0, "a torso keyed +10 leans back on Java");
    }

    @Test
    void aFormat4KeyGoesOutAsAuthored() throws Exception {
        // Blockbench 4 keeps keys in Bedrock's convention already; only rest rotations are on its own axes.
        Exported exported = export(load("4.10"));
        assertVector(exported.geometry("thigh").getAsJsonArray("rotation"), -90, -20, -15, "rest rotation");
        assertVector(exported.key("arm", "rotation"), 40, 25, -10, "rotation key");
        assertVector(exported.key("arm", "position"), 2, 3, -1, "position key");
        assertVector(exported.key("torso", "rotation"), 10, 0, 0, "a torso keyed +10 leans forward on Java");
    }

    @Test
    void aFormat5PoseIsDrawnWhereJavaDrawsIt() throws Exception {
        assertSamePose("5.0");
    }

    @Test
    void aFormat4PoseIsDrawnWhereJavaDrawsIt() throws Exception {
        assertSamePose("4.10");
    }

    @Test
    void theTwoFormatsReadTheSameKeysAsOppositePoses() throws Exception {
        // The probe would pass a sign error that hit both sides alike, so pin the Java side to the files' meaning:
        // +40 on x lifts a format 5 arm forward (toward -z, the model's front) and a format 4 arm backward.
        Vector3d format5 = javaProbe(load("5.0"), 0F, "m_hand"), format4 = javaProbe(load("4.10"), 0F, "m_hand");
        // The model faces yaw 0, south (+z), so its front is world +z.
        assertTrue(format5.z > 20.3, "format 5 hand in front of the shoulder line: " + format5);
        assertTrue(format4.z < 19.9, "format 4 hand behind it: " + format4);
    }

    private void assertSamePose(String format) throws Exception {
        FileModelConverter converter = load(format);
        Exported exported = export(converter);
        for (float yaw : YAWS) {
            Matrix3d facing = new Matrix3d().rotationY(Math.toRadians(180D - yaw));
            for (Map.Entry<String, Vector3d> probe : PROBES.entrySet()) {
                Matrix4d bones = new Matrix4d();
                for (String bone : CHAINS.get(probe.getKey())) bones.mul(exported.bedrockTransform(bone));
                Vector3d bedrock = facing.transform(bones.transformPosition(new Vector3d(probe.getValue())))
                        .mul(1 / 16D).add(10, 64, 20);
                Vector3d java = javaProbe(converter, yaw, probe.getKey());
                assertEquals(0D, java.distance(bedrock), 2.0E-3, "format " + format + ", " + probe.getKey()
                        + " at yaw " + yaw + ": Java " + java + ", Bedrock " + bedrock);
            }
        }
    }

    /** Where Java puts a probe after the pose mixer played one frame of the pose. */
    private static Vector3d javaProbe(FileModelConverter converter, float yaw, String probe) throws Exception {
        ModeledEntity model = new ModeledEntity(converter.getID(), new Location(null, 10, 64, 20, yaw, 0));
        assertTrue(model.getAnimationComponent().playAnimation("pose", false, true));
        model.getAnimationComponent().tick();
        // The model tick's own step, which places every bone; package-private to the skeleton.
        Method transformBones = model.getSkeleton().getClass().getDeclaredMethod("transformBones");
        transformBones.setAccessible(true);
        transformBones.invoke(model.getSkeleton());
        Location location = model.getSkeleton().getBones().stream()
                .filter(candidate -> candidate.getBoneBlueprint().getOriginalBoneName().equals(probe))
                .findFirst().orElseThrow().getBoneLocation();
        return new Vector3d(location.getX(), location.getY(), location.getZ());
    }

    private FileModelConverter load(String format) throws Exception {
        Path source = directory.resolve("pose_export_" + format.replace('.', '_') + ".bbmodel");
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", format, "model_format", "free"),
                "elements", List.of(),
                "outliner", List.of(bone("body", List.of(0, 0, 0), null, List.of(
                        bone("torso", List.of(0, 12, 0), null, List.of(
                                bone("arm", List.of(5, 22, 0), null, List.of(
                                        bone("m_hand", List.of(6, 10, 2), null, List.of()))))),
                        bone("thigh", List.of(-2, 12, 0), List.of(90, 20, -15), List.of(
                                bone("shin", List.of(-2, 6, 0), List.of(0, 0, 60), List.of(
                                        bone("m_foot", List.of(-2, 0, 1), null, List.of())))))))),
                "animations", List.of(Map.of("name", "pose", "loop", "loop", "length", 0.5, "animators", Map.of(
                        "bone-torso", animator("torso", key("rotation", 10, 0, 0)),
                        "bone-arm", animator("arm", key("rotation", 40, 25, -10), key("position", 2, 3, -1)),
                        // A turn about x after a rest turn about z alone: the one case where Java's
                        // rest * animation product and Bedrock's Euler sum are the same rotation.
                        "bone-shin", animator("shin", key("rotation", -25, 0, 0))))))));
        return new FileModelConverter(source.toFile());
    }

    private Exported export(FileModelConverter converter) throws Exception {
        Path root = directory.resolve("bundle-" + converter.getID());
        Class<?> textureInfo = Class.forName(BedrockEntityBundleExporter.class.getName() + "$BedrockTextureInfo");
        Constructor<?> constructor = textureInfo.getDeclaredConstructor(String.class, int.class, int.class, List.class);
        constructor.setAccessible(true);
        Method geometry = BedrockEntityBundleExporter.class.getDeclaredMethod("writeGeometry", File.class, String.class,
                textureInfo, List.class, FileModelConverter.class);
        geometry.setAccessible(true);
        geometry.invoke(null, root.toFile(), converter.getID(),
                constructor.newInstance("textures/entity/pose_export/texture", 16, 16, List.of()),
                BedrockEntityBundleExporter.visualBones(converter), converter);
        Method animations = BedrockEntityBundleExporter.class.getDeclaredMethod("writeAnimations", File.class,
                String.class, FileModelConverter.class, List.class);
        animations.setAccessible(true);
        animations.invoke(null, root.toFile(), converter.getID(), converter, BedrockEntityBundleExporter.visualBones(converter));

        Map<String, JsonObject> bones = new HashMap<>();
        for (JsonElement bone : read(root.resolve("models/entity/freeminecraftmodels/" + converter.getID() + ".geo.json"))
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones"))
            bones.put(bone.getAsJsonObject().get("name").getAsString(), bone.getAsJsonObject());
        String hex = java.util.HexFormat.of().formatHex("pose".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonObject pose = read(root.resolve("animations/" + converter.getID() + ".animation.json"))
                .getAsJsonObject("animations").getAsJsonObject("animation.fmm." + converter.getID() + ".a_" + hex);
        assertNotNull(pose, "the pose animation is exported");
        return new Exported(bones, pose.getAsJsonObject("bones"));
    }

    private record Exported(Map<String, JsonObject> bones, JsonObject animation) {
        JsonObject geometry(String bone) {
            return bones.get(bone);
        }

        /** A channel's value at the start of the animation; every key of the pose is constant. */
        JsonArray key(String bone, String channel) {
            JsonObject keyed = animation.getAsJsonObject(bone);
            if (keyed == null || !keyed.has(channel)) return null;
            return keyed.getAsJsonObject(channel).getAsJsonArray("0");
        }

        /** The bone's own transform in pixels on Blockbench axes, as Blockbench shows a Bedrock model. */
        Matrix4d bedrockTransform(String bone) {
            double[] pivot = vector(geometry(bone).getAsJsonArray("pivot"));
            double[] rest = vector(geometry(bone).getAsJsonArray("rotation"));
            double[] turn = vector(key(bone, "rotation"));
            double[] move = vector(key(bone, "position"));
            return new Matrix4d()
                    .translate(-move[0], move[1], move[2])
                    .translate(-pivot[0], pivot[1], pivot[2])
                    .rotateZ(Math.toRadians(rest[2] + turn[2]))
                    .rotateY(Math.toRadians(-(rest[1] + turn[1])))
                    .rotateX(Math.toRadians(-(rest[0] + turn[0])))
                    .translate(pivot[0], -pivot[1], -pivot[2]);
        }

        private static double[] vector(JsonArray array) {
            double[] vector = new double[3];
            if (array != null) for (int axis = 0; axis < 3; axis++) vector[axis] = array.get(axis).getAsDouble();
            return vector;
        }
    }

    private static void assertVector(JsonArray actual, double x, double y, double z, String message) {
        assertNotNull(actual, message);
        assertEquals(x, actual.get(0).getAsDouble(), 1e-3, message + " x");
        assertEquals(y, actual.get(1).getAsDouble(), 1e-3, message + " y");
        assertEquals(z, actual.get(2).getAsDouble(), 1e-3, message + " z");
    }

    private static Map<String, Object> bone(String name, List<Integer> origin, List<Integer> rotation, List<Object> children) {
        Map<String, Object> bone = new HashMap<>();
        bone.put("name", name);
        bone.put("uuid", "bone-" + name);
        bone.put("origin", origin);
        if (rotation != null) bone.put("rotation", rotation);
        bone.put("children", new ArrayList<>(children));
        return bone;
    }

    private static Map<String, Object> animator(String bone, Object... keyframes) {
        return Map.of("name", bone, "type", "bone", "keyframes", List.of(keyframes));
    }

    private static Map<String, Object> key(String channel, double x, double y, double z) {
        return Map.of("channel", channel, "interpolation", "linear", "time", 0,
                "data_points", List.of(Map.of("x", x, "y", y, "z", z)));
    }

    private static JsonObject read(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }
}
