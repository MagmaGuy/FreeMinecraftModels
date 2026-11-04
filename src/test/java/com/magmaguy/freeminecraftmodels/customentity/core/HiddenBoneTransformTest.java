package com.magmaguy.freeminecraftmodels.customentity.core;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A hidden bone is drawn at no size, with every bone under it; the rest of the model is untouched. */
class HiddenBoneTransformTest {
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
        Path source = directory.resolve("saddle_probe.bbmodel");
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", List.of(),
                "outliner", List.of(Map.of("name", "body", "uuid", "bone-body", "origin", List.of(0, 8, 0),
                        "children", List.of(Map.of("name", "Saddle", "uuid", "bone-saddle", "origin", List.of(0, 12, 0),
                                "children", List.of(Map.of("name", "strap", "uuid", "bone-strap",
                                        "origin", List.of(0, 10, 2), "children", List.of())))))))));
        FileModelConverter converter = new FileModelConverter(source.toFile());
        model = new ModeledEntity(converter.getID(), new Location(null, 10, 64, 20, 30, 0));
    }

    @AfterEach
    void discardModel() {
        FileModelConverter.shutdown();
        MetadataHandler.PLUGIN = previousPlugin;
    }

    private double drawnScale(String boneName) {
        model.getSkeleton().transformBones();
        Bone bone = model.getSkeleton().getBones().stream()
                .filter(candidate -> candidate.getBoneBlueprint().getOriginalBoneName().equals(boneName))
                .findFirst().orElseThrow();
        double[] scale = new double[3];
        bone.getBoneTransforms().readDrawnScale(scale);
        assertEquals(scale[0], scale[1], 1e-9);
        assertEquals(scale[0], scale[2], 1e-9);
        return scale[0];
    }

    @Test
    void aHiddenBoneAndTheBonesUnderItAreDrawnAtNoSize() {
        double body = drawnScale("body"), saddle = drawnScale("Saddle"), strap = drawnScale("strap");
        assertTrue(saddle > 0 && strap > 0);

        assertTrue(model.setBoneHidden("saddle", true), "names match in any case");
        assertTrue(model.isBoneHidden("SADDLE"));
        assertEquals(0D, drawnScale("Saddle"), 1e-9);
        assertEquals(0D, drawnScale("strap"), 1e-9);
        assertEquals(body, drawnScale("body"), 1e-9);
        assertFalse(model.isBoneHidden("strap"), "only the named bone is hidden; the strap goes with it");
    }

    @Test
    void showingItAgainDrawsItAsBefore() {
        double saddle = drawnScale("Saddle"), strap = drawnScale("strap");
        model.setBoneHidden("saddle", true);
        drawnScale("Saddle");
        model.setBoneHidden("saddle", false);
        assertFalse(model.isBoneHidden("saddle"));
        assertEquals(saddle, drawnScale("Saddle"), 1e-9);
        assertEquals(strap, drawnScale("strap"), 1e-9);
    }

    @Test
    void aModelWithoutTheBoneSaysSo() {
        assertFalse(model.setBoneHidden("wings", true));
        assertFalse(model.isBoneHidden("wings"));
    }
}
