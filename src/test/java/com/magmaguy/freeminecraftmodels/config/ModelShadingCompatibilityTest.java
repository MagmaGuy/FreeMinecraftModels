package com.magmaguy.freeminecraftmodels.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ModelShadingCompatibilityTest {
    @Test
    void generatedBonesAndDisplayModelsPreserveShadingForOldAndNewClients(@TempDir Path temporary) throws Exception {
        Plugin previousPlugin = MetadataHandler.PLUGIN;
        Server previousServer = Bukkit.getServer();
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> temporary.toFile();
                    case "getName" -> "FreeMinecraftModels";
                    default -> defaultValue(method.getReturnType());
                });
        if (previousServer == null) {
            Bukkit.setServer((Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                    new Class<?>[]{Server.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getBukkitVersion" -> "26.3-R0.1-SNAPSHOT";
                        case "getLogger" -> Logger.getLogger("FMM-format-test");
                        default -> defaultValue(method.getReturnType());
                    }));
        }
        FileModelConverter.shutdown();
        try {
            Path models = Files.createDirectories(temporary.resolve("models"));
            JsonObject source;
            try (var stream = getClass().getResourceAsStream(
                    "/ticket-220/current/models/fmm_craftenmine_basic_item_pack_formula_7_sword.bbmodel")) {
                assertNotNull(stream);
                source = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
            source.getAsJsonArray("elements").forEach(element -> element.getAsJsonObject().addProperty("shade", false));
            Path modelFile = models.resolve("format_compat.bbmodel");
            String modelInput = source.toString();
            Files.writeString(modelFile, modelInput);
            String displayInput = """
                    {"elements":[
                      {"from":[0,0,0],"to":[1,1,1],"faces":{},"shade":false},
                      {"from":[0,0,0],"to":[1,1,1],"faces":{},"shade":true},
                      {"from":[0,0,0],"to":[1,1,1],"faces":{}},
                      {"from":[0,0,0],"to":[1,1,1],"faces":{},"shade":false,"shade_direction_override":"east"}
                    ]}
                    """;
            Path displayFile = models.resolve("format_compat.json");
            Files.writeString(displayFile, displayInput);

            ModelsFolder.initializeConfig();

            Path output = temporary.resolve("output/FreeMinecraftModels/assets/freeminecraftmodels/models");
            int checkedElements = 0;
            try (var generated = Files.walk(output.resolve("format_compat"))) {
                for (Path model : generated.filter(path -> path.toString().endsWith(".json")).toList()) {
                    JsonObject json = JsonParser.parseString(Files.readString(model)).getAsJsonObject();
                    for (var element : json.getAsJsonArray("elements")) {
                        assertFalse(element.getAsJsonObject().get("shade").getAsBoolean());
                        assertEquals(JsonParser.parseString("\"up\""), element.getAsJsonObject().get("shade_direction_override"));
                        checkedElements++;
                    }
                }
            }
            assertTrue(checkedElements > 0, "The real converter must write shaded bone geometry");
            var display = JsonParser.parseString(Files.readString(output.resolve("display/format_compat.json")))
                    .getAsJsonObject().getAsJsonArray("elements");
            assertFalse(display.get(0).getAsJsonObject().get("shade").getAsBoolean());
            assertEquals(JsonParser.parseString("\"up\""), display.get(0).getAsJsonObject().get("shade_direction_override"));
            assertTrue(display.get(1).getAsJsonObject().get("shade").getAsBoolean());
            assertFalse(display.get(1).getAsJsonObject().has("shade_direction_override"));
            assertFalse(display.get(2).getAsJsonObject().has("shade_direction_override"));
            assertEquals("east", display.get(3).getAsJsonObject().get("shade_direction_override").getAsString());
            assertEquals(modelInput, Files.readString(modelFile));
            assertEquals(displayInput, Files.readString(displayFile));
        } finally {
            FileModelConverter.shutdown();
            ItemScriptManager.shutdown();
            DisplayModelRegistry.shutdown();
            MetadataHandler.PLUGIN = previousPlugin;
            if (previousServer == null) {
                var serverField = Bukkit.class.getDeclaredField("server");
                serverField.setAccessible(true);
                serverField.set(null, null);
            }
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
