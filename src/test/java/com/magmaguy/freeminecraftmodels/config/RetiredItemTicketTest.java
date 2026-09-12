package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Temporary coverage for the retired equipped-item formats reported in ticket #220. */
class RetiredItemTicketTest {
    @Test
    void scriptedCustomItemIsClassifiedAsRetiredWithoutRevivingItsScript() throws Exception {
        String source = new String(
                getClass().getResourceAsStream("/ticket-220/formula-7-v1-retired.yml")
                        .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        PropScriptConfigFields fields = parse(source);

        assertTrue(fields.getUnavailableReason().startsWith("retired scripted item format"));
        assertTrue(fields.getFileConfiguration().getStringList("scripts")
                .contains("fmm_craftenmine_basic_item_pack_formula_7_sword.lua"));
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
