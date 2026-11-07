package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A pet file's hiddenBones: the bones its model starts without, such as the fish a penguin carries home. */
class PetHiddenBonesTest {
    @Test
    void noneUnlessListed() {
        assertEquals(List.of(), PetSpecies.hiddenBones(new YamlConfiguration()));
    }

    @Test
    void namesAreTrimmedOnceEachAndBlanksDropped() throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("hiddenBones: [' fish ', '', fish, lantern]");
        assertEquals(List.of("fish", "lantern"), PetSpecies.hiddenBones(config));
    }
}
