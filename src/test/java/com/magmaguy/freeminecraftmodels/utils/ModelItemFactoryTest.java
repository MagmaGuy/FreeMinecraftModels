package com.magmaguy.freeminecraftmodels.utils;

import com.magmaguy.freeminecraftmodels.MockBukkitTestSupport;
import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelItemFactoryTest extends MockBukkitTestSupport {

    @Test
    void createModelItemTagsPlacementItemsWithModelId() {
        String modelId = "01_em_crystal_dragon";

        ItemStack item = ModelItemFactory.createModelItem(modelId, Material.PAPER);
        ItemMeta meta = item.getItemMeta();

        assertNotNull(meta);
        assertEquals(Material.PAPER, item.getType());
        assertTrue(ChatColor.stripColor(meta.getDisplayName()).contains("Crystal Dragon"));
        assertEquals(modelId, meta.getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "model_id"), PersistentDataType.STRING));
        assertFalse(meta.getPersistentDataContainer()
                .has(new NamespacedKey(plugin, "fmm_item_id"), PersistentDataType.STRING));
        assertTrue(meta.getLore().stream().anyMatch(line -> line.contains(modelId)));
    }

    @Test
    void formatModelNameRemovesKnownPrefixesAndHumanizesWords() {
        assertEquals("Crystal Dragon", ModelItemFactory.formatModelName("01_em_crystal_dragon"));
        assertEquals("Market Stall", ModelItemFactory.formatModelName("market_stall"));
    }

    @Test
    void createCustomItemUsesConfiguredItemFieldsAndCustomItemPdc() {
        PropScriptConfigFields config = new PropScriptConfigFields("storm_wand.yml", true);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("material", "BLAZE_ROD");
        yaml.set("name", "&bStorm Wand");
        yaml.set("lore", java.util.List.of("&7Calls down clean test coverage"));
        config.setFileConfiguration(yaml);
        config.processConfigFields();

        ItemStack item = ModelItemFactory.createCustomItem("storm_wand", config);
        ItemMeta meta = item.getItemMeta();

        assertNotNull(meta);
        assertEquals(Material.BLAZE_ROD, item.getType());
        assertEquals("Storm Wand", ChatColor.stripColor(meta.getDisplayName()));
        assertEquals("Calls down clean test coverage", ChatColor.stripColor(meta.getLore().get(0)));
        assertEquals("storm_wand", meta.getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "fmm_item_id"), PersistentDataType.STRING));
        assertFalse(meta.getPersistentDataContainer()
                .has(new NamespacedKey(plugin, "model_id"), PersistentDataType.STRING));
    }
}
