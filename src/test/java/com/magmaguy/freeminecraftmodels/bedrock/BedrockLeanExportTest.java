package com.magmaguy.freeminecraftmodels.bedrock;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Every Bedrock model hangs from a lean bone, pivoted where Java tilts, that its lean properties turn. */
class BedrockLeanExportTest {
    // The pattern the RSPM Geyser bridge reads a pack's controllers with to register their properties at startup.
    private static final Pattern RSPM_PROPERTY_QUERY = Pattern.compile("query\\.property\\(['\"]([^'\"]+)['\"]\\)");

    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private boolean installedServer;
    private FileModelConverter converter;

    @BeforeEach
    void convert() throws Exception {
        previousPlugin = MetadataHandler.PLUGIN;
        File dataFolder = directory.resolve("data").toFile();
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "FreeMinecraftModels";
                    // Cubes become Java bone models under the data folder.
                    case "getDataFolder" -> dataFolder;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        // Converting cubes reads the server version.
        if (Bukkit.getServer() == null) {
            Bukkit.setServer((Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                    new Class<?>[]{Server.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getBukkitVersion" -> "26.3-R0.1-SNAPSHOT";
                        case "getLogger" -> java.util.logging.Logger.getLogger("FMM-lean-export-test");
                        default -> null;
                    }));
            installedServer = true;
        }
        // Cubes from 4 to 20 pixels up: the model tilts about 12 pixels above its feet.
        Path source = directory.resolve("lean_export.bbmodel");
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", List.of(cube("cube-body", -4, 4, -4, 4, 12, 4), cube("cube-head", -2, 12, -2, 2, 20, 2),
                        cube("cube-tail", -1, 6, 4, 1, 8, 10)),
                "outliner", List.of(
                        Map.of("name", "body", "uuid", "bone-body", "origin", List.of(0, 8, 0),
                                "children", List.of("cube-body", Map.of("name", "head", "uuid", "bone-head",
                                        "origin", List.of(0, 12, 0), "children", List.of("cube-head")))),
                        Map.of("name", "tail", "uuid", "bone-tail", "origin", List.of(0, 7, 4),
                                "children", List.of("cube-tail"))))));
        converter = new FileModelConverter(source.toFile());
    }

    @AfterEach
    void cleanUp() throws Exception {
        FileModelConverter.shutdown();
        MetadataHandler.PLUGIN = previousPlugin;
        if (installedServer) {
            // Bukkit's singleton can only be set once; later test classes install their own, so give it back.
            Field server = Bukkit.class.getDeclaredField("server");
            server.setAccessible(true);
            server.set(null, null);
        }
    }

    @Test
    void everyRootBoneHangsFromTheLeanBoneAtTheModelsMiddle() throws Exception {
        Path root = directory.resolve("bundle");
        Method writer = BedrockEntityBundleExporter.class.getDeclaredMethod("writeGeometry", File.class, String.class,
                textureInfoClass(), List.class, FileModelConverter.class);
        writer.setAccessible(true);
        writer.invoke(null, root.toFile(), converter.getID(), textureInfo(),
                BedrockEntityBundleExporter.visualBones(converter), converter);
        JsonArray bones = read(root.resolve("models/entity/freeminecraftmodels/" + converter.getID() + ".geo.json"))
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");

        Map<String, JsonObject> byName = new HashMap<>();
        for (JsonElement bone : bones) byName.put(bone.getAsJsonObject().get("name").getAsString(), bone.getAsJsonObject());
        JsonObject lean = byName.get(BedrockEntityBundleExporter.LEAN_BONE);
        assertNotNull(lean, byName.keySet().toString());
        assertFalse(lean.has("parent"));
        assertFalse(lean.has("cubes"));
        JsonArray pivot = lean.getAsJsonArray("pivot");
        assertEquals(0D, pivot.get(0).getAsDouble(), 1e-9);
        assertEquals(12D, pivot.get(1).getAsDouble(), 1e-9);
        assertEquals(0D, pivot.get(2).getAsDouble(), 1e-9);
        assertEquals("body", byName.get("head").get("parent").getAsString());
        for (String name : byName.keySet()) {
            String ancestor = name;
            while (byName.get(ancestor).has("parent")) ancestor = byName.get(ancestor).get("parent").getAsString();
            assertEquals(BedrockEntityBundleExporter.LEAN_BONE, ancestor, name + " leans with the model");
        }
    }

    @Test
    void theLeanAnimationTurnsTheLeanBoneByItsProperties() throws Exception {
        Path root = directory.resolve("bundle");
        invokeAnimationWriter("writeAnimations", root);
        JsonObject lean = read(root.resolve("animations/" + converter.getID() + ".animation.json"))
                .getAsJsonObject("animations").getAsJsonObject("animation.fmm." + converter.getID() + ".lean");
        assertNotNull(lean);
        assertTrue(lean.get("loop").getAsBoolean());
        JsonArray rotation = lean.getAsJsonObject("bones").getAsJsonObject(BedrockEntityBundleExporter.LEAN_BONE)
                .getAsJsonArray("rotation");
        for (int axis = 0; axis < 3; axis++) {
            assertEquals("query.property('" + BedrockEntityBundleExporter.LEAN_PROPERTIES.get(axis) + "') / 100",
                    rotation.get(axis).getAsString());
        }
    }

    @Test
    void theLeanControllerNamesItsPropertiesWhereRspmRegistersThem() throws Exception {
        Path root = directory.resolve("bundle");
        invokeAnimationWriter("writeAnimationControllers", root);
        String controllerId = "controller.animation.fmm." + converter.getID() + ".lean";
        JsonObject controller = read(root.resolve("animation_controllers/" + converter.getID()
                + ".animation_controllers.json")).getAsJsonObject("animation_controllers").getAsJsonObject(controllerId);
        assertNotNull(controller);
        assertEquals("upright", controller.get("initial_state").getAsString());
        JsonObject states = controller.getAsJsonObject("states");
        assertFalse(states.getAsJsonObject("upright").has("animations"), "an upright model plays nothing");
        assertEquals("anim_lean", states.getAsJsonObject("leaning").getAsJsonArray("animations").get(0).getAsString());

        Set<String> queried = new LinkedHashSet<>();
        Matcher matcher = RSPM_PROPERTY_QUERY.matcher(controller.toString());
        while (matcher.find()) queried.add(matcher.group(1));
        assertEquals(Set.copyOf(BedrockEntityBundleExporter.LEAN_PROPERTIES), queried);

        Method writer = BedrockEntityBundleExporter.class.getDeclaredMethod("writeEntity", File.class, String.class,
                textureInfoClass(), FileModelConverter.class);
        writer.setAccessible(true);
        writer.invoke(null, root.toFile(), converter.getID(), textureInfo(), converter);
        JsonObject description = read(root.resolve("entity/" + converter.getID() + ".entity.json"))
                .getAsJsonObject("minecraft:client_entity").getAsJsonObject("description");
        assertEquals(controllerId, description.getAsJsonObject("animations").get("control_lean").getAsString());
        JsonArray animate = description.getAsJsonObject("scripts").getAsJsonArray("animate");
        assertEquals("control_lean", animate.get(animate.size() - 1).getAsString());
    }

    @Test
    void aCarrierFullOfAnimationFlagsLeavesTheLeanOut() {
        // 24 flags per property, 32 properties: 29 flag properties leave room for the three lean properties.
        assertTrue(BedrockEntityBundleExporter.leans(29 * 24));
        assertFalse(BedrockEntityBundleExporter.leans(29 * 24 + 1));
    }

    private void invokeAnimationWriter(String method, Path root) throws Exception {
        Method writer = BedrockEntityBundleExporter.class.getDeclaredMethod(method, File.class, String.class,
                FileModelConverter.class, List.class);
        writer.setAccessible(true);
        writer.invoke(null, root.toFile(), converter.getID(), converter, BedrockEntityBundleExporter.visualBones(converter));
    }

    private static Class<?> textureInfoClass() throws ClassNotFoundException {
        return Class.forName(BedrockEntityBundleExporter.class.getName() + "$BedrockTextureInfo");
    }

    /** A texture for cubes without faces, which never look up a placement. */
    private static Object textureInfo() throws Exception {
        Constructor<?> constructor = textureInfoClass().getDeclaredConstructor(String.class, int.class, int.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance("textures/entity/lean_export/texture", 16, 16, List.of());
    }

    private static Map<String, Object> cube(String uuid, double fromX, double fromY, double fromZ,
                                            double toX, double toY, double toZ) {
        return Map.of("uuid", uuid, "name", uuid, "from", List.of(fromX, fromY, fromZ), "to", List.of(toX, toY, toZ),
                "origin", List.of(0, 0, 0), "faces", Map.of());
    }

    private static JsonObject read(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }
}
