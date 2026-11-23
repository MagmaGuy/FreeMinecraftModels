package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Temporary coverage for the retired equipped-item formats reported in ticket #220. */
class RetiredItemTicketTest {
    @Test
    void scriptedCustomItemIsClassifiedAsRetiredWithoutRevivingItsScript() throws Exception {
        // A stand-in in the retired format. The item the ticket was about is paid content and
        // stays out of this public repository.
        String source = """
                isEnabled: true
                material: IRON_SWORD
                name: "Stand-in scripted sword"
                lore:
                - "&7An item in the retired scripted format."
                enchantments:
                - "SHARPNESS,5"
                - "UNBREAKING,1"
                scripts:
                - standin_scripted_sword.lua
                """;
        PropScriptConfigFields fields = parse(source);

        assertTrue(fields.getUnavailableReason().startsWith("retired scripted item format"));
        assertTrue(fields.getFileConfiguration().getStringList("scripts")
                .contains("standin_scripted_sword.lua"));
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
