package com.magmaguy.freeminecraftmodels.config.props;

import com.magmaguy.magmacore.config.CustomConfig;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.command.CommandSender;

import java.util.HashMap;

public class PropsConfig extends CustomConfig {
    private static HashMap<String, PropsConfigFields> propsConfigs = new HashMap<>();

    public PropsConfig() {
        super("props", "com.magmaguy.freeminecraftmodels.config.props.premade", PropsConfigFields.class);

//        Files.isDirectory(Path.of(MetadataHandler.PLUGIN.getDataFolder().toString() + File.separatorChar + ""))
//        if (MetadataHandler.PLUGIN.getDataFolder())
        propsConfigs = new HashMap<>();
        for (String key : super.getCustomConfigFieldsHashMap().keySet())
            if (super.getCustomConfigFieldsHashMap().get(key).isEnabled()) {
                propsConfigs.put(key, (PropsConfigFields) super.getCustomConfigFieldsHashMap().get(key));
                //todo: initialization logic goes here
            }
    }

    public static void CreateProp(CommandSender commandSender, String propFilename) {
        if (propsConfigs.containsKey(propFilename)) {
            Logger.warn("[FreeMinecraftModel] This prop already exists! You can simply add more locations.");
            return;
        }
        propsConfigs.put(propFilename, new PropsConfigFields(propFilename, true));
        //todo: this should create a configuration file based on the imported file in the models folder if a command is run
    }
}
