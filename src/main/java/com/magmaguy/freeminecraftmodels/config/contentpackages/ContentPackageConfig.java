package com.magmaguy.freeminecraftmodels.config.contentpackages;

import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.magmacore.config.CustomConfig;
import lombok.Getter;

import java.util.HashMap;

public class ContentPackageConfig extends CustomConfig {
    @Getter
    private static final HashMap<String, ContentPackageConfigFields> contentPackages = new HashMap<>();

    public ContentPackageConfig() {
        super("content_packages", "com.magmaguy.freeminecraftmodels.config.contentpackages.premade", ContentPackageConfigFields.class);
        contentPackages.clear();
        for (String key : super.getCustomConfigFieldsHashMap().keySet()) {
            ContentPackageConfigFields fields = (ContentPackageConfigFields) super.getCustomConfigFieldsHashMap().get(key);
            contentPackages.put(key, fields);
            new FMMPackage(fields);
        }
    }
}
