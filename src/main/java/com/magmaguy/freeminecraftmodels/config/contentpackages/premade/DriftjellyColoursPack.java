package com.magmaguy.freeminecraftmodels.config.contentpackages.premade;

import com.magmaguy.freeminecraftmodels.config.contentpackages.ContentPackageConfigFields;

import java.util.List;

public class DriftjellyColoursPack extends ContentPackageConfigFields {
    public DriftjellyColoursPack() {
        super("driftjelly_colours_pack",
                true,
                "&bDriftjelly Colours Pack",
                List.of("&f10 more driftjelly colours, each with its own baby, from bloodmoon and emberglow to the rare",
                        "&ffrostveil, starfall and sunspike. Needs the Driftjelly Pet Pack."),
                "https://nightbreak.io/plugin/freeminecraftmodels/",
                "fmm_driftjelly_colours");
        setNightbreakSlug("driftjelly-colours-pack");
        // In final testing: /fmm setup lists it under Coming soon and nothing downloads it. Remove this line to release it.
        setComingSoon(true);
    }
}
