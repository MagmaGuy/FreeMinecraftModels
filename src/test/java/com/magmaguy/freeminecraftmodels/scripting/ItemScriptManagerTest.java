package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MockBukkitTestSupport;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemScriptManagerTest extends MockBukkitTestSupport {

    @AfterEach
    void clearItemDefinitions() {
        ItemScriptManager.getItemDefinitions().clear();
        ItemScriptManager.getItemSourceFiles().clear();
    }

    @Test
    void scanForCustomItemsLoadsEnabledBaseModelConfigsOnly(@TempDir Path tempDir) throws Exception {
        Path models = tempDir.resolve("models");
        Files.createDirectories(models);
        Files.writeString(models.resolve("storm_wand.bbmodel"), "{}");
        writeModelConfig(models.resolve("storm_wand.yml"), true, "BLAZE_ROD");
        writeModelConfig(models.resolve("storm_wand_draw_start.yml"), true, "BOW");
        writeModelConfig(models.resolve("disabled_item.yml"), false, "PAPER");
        writeModelConfig(models.resolve("plain_prop.yml"), true, "");

        ItemScriptManager.scanForCustomItems(models.toFile());

        assertTrue(ItemScriptManager.getItemDefinitions().containsKey("storm_wand"));
        assertFalse(ItemScriptManager.getItemDefinitions().containsKey("storm_wand_draw_start"));
        assertFalse(ItemScriptManager.getItemDefinitions().containsKey("disabled_item"));
        assertFalse(ItemScriptManager.getItemDefinitions().containsKey("plain_prop"));
        assertEquals(models.resolve("storm_wand.bbmodel").toFile(),
                ItemScriptManager.getItemSourceFiles().get("storm_wand"));
    }

    private static void writeModelConfig(Path path, boolean enabled, String material) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("isEnabled", enabled);
        if (material != null && !material.isEmpty()) {
            config.set("material", material);
        }
        config.save(path.toFile());
    }
}
