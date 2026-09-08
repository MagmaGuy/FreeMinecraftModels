package com.magmaguy.freeminecraftmodels.utils;

import com.google.gson.*;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** #17: verifies production Euler extraction against actual reported bone rotations. */
public class RotationTicketTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "NIGHTBREAK_TICKET_PACKS", matches = ".+")
    public void reportedBoneRotationsKeepTheirOrientationAtZeroYaw() throws Exception {
        Path file = Path.of(System.getenv("NIGHTBREAK_TICKET_PACKS"), "building.glb.bbmodel");
        JsonObject model = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        List<double[]> rotations = new ArrayList<>();
        collect(model.getAsJsonArray("outliner"), rotations);
        assertFalse(rotations.isEmpty(), "The reporter's model must supply bone rotations");
        List<String> failures = new ArrayList<>();
        int checks = 0;
        for (double yaw : new double[]{0, .01}) for (double[] degrees : rotations) {
            float x = (float) Math.toRadians(degrees[0]), y = (float) Math.toRadians(degrees[1]), z = (float) Math.toRadians(degrees[2]);
            float rootYaw = (float) -Math.toRadians(yaw + 180);
            TransformationMatrix parent = new TransformationMatrix(), bone = new TransformationMatrix(), global = new TransformationMatrix();
            parent.rotateLocal(0, rootYaw, 0);
            bone.rotateLocal(x, y, z);
            TransformationMatrix.multiplyMatrices(parent, bone, global);
            double[] extracted = global.getRotation();
            Matrix3d expected = new Matrix3d().rotateY(rootYaw).rotateZ(z).rotateY(y).rotateX(x);
            Matrix3d actual = new Matrix3d().rotateZ(extracted[2]).rotateY(extracted[1]).rotateX(extracted[0]);
            for (Vector3d axis : List.of(new Vector3d(1,0,0), new Vector3d(0,1,0), new Vector3d(0,0,1))) {
                double error = expected.transform(new Vector3d(axis)).distance(actual.transform(new Vector3d(axis)));
                if (error > .0001 && failures.size() < 5) failures.add("yaw=" + yaw + " bone=" + Arrays.toString(degrees) + " axisError=" + error);
            }
            checks++;
        }
        System.out.println("Checked " + checks + " actual reported bone/yaw combinations");
        assertTrue(failures.isEmpty(), "Production orientation extraction changed bone geometry: " + failures);
    }
    private static void collect(JsonArray nodes, List<double[]> rotations) {
        for (JsonElement node : nodes) if (node.isJsonObject()) {
            JsonObject group = node.getAsJsonObject();
            if (group.has("rotation")) {
                JsonArray rotation = group.getAsJsonArray("rotation");
                rotations.add(new double[]{rotation.get(0).getAsDouble(), rotation.get(1).getAsDouble(), rotation.get(2).getAsDouble()});
            }
            if (group.has("children")) collect(group.getAsJsonArray("children"), rotations);
        }
    }
}
