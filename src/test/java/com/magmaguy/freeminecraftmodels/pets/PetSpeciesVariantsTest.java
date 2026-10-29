package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A variant's settings past its model, weight and behaviors: the name it shows, the model its babies wear, the biomes
 * it spawns wild in and how often it breeds true. Loading a whole species needs a server's registries, so this reads
 * the variants the way the loader does.
 */
class PetSpeciesVariantsTest {
    private static final String FILE = "jellyfish.pet.yml";
    private static final String BLUE = "em_pet_jellyfish_blue", WAR = "em_pet_jellyfish_man_o_war",
            WAR_BABY = "em_pet_jellyfish_man_o_war_baby";
    private static final NamespacedKey WARM_OCEAN = NamespacedKey.minecraft("warm_ocean"),
            BEACH = NamespacedKey.minecraft("beach");

    private static List<PetSpecies.Variant> variants(String yaml, List<String> warnings) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return PetSpecies.variants(config.get("variants"), FILE, warnings::add);
    }

    @Test
    void aVariantMapCarriesANameABabyModelBiomesAndAnInheritChance() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        List<PetSpecies.Variant> variants = variants("""
                variants:
                - model: em_pet_jellyfish_man_o_war
                  name: '&9Portuguese Man o'' War'
                  weight: 0.2
                  babyModel: em_pet_jellyfish_man_o_war_baby
                  biomes: [warm_ocean, minecraft:beach]
                  inheritChance: 0.25
                """, warnings);
        assertEquals(List.of(new PetSpecies.Variant(WAR, 0.2D, List.of(), "&9Portuguese Man o' War", WAR_BABY,
                Set.of(WARM_OCEAN, BEACH), 0.25D)), variants);
        assertEquals(List.of(), warnings);
    }

    @Test
    void theTextFormAndAPlainMapKeepTheDefaults() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        List<PetSpecies.Variant> variants = variants("""
                variants:
                - em_pet_jellyfish_blue:3
                - model: em_pet_jellyfish_man_o_war
                  name:
                  babyModel: ''
                  biomes:
                """, warnings);
        for (PetSpecies.Variant variant : variants) {
            assertNull(variant.name(), variant.model());
            assertNull(variant.babyModel(), variant.model());
            assertEquals(Set.of(), variant.biomes(), variant.model());
            assertEquals(1D, variant.inheritChance(), variant.model());
        }
        assertEquals(List.of(), warnings, "blank settings count as absent");
    }

    @Test
    void anInheritChanceOutsideZeroToOneIsClampedWithAWarning() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        List<PetSpecies.Variant> variants = variants("""
                variants:
                - model: em_pet_jellyfish_red
                  inheritChance: 1.5
                - model: em_pet_jellyfish_gold
                  inheritChance: -0.2
                - model: em_pet_jellyfish_pink
                  inheritChance: often
                - model: em_pet_jellyfish_teal
                  inheritChance: "0.5"
                """, warnings);
        assertEquals(List.of(1D, 0D, 1D, 0.5D), variants.stream().map(PetSpecies.Variant::inheritChance).toList());
        assertEquals(List.of(
                "jellyfish.pet.yml, variant em_pet_jellyfish_red: inheritChance must be from 0 to 1, not 1.5; using 1.",
                "jellyfish.pet.yml, variant em_pet_jellyfish_gold: inheritChance must be from 0 to 1, not -0.2; using 0.",
                "jellyfish.pet.yml, variant em_pet_jellyfish_pink: inheritChance must be a number from 0 to 1, not \"often\"; "
                        + "using 1."), warnings);
    }

    @Test
    void biomesTakeOneNameOrAListAndSkipWhatIsNotABiomeName() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        List<PetSpecies.Variant> variants = variants("""
                variants:
                - model: em_pet_jellyfish_blue
                  biomes: warm_ocean
                - model: em_pet_jellyfish_man_o_war
                  biomes: [Warm Ocean!, beach, '']
                - model: em_pet_jellyfish_gold
                  biomes: { warm_ocean: true }
                """, warnings);
        assertEquals(List.of(Set.of(WARM_OCEAN), Set.of(BEACH), Set.of()),
                variants.stream().map(PetSpecies.Variant::biomes).toList());
        assertEquals(List.of(
                "jellyfish.pet.yml, variant em_pet_jellyfish_man_o_war: skipping \"Warm Ocean!\" in biomes; a biome is "
                        + "written like minecraft:warm_ocean.",
                "jellyfish.pet.yml, variant em_pet_jellyfish_man_o_war: skipping an empty entry in biomes; a biome is "
                        + "written like minecraft:warm_ocean.",
                "jellyfish.pet.yml, variant em_pet_jellyfish_gold: biomes must be a list of biomes like "
                        + "minecraft:warm_ocean, not { warm_ocean: true }; it spawns in every biome."), warnings);
    }

    @Test
    void aNameOrBabyModelThatIsNotTextWarnsAndIsIgnored() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        List<PetSpecies.Variant> variants = variants("""
                variants:
                - model: em_pet_jellyfish_blue
                  name: [Blue, Jellyfish]
                  babyModel: { model: em_pet_jellyfish_blue_baby }
                """, warnings);
        assertNull(variants.getFirst().name());
        assertNull(variants.getFirst().babyModel());
        assertEquals(List.of(
                "jellyfish.pet.yml, variant em_pet_jellyfish_blue: name must be text, not [Blue, Jellyfish]; ignored.",
                "jellyfish.pet.yml, variant em_pet_jellyfish_blue: babyModel must be a model's name, not "
                        + "{ model: em_pet_jellyfish_blue_baby }; ignored."), warnings);
    }

    @Test
    void anUnknownSettingWarningListsEverySettingAVariantTakes() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        variants("""
                variants:
                - model: em_pet_jellyfish_blue
                  rarity: rare
                """, warnings);
        assertEquals(List.of("jellyfish.pet.yml, variant em_pet_jellyfish_blue: a variant has no setting rarity (its "
                + "settings are model, weight, behaviors, name, babyModel, biomes and inheritChance); ignored."), warnings);
    }

    @Test
    void biomesTheServerDoesNotHaveAreReportedOncePerVariant() {
        List<PetSpecies.Variant> variants = List.of(
                new PetSpecies.Variant(BLUE, 1D, List.of(), null, null, Set.of(WARM_OCEAN), 1D),
                new PetSpecies.Variant(WAR, 1D, List.of(), null, null, Set.of(NamespacedKey.minecraft("coral_reef")), 1D));
        List<String> warnings = new ArrayList<>();
        PetSpecies.warnUnknownBiomes(variants, FILE, key -> key.equals(WARM_OCEAN), warnings::add);
        assertEquals(List.of("jellyfish.pet.yml, variant em_pet_jellyfish_man_o_war: this server has no biome "
                + "minecraft:coral_reef; it never matches."), warnings);
    }

    @Test
    void aVariantIsFoundByItsExactNameFirstThenAsAModelName() {
        PetSpecies.Variant upper = new PetSpecies.Variant("EM_Pet_Gold", 1D, List.of());
        PetSpecies.Variant lower = new PetSpecies.Variant("em_pet_gold", 1D, List.of());
        List<PetSpecies.Variant> variants = List.of(upper, lower);
        assertSame(lower, PetSpecies.variant(variants, "em_pet_gold"));
        assertSame(upper, PetSpecies.variant(variants, "EM_Pet_Gold"));
        assertSame(upper, PetSpecies.variant(variants, "em_pet_GOLD"));
        assertNull(PetSpecies.variant(variants, "em_pet_blue"));
        assertNull(PetSpecies.variant(variants, null));
    }
}
