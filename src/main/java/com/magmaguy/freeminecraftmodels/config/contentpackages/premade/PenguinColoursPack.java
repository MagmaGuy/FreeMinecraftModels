package com.magmaguy.freeminecraftmodels.config.contentpackages.premade;

import com.magmaguy.freeminecraftmodels.config.contentpackages.ContentPackageConfigFields;

import java.util.List;

public class PenguinColoursPack extends ContentPackageConfigFields {
    public PenguinColoursPack() {
        super("penguin_colours_pack",
                true,
                "&bPenguin Colours Pack",
                List.of("&f7 more penguin colours, each with its own baby, from the emperor and rockhopper to the very",
                        "&frare albino, lutino and melanistic. Needs the Penguin Pet Pack."),
                "https://nightbreak.io/plugin/freeminecraftmodels/",
                "fmm_penguin_colours");
        setNightbreakSlug("penguin-colours-pack");
        // In final testing: /fmm setup lists it under Coming soon and nothing downloads it. Remove this line to release it.
        setComingSoon(true);
    }
}
