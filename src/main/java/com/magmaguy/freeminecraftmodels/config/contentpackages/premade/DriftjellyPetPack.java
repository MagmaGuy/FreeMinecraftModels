package com.magmaguy.freeminecraftmodels.config.contentpackages.premade;

import com.magmaguy.freeminecraftmodels.config.contentpackages.ContentPackageConfigFields;

import java.util.List;

public class DriftjellyPetPack extends ContentPackageConfigFields {
    public DriftjellyPetPack() {
        super("driftjelly_pet_pack",
                true,
                "&bDriftjelly Pet Pack",
                List.of("&fFloating jellyfish pets in 11 colours, each with its own baby: tame them with glow berries,",
                        "&fbreed them and keep them."),
                "https://nightbreak.io/plugin/freeminecraftmodels/",
                "fmm_driftjelly");
        setNightbreakSlug("driftjelly-pet-pack");
        // In final testing: /fmm setup lists it under Coming soon and nothing downloads it. Remove this line to release it.
        setComingSoon(true);
    }
}
