package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Temporary coverage for the retired equipped-item formats reported in ticket #220. */
class RetiredItemTicketTest {
    @Test
    void scriptedCustomItemIsClassifiedAsRetiredWithoutRevivingItsScript() throws Exception {
        PropScriptConfigFields fields = parse("""
                material: BLAZE_ROD
                scripts: [old_item_hook.lua]
                """);

        assertTrue(fields.getUnavailableReason().startsWith("retired scripted item format"));
    }

    private static PropScriptConfigFields parse(String yaml) throws Exception {
        PropScriptConfigFields fields = new PropScriptConfigFields("ticket-220.yml", true);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        fields.setFileConfiguration(configuration);
        fields.processConfigFields();
        return fields;
    }
}
