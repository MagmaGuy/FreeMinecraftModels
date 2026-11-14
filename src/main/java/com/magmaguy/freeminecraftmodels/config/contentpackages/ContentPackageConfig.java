package com.magmaguy.freeminecraftmodels.config.contentpackages;

import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.magmacore.config.CustomConfig;

public class ContentPackageConfig extends CustomConfig {
    public ContentPackageConfig() {
        super("content_packages", "com.magmaguy.freeminecraftmodels.config.contentpackages.premade", ContentPackageConfigFields.class);
        for (String key : super.getCustomConfigFieldsHashMap().keySet()) {
            ContentPackageConfigFields fields = (ContentPackageConfigFields) super.getCustomConfigFieldsHashMap().get(key);
            new FMMPackage(fields);
        }
    }
}
