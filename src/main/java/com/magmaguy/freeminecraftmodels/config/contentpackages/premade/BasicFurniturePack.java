package com.magmaguy.freeminecraftmodels.config.contentpackages.premade;

import com.magmaguy.freeminecraftmodels.config.contentpackages.ContentPackageConfigFields;

import java.util.List;

public class BasicFurniturePack extends ContentPackageConfigFields {
    public BasicFurniturePack() {
        super("basic_furniture_pack",
                true,
                "&2Basic Furniture Pack",
                List.of("&fA collection of basic furniture models with interactive scripts."),
                "https://nightbreak.io/plugin/freeminecraftmodels/",
                "Basic Furniture Pack");
        setNightbreakSlug("basic-furniture-pack");
    }
}
