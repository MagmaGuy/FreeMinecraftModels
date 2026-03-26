package com.magmaguy.freeminecraftmodels.config.contentpackages.premade;

import com.magmaguy.freeminecraftmodels.config.contentpackages.ContentPackageConfigFields;

import java.util.List;

public class EliteMobsPropPack extends ContentPackageConfigFields {
    public EliteMobsPropPack() {
        super("elitemobs_prop_pack",
                true,
                "&2EliteMobs Prop Pack",
                List.of("&fShared decorative model assets for EliteMobs-powered content."),
                "https://nightbreak.io/plugin/freeminecraftmodels/",
                "em_dungeon_prop_pack");
        setNightbreakSlug("elitemobs-prop-pack");
    }
}
