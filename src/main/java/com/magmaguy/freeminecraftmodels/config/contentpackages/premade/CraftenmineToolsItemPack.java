package com.magmaguy.freeminecraftmodels.config.contentpackages.premade;

import com.magmaguy.freeminecraftmodels.config.contentpackages.ContentPackageConfigFields;

import java.util.List;

public class CraftenmineToolsItemPack extends ContentPackageConfigFields {
    public CraftenmineToolsItemPack() {
        super("craftenmine_tools_item_pack",
                true,
                "&2Craftenmine Tools Item Pack",
                List.of("&fA collection of tool item models by Craftenmine."),
                "https://nightbreak.io/plugin/freeminecraftmodels/",
                "fmm_craftenmine_tools_items_pack");
        setNightbreakSlug("craftenmine-tools-item-pack");
    }
}
