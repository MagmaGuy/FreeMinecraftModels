package com.magmaguy.freeminecraftmodels.config.props;

import com.magmaguy.magmacore.util.Logger;
import org.reflections.Reflections;

import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

public class PropScriptLuaConfig {
    public PropScriptLuaConfig(File scriptsFolder) {
        Set<Class<? extends PropScriptLuaConfigFields>> premadeClasses =
                new Reflections("com.magmaguy.freeminecraftmodels.config.props.premade")
                        .getSubTypesOf(PropScriptLuaConfigFields.class);

        for (Class<? extends PropScriptLuaConfigFields> clazz : premadeClasses) {
            if (Modifier.isAbstract(clazz.getModifiers())) continue;
            try {
                PropScriptLuaConfigFields config = clazz.getDeclaredConstructor().newInstance();
                File scriptFile = new File(scriptsFolder, config.getFilename());
                if (!scriptFile.exists()) {
                    scriptFile.getParentFile().mkdirs();
                    Files.writeString(scriptFile.toPath(), config.getSource(), StandardCharsets.UTF_8);
                    Logger.info("Created default prop script: " + config.getFilename());
                }
            } catch (Exception e) {
                Logger.warn("Failed to create premade prop script from " + clazz.getSimpleName());
                e.printStackTrace();
            }
        }
    }
}
