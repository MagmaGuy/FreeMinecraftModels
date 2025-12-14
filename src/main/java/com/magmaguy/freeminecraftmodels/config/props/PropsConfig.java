package com.magmaguy.freeminecraftmodels.config.props;

import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.CustomConfig;
import com.magmaguy.magmacore.config.CustomConfigFields;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;

public class PropsConfig extends CustomConfig {
    public static PropsConfig INSTANCE;
    @Getter
    private static HashMap<String, PropsConfigFields> propsConfigs = new HashMap<>();

    public PropsConfig() {
        super("props", "com.magmaguy.freeminecraftmodels.config.props.premade", PropsConfigFields.class);
        INSTANCE = this;
        propsConfigs = new HashMap<>();
        for (String key : super.getCustomConfigFieldsHashMap().keySet())
            if (super.getCustomConfigFieldsHashMap().get(key).isEnabled())
                propsConfigs.put(key, (PropsConfigFields) super.getCustomConfigFieldsHashMap().get(key));
    }

    public static PropsConfigFields addPropConfigurationFile(String propFilename, Player player) {
        if (!propsConfigs.containsKey(propFilename)) {
            PropsConfigFields newProp = new PropsConfigFields(propFilename, true);
            propsConfigs.put(propFilename, newProp);
            INSTANCE.initialize(newProp);

            // Only show this message if a new config was created
            Logger.sendMessage(player, "Created new prop config file at ~/plugins/FreeMinecraftModels/props/" + propFilename + ".yml");
            return newProp;
        } else {
            // If the config already exists, inform the user where they can edit the values
            Logger.sendMessage(player, "Using existing prop config. You can edit properties at ~/plugins/FreeMinecraftModels/props/" + propFilename + ".yml");
            return propsConfigs.get(propFilename);
        }
    }

    private void initialize(CustomConfigFields customConfigFields) {
        //Create configuration file from defaults if it does not exist
        File file = ConfigurationEngine.fileCreator("props", customConfigFields.getFilename());
        //Get config file
        FileConfiguration fileConfiguration = ConfigurationEngine.fileConfigurationCreator(file);

        //Associate config
        customConfigFields.setFile(file);
        customConfigFields.setFileConfiguration(fileConfiguration);

        //Parse actual fields and load into RAM to be used
        customConfigFields.processConfigFields();

        //Save all configuration values as they exist
        ConfigurationEngine.fileSaverCustomValues(fileConfiguration, file);

        //if (customConfigFields.isEnabled)
        //Store for use by the plugin
        addCustomConfigFields(file.getName(), customConfigFields);
    }

    public static void shutdown() {
        propsConfigs.clear();
        INSTANCE = null;
    }
}