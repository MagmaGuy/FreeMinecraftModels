package com.magmaguy.freeminecraftmodels.bedrock;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The lean bone draws a model where Java draws it: on its feet or centred on its body, upright or leaning, whatever
 * way it faces. The Java side is the model's own bone positions; the Bedrock side applies the exported pivot and the
 * sent properties the way Blockbench shows a Bedrock model, moving the lean bone after turning it about its pivot.
 */
class BedrockLeanBoneJavaTest {
    private static final float[] YAWS = {0F, 37.5F, 90F, -170F};
    // Empty bones, so their positions are the points themselves: one on the model's axis, one off it.
    private static final Map<String, Vector3d> POINTS = Map.of(
            "m_top", new Vector3d(0, 32, 0), "m_side", new Vector3d(4, 12, -6));

    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private FileModelConverter converter;

    @BeforeEach
    void loadModel() throws Exception {
        previousPlugin = MetadataHandler.PLUGIN;
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "FreeMinecraftModels";
                    throw new UnsupportedOperationException(method.getName());
                });
        Path source = directory.resolve("lean_bone_probe.bbmodel");
        // No cubes: the model counts as 0 to 2 blocks tall, so its middle is 16 pixels up.
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", List.of(),
                "outliner", List.of(Map.of("name", "body", "uuid", "bone-body", "origin", List.of(0, 0, 0),
                        "children", List.of(
                                Map.of("name", "m_top", "uuid", "bone-top", "origin", List.of(0, 32, 0),
                                        "children", List.of()),
                                Map.of("name", "m_side", "uuid", "bone-side", "origin", List.of(4, 12, -6),
                                        "children", List.of())))))));
        converter = new FileModelConverter(source.toFile());
    }

    @AfterEach
    void discardModel() {
        FileModelConverter.shutdown();
        MetadataHandler.PLUGIN = previousPlugin;
    }

    @Test
    void aModelOnItsFeetIsDrawnWhereJavaDrawsIt() throws Exception {
        for (float yaw : YAWS) {
            assertSamePlace(model(yaw, false, null));
            assertSamePlace(model(yaw, false, new Quaterniond().rotationAxis(Math.toRadians(70), 0.6, 0.2, -0.77).normalize()));
        }
    }

    @Test
    void aCentredModelIsDrawnWhereJavaDrawsIt() throws Exception {
        for (float yaw : YAWS) {
            assertSamePlace(model(yaw, true, null));
            assertSamePlace(model(yaw, true, new Quaterniond().rotationZ(Math.toRadians(90))));
            assertSamePlace(model(yaw, true, new Quaterniond().rotationAxis(Math.toRadians(150), -0.3, 0.5, 0.81).normalize()));
        }
    }

    /** The probe facing this yaw, on a body one block tall, centred on it or not, leaning by this tilt. */
    private ModeledEntity model(float yaw, boolean centred, Quaterniond tilt) {
        ModeledEntity model = new ModeledEntity(converter.getID(), new Location(null, 10, 64, 20, yaw, 0)) {
            @Override
            protected double bodyHeight() {
                return 1D;
            }
        };
        model.setCenteredOnBody(centred);
        model.setBodyTilt(tilt);
        return model;
    }

    private void assertSamePlace(ModeledEntity model) throws Exception {
        // The model tick's own step, which places every bone; package-private to the skeleton.
        Method transformBones = model.getSkeleton().getClass().getDeclaredMethod("transformBones");
        transformBones.setAccessible(true);
        transformBones.invoke(model.getSkeleton());
        Location feet = model.getLocation();
        float yaw = feet.getYaw();
        Map<String, Object> sent = new BedrockBodyLean().update(model.getBodyTilt(), yaw, model.getBodyOffset(),
                model.getScaleModifier());
        double[] lean = new double[4];
        if (sent != null) {
            List<String> names = BedrockEntityBundleExporter.LEAN_BONE_PROPERTIES;
            for (int i = 0; i < 4; i++) lean[i] = ((Integer) sent.getOrDefault(names.get(i), 0)) / 100D;
        }
        Vector3d pivot = new Vector3d(0, converter.getSkeletonBlueprint().getBodyTiltPivot() * 16D, 0);
        Matrix3d bone = new Matrix3d().rotationZ(Math.toRadians(lean[2]))
                .rotateY(Math.toRadians(-lean[1])).rotateX(Math.toRadians(-lean[0]));
        Matrix3d facing = new Matrix3d().rotationY(Math.toRadians(180D - yaw));
        double scale = model.getScaleModifier() / 16D;
        for (Map.Entry<String, Vector3d> point : POINTS.entrySet()) {
            Vector3d pixels = bone.transform(new Vector3d(point.getValue()).sub(pivot)).add(pivot).add(0, lean[3], 0);
            Vector3d bedrock = facing.transform(pixels).mul(scale).add(feet.getX(), feet.getY(), feet.getZ());
            Location java = model.getSkeleton().getBones().stream()
                    .filter(candidate -> candidate.getBoneBlueprint().getOriginalBoneName().equals(point.getKey()))
                    .findFirst().orElseThrow().getBoneLocation();
            String where = point.getKey() + " at yaw " + yaw + ", centred " + model.isCenteredOnBody() + ", tilt "
                    + model.getBodyTilt() + ", sent " + Arrays.toString(lean);
            assertEquals(java.getX(), bedrock.x, 2.0E-3, where);
            assertEquals(java.getY(), bedrock.y, 2.0E-3, where);
            assertEquals(java.getZ(), bedrock.z, 2.0E-3, where);
        }
    }
}
