package com.magmaguy.freeminecraftmodels.config.contentpackages.premade;

import com.magmaguy.freeminecraftmodels.config.contentpackages.ContentPackageConfigFields;

import java.util.List;

public class BetterStructuresPropPack extends ContentPackageConfigFields {
    public BetterStructuresPropPack() {
        super("betterstructures_prop_pack",
                true,
                "&2BetterStructures Prop Pack",
                List.of("&fShared decorative model assets for BetterStructures content."),
                "https://nightbreak.io/plugin/freeminecraftmodels/",
                "bs prop pack");
        setNightbreakSlug("betterstructures-prop-pack");
        setContentFilePrefixes(List.of("bs prop pack", "bs_", "BetterStructures Props Pack"));
    }
}
