package com.magmaguy.freeminecraftmodels.config.props;

import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.CustomConfig;
import com.magmaguy.magmacore.config.CustomConfigFields;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

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

    public static PropsConfigFields addPropConfigurationFile(String propFilename) {
        PropsConfig.getPropsConfigs().put(propFilename, new PropsConfigFields(propFilename, true));
        PropsConfigFields newProp = PropsConfig.getPropsConfigs().get(propFilename);
        propsConfigs.put(propFilename, newProp);
        INSTANCE.initialize(newProp);
        return newProp;
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

}
