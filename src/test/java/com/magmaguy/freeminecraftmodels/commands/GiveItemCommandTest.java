package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MockBukkitTestSupport;
import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import com.magmaguy.magmacore.command.CommandData;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiveItemCommandTest extends MockBukkitTestSupport {

    @AfterEach
    void clearItemDefinitions() {
        ItemScriptManager.getItemDefinitions().clear();
    }

    @Test
    void giveItemCommandAddsConfiguredCustomItemToPlayerInventory() {
        PropScriptConfigFields config = new PropScriptConfigFields("storm_wand.yml", true);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("material", "BLAZE_ROD");
        yaml.set("name", "&bStorm Wand");
        config.setFileConfiguration(yaml);
        config.processConfigFields();
        ItemScriptManager.getItemDefinitions().put("storm_wand", config);

        PlayerMock player = server.addPlayer("Tiago");
        GiveItemCommand command = new GiveItemCommand();

        command.execute(new CommandData(player, new String[]{"giveitem", "storm_wand"}, command));

        ItemStack item = player.getInventory().getItem(0);
        assertNotNull(item);
        assertEquals(Material.BLAZE_ROD, item.getType());
        assertEquals("storm_wand", item.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "fmm_item_id"), PersistentDataType.STRING));
        assertTrue(player.nextMessage().contains("Gave custom item: storm_wand"));
    }
}
