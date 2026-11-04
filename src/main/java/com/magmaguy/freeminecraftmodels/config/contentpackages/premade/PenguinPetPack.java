package com.magmaguy.freeminecraftmodels.config.contentpackages.premade;

import com.magmaguy.freeminecraftmodels.config.contentpackages.ContentPackageConfigFields;

import java.util.List;

public class PenguinPetPack extends ContentPackageConfigFields {
    public PenguinPetPack() {
        super("penguin_pet_pack",
                true,
                "&bPenguin Pet Pack",
                List.of("&fPenguin pets that waddle, swim and peck: tame them with fish, breed them, and saddle a",
                        "&fgrown one to slide across snow and ice. The Penguin Colours Pack adds 7 more colours."),
                "https://nightbreak.io/plugin/freeminecraftmodels/",
                "fmm_penguin");
        setNightbreakSlug("penguin-pet-pack");
        // In final testing: /fmm setup lists it under Coming soon and nothing downloads it. Remove this line to release it.
        setComingSoon(true);
    }
}
