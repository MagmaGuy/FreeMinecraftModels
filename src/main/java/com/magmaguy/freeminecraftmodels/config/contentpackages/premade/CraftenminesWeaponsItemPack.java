package com.magmaguy.freeminecraftmodels.config.contentpackages.premade;

import com.magmaguy.freeminecraftmodels.config.contentpackages.ContentPackageConfigFields;

import java.util.List;

public class CraftenminesWeaponsItemPack extends ContentPackageConfigFields {
    public CraftenminesWeaponsItemPack() {
        super("craftenmines_weapons_item_pack",
                true,
                "&2Craftenmine's Weapons Item Pack",
                List.of("&fA collection of weapon item models by Craftenmine."),
                "https://nightbreak.io/plugin/freeminecraftmodels/",
                "fmm_craftenmine_weapons_items_pack_v1");
        setNightbreakSlug("craftenmines-weapons-item-pack");
    }
}
