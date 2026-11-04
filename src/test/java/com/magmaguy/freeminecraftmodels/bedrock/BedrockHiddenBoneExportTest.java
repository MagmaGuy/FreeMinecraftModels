package com.magmaguy.freeminecraftmodels.bedrock;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model's saddle bone gets a Bedrock animation that shrinks it to nothing, with a flag after the model's own
 * animations, so the server can hide it on Bedrock as it does on Java.
 */
class BedrockHiddenBoneExportTest {
    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private boolean installedServer;
    private FileModelConverter saddled;
    private FileModelConverter plain;

    @BeforeEach
    void convert() throws Exception {
        previousPlugin = MetadataHandler.PLUGIN;
        File dataFolder = directory.resolve("data").toFile();
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "FreeMinecraftModels";
                    case "getDataFolder" -> dataFolder;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        if (Bukkit.getServer() == null) {
            Bukkit.setServer((Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                    new Class<?>[]{Server.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getBukkitVersion" -> "26.3-R0.1-SNAPSHOT";
                        case "getLogger" -> java.util.logging.Logger.getLogger("FMM-hidden-bone-export-test");
                        default -> null;
                    }));
            installedServer = true;
        }
        saddled = model("saddle_export", true);
        plain = model("plain_export", false);
    }

    @AfterEach
    void cleanUp() throws Exception {
        FileModelConverter.shutdown();
        MetadataHandler.PLUGIN = previousPlugin;
        if (installedServer) {
            Field server = Bukkit.class.getDeclaredField("server");
            server.setAccessible(true);
            server.set(null, null);
        }
    }

    @Test
    void theSaddleGetsAFlagAfterTheModelsOwnAnimations() {
        String hide = BedrockEntityBundleExporter.hideAnimationName("Saddle");
        assertEquals(List.of("idle", hide), BedrockEntityBundleExporter.exportedAnimationNames(saddled));
        assertEquals(List.of("idle"), BedrockEntityBundleExporter.exportedAnimationNames(plain));
    }

    @Test
    void itsAnimationShrinksTheSaddleToNothingAndIdleStillExists() throws Exception {
        Path root = directory.resolve("bundle");
        invokeAnimationWriter("writeAnimations", root, saddled);
        JsonObject animations = read(root.resolve("animations/" + saddled.getID() + ".animation.json"))
                .getAsJsonObject("animations");
        String prefix = "animation.fmm." + saddled.getID() + ".";
        assertNotNull(animations.get(prefix + key("idle")), "a model without animations still idles: " + animations.keySet());
        JsonObject hide = animations.getAsJsonObject(prefix + key(BedrockEntityBundleExporter.hideAnimationName("saddle")));
        assertNotNull(hide, animations.keySet().toString());
        assertTrue(hide.get("loop").getAsBoolean());
        JsonObject bones = hide.getAsJsonObject("bones");
        assertEquals(List.of("saddle"), new ArrayList<>(bones.keySet()));
        assertEquals(0D, bones.getAsJsonObject("saddle").get("scale").getAsDouble(), 1e-9);
    }

    @Test
    void itsControllerPlaysItOnItsOwnFlag() throws Exception {
        Path root = directory.resolve("bundle");
        invokeAnimationWriter("writeAnimationControllers", root, saddled);
        String hide = key(BedrockEntityBundleExporter.hideAnimationName("saddle"));
        JsonObject controller = read(root.resolve("animation_controllers/" + saddled.getID()
                + ".animation_controllers.json")).getAsJsonObject("animation_controllers")
                .getAsJsonObject("controller.animation.fmm." + saddled.getID() + "." + hide);
        assertNotNull(controller);
        JsonObject play = controller.getAsJsonObject("states").getAsJsonObject("play");
        assertEquals("anim_" + hide, play.getAsJsonArray("animations").get(0).getAsString());
        assertFalse(play.has("blend_transition"), "it goes at once");
        String toPlay = controller.getAsJsonObject("states").getAsJsonObject("stop").getAsJsonArray("transitions")
                .get(0).getAsJsonObject().get("play").getAsString();
        // The second flag: bit 2 of the first animation property.
        assertEquals("math.mod(math.floor(query.property('" + BedrockEntityBundleExporter.animationPropertyName(0)
                + "') / 2), 2) == 1", toPlay);

        Method writer = BedrockEntityBundleExporter.class.getDeclaredMethod("writeEntity", File.class, String.class,
                textureInfoClass(), FileModelConverter.class);
        writer.setAccessible(true);
        writer.invoke(null, root.toFile(), saddled.getID(), textureInfo(), saddled);
        JsonObject description = read(root.resolve("entity/" + saddled.getID() + ".entity.json"))
                .getAsJsonObject("minecraft:client_entity").getAsJsonObject("description");
        JsonArray animate = description.getAsJsonObject("scripts").getAsJsonArray("animate");
        assertTrue(animate.toString().contains("control_" + hide), animate.toString());
    }

    private FileModelConverter model(String name, boolean withSaddle) throws Exception {
        List<Object> bodyChildren = new ArrayList<>(List.of("cube-body-" + name));
        List<Object> elements = new ArrayList<>(List.of(cube("cube-body-" + name, -4, 0, -4, 4, 10, 4)));
        if (withSaddle) {
            elements.add(cube("cube-saddle-" + name, -3, 8, -2, 3, 11, 3));
            bodyChildren.add(Map.of("name", "Saddle", "uuid", "bone-saddle-" + name, "origin", List.of(0, 10, 0),
                    "children", List.of("cube-saddle-" + name)));
        }
        Path source = directory.resolve(name + ".bbmodel");
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", elements,
                "outliner", List.of(Map.of("name", "body", "uuid", "bone-body-" + name, "origin", List.of(0, 5, 0),
                        "children", bodyChildren)))));
        return new FileModelConverter(source.toFile());
    }

    private static String key(String animation) throws Exception {
        Method key = BedrockEntityBundleExporter.class.getDeclaredMethod("animationKey", String.class);
        key.setAccessible(true);
        return (String) key.invoke(null, animation);
    }

    private void invokeAnimationWriter(String method, Path root, FileModelConverter converter) throws Exception {
        Method writer = BedrockEntityBundleExporter.class.getDeclaredMethod(method, File.class, String.class,
                FileModelConverter.class, List.class);
        writer.setAccessible(true);
        writer.invoke(null, root.toFile(), converter.getID(), converter, BedrockEntityBundleExporter.visualBones(converter));
    }

    private static Class<?> textureInfoClass() throws ClassNotFoundException {
        return Class.forName(BedrockEntityBundleExporter.class.getName() + "$BedrockTextureInfo");
    }

    private static Object textureInfo() throws Exception {
        Constructor<?> constructor = textureInfoClass().getDeclaredConstructor(String.class, int.class, int.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance("textures/entity/saddle_export/texture", 16, 16, List.of());
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
