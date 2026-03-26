package com.magmaguy.freeminecraftmodels.config.props;

import com.magmaguy.freeminecraftmodels.config.props.premade.InvulnerablePropConfig;
import com.magmaguy.freeminecraftmodels.config.props.premade.PickupablePropConfig;
import com.magmaguy.freeminecraftmodels.config.props.premade.StorageDoublePropConfig;
import com.magmaguy.freeminecraftmodels.config.props.premade.StorageSinglePropConfig;
import com.magmaguy.magmacore.util.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class PropScriptLuaConfig {
    public PropScriptLuaConfig(File scriptsFolder) {
        writePremadeScript(scriptsFolder, new InvulnerablePropConfig());
        writePremadeScript(scriptsFolder, new PickupablePropConfig());
        writePremadeScript(scriptsFolder, new StorageSinglePropConfig());
        writePremadeScript(scriptsFolder, new StorageDoublePropConfig());
    }

    private void writePremadeScript(File scriptsFolder, PropScriptLuaConfigFields config) {
        try {
            File scriptFile = new File(scriptsFolder, config.getFilename());
            if (!scriptFile.exists()) {
                scriptsFolder.mkdirs();
                Files.writeString(scriptFile.toPath(), config.getSource(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Logger.warn("Failed to create prop script: " + config.getFilename());
        }
    }
}
