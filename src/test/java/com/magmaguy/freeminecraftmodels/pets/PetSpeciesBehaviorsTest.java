package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.pets.behavior.PetBehaviorEntry;
import com.magmaguy.freeminecraftmodels.pets.behavior.PetPrograms;
import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pet file's behaviors list, its variants and their own lists, and the modules a pet wearing a model runs. Loading a
 * whole species needs a server's registries, so this reads the parts the way the loader does; the species' own
 * listsBehaviors and behaviors(model) hand these results on.
 */
class PetSpeciesBehaviorsTest {
    private static final String FILE = "jellyfish.pet.yml";
    private static final PetBehaviorEntry STAY = new PetBehaviorEntry("stay", Map.of());
    private static final PetBehaviorEntry DRIFT = new PetBehaviorEntry("drift", Map.of("radius", 8));

    private static YamlConfiguration config(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    private static List<PetSpecies.Variant> variants(String yaml, List<String> warnings) throws InvalidConfigurationException {
        return PetSpecies.variants(config(yaml).get("variants"), FILE, warnings::add);
    }

    @Test
    void variantsAreTextOrMapsWithTheirOwnBehaviors() throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                variants:
                - em_pet_jellyfish_blue:3
                - model: em_pet_jellyfish_gold
                  weight: 0.5
                  behaviors:
                  - night_glow: { light: 12 }
                """);
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(new PetSpecies.Variant("em_pet_jellyfish_blue", 3D, List.of()),
                        new PetSpecies.Variant("em_pet_jellyfish_gold", 0.5D,
                                List.of(new PetBehaviorEntry("night_glow", Map.of("light", 12))))),
                PetSpecies.variants(config.getList("variants"), "jellyfish.pet.yml", warnings::add));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aFileWithoutVariantsHasNone() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(), variants("model: em_pet_jellyfish\n", warnings));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aMapVariantTakesItsWeightAsTextThatReadsAsANumberAndDefaultsToOne() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(new PetSpecies.Variant("em_pet_jellyfish_gold", 0.5D, List.of()),
                new PetSpecies.Variant("em_pet_jellyfish_blue", 1D, List.of()),
                new PetSpecies.Variant("em_pet_jellyfish_red", 0D, List.of())), variants("""
                variants:
                - model: em_pet_jellyfish_gold
                  weight: "0.5"
                - model: em_pet_jellyfish_blue
                - em_pet_jellyfish_red:0
                """, warnings));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aBadWeightWarnsAndCountsAsOneAndANegativeOneAsZero() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(new PetSpecies.Variant("em_pet_jellyfish_blue", 1D, List.of()),
                new PetSpecies.Variant("em_pet_jellyfish_teal", 1D, List.of()),
                new PetSpecies.Variant("em_pet_jellyfish_red", 0D, List.of()),
                new PetSpecies.Variant("em_pet_jellyfish_gold", 1D, List.of()),
                new PetSpecies.Variant("em_pet_jellyfish_pink", 1D, List.of()),
                new PetSpecies.Variant("em_pet_jellyfish_grey", 0D, List.of())), variants("""
                variants:
                - em_pet_jellyfish_blue:x
                - "em_pet_jellyfish_teal:"
                - em_pet_jellyfish_red:-2
                - model: em_pet_jellyfish_gold
                  weight: heavy
                - model: em_pet_jellyfish_pink
                  weight: .nan
                - model: em_pet_jellyfish_grey
                  weight: -0.5
                """, warnings));
        assertEquals(List.of(
                "jellyfish.pet.yml, variant em_pet_jellyfish_blue: weight must be a number, not \"x\"; using 1.",
                "jellyfish.pet.yml, variant em_pet_jellyfish_teal: weight has no value; using 1.",
                "jellyfish.pet.yml, variant em_pet_jellyfish_red: weight must be 0 or more, not -2; using 0.",
                "jellyfish.pet.yml, variant em_pet_jellyfish_gold: weight must be a number, not \"heavy\"; using 1.",
                "jellyfish.pet.yml, variant em_pet_jellyfish_pink: weight must be a number, not .nan; using 1.",
                "jellyfish.pet.yml, variant em_pet_jellyfish_grey: weight must be 0 or more, not -0.5; using 0."), warnings);
    }

    @Test
    void entriesWithoutAModelWarnAndAreSkipped() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(new PetSpecies.Variant("em_pet_jellyfish_blue", 1D, List.of())), variants("""
                variants:
                -
                - ":3"
                - weight: 2
                  behaviors: [glow]
                - model: ""
                  weight: 2
                - em_pet_jellyfish_gold: 3
                - [em_pet_jellyfish_red, 2]
                - em_pet_jellyfish_blue
                """, warnings));
        assertEquals(List.of(
                "jellyfish.pet.yml: variants has an empty entry; skipped.",
                "jellyfish.pet.yml: skipping \":3\" in variants; it names no model.",
                "jellyfish.pet.yml: skipping \"weight: 2, behaviors: [glow]\" in variants; a variant written as a map needs "
                        + "\"model: <model name>\".",
                "jellyfish.pet.yml: skipping \"model: , weight: 2\" in variants; its model must be a model's name.",
                "jellyfish.pet.yml: skipping \"em_pet_jellyfish_gold: 3\" in variants; write \"- em_pet_jellyfish_gold:<weight>\" "
                        + "with no space after the colon, or \"- model: em_pet_jellyfish_gold\" with weight and behaviors "
                        + "lined up below it.",
                "jellyfish.pet.yml: skipping \"[em_pet_jellyfish_red, 2]\" in variants; each entry is a model name, "
                        + "\"model:weight\", or a map with model, weight and behaviors."), warnings);
    }

    @Test
    void aMapVariantWarnsAboutKeysItDoesNotTakeAndNamesItselfInItsBehaviorsWarnings() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(new PetSpecies.Variant("em_pet_jellyfish_gold", 1D, List.of(STAY))), variants("""
                variants:
                - model: em_pet_jellyfish_gold
                  behaviours: [glow]
                  behaviors: [stay, stay]
                """, warnings));
        assertEquals(List.of("jellyfish.pet.yml, variant em_pet_jellyfish_gold: a variant has no setting behaviours (its "
                        + "settings are model, weight and behaviors); ignored.",
                "jellyfish.pet.yml, variant em_pet_jellyfish_gold: behaviors lists stay twice; keeping the first."), warnings);
    }

    @Test
    void aVariantBehaviorsValueThatIsNotAListAddsNothing() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(new PetSpecies.Variant("em_pet_jellyfish_gold", 1D, List.of())), variants("""
                variants:
                - model: em_pet_jellyfish_gold
                  behaviors: night_glow
                """, warnings));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith("jellyfish.pet.yml, variant em_pet_jellyfish_gold: behaviors must be a list"),
                warnings.toString());
    }

    @Test
    void variantsThatAreNotAListWarnAndLeaveTheSpeciesModel() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(), variants("variants: em_pet_jellyfish_gold\n", warnings));
        assertEquals(List.of("jellyfish.pet.yml: variants must be a list, one model per line starting with \"- \"; the "
                + "species wears its own model."), warnings);
    }

    @Test
    void aFileListsBehaviorsOnlyWithAList() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertNull(PetSpecies.listedBehaviors(config("model: em_pet_jellyfish\n"), FILE, warnings::add));
        assertEquals(List.of(), warnings);
        assertNull(PetSpecies.listedBehaviors(config("behaviors: drift\n"), FILE, warnings::add));
        assertEquals(1, warnings.size(), "a value that is not a list is reported: " + warnings);
        assertEquals(List.of(), PetSpecies.listedBehaviors(config("behaviors: []\n"), FILE, warnings::add),
                "an empty list is a list: the species runs nothing");
        assertEquals(List.of(STAY, DRIFT), PetSpecies.listedBehaviors(config("""
                behaviors:
                - stay
                - drift: { radius: 8 }
                """), FILE, warnings::add));
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void aPetRunsItsLocomotionsDefaultsWithoutAListAndTheListWithOne() {
        PetSpecies.Variant plain = new PetSpecies.Variant("em_pet_jellyfish_blue", 1D, List.of());
        assertEquals(PetPrograms.defaults(MindBodyLocomotion.AQUATIC),
                PetSpecies.behaviors(null, MindBodyLocomotion.AQUATIC, List.of(plain), "em_pet_jellyfish_blue"));
        assertEquals(List.of(STAY, DRIFT),
                PetSpecies.behaviors(List.of(STAY, DRIFT), MindBodyLocomotion.AQUATIC, List.of(plain), "em_pet_jellyfish_blue"));
        assertEquals(List.of(), PetSpecies.behaviors(List.of(), MindBodyLocomotion.AQUATIC, List.of(plain), "em_pet_jellyfish"));
    }

    @Test
    void aVariantAddsModulesAndRetunesTheSpecies() {
        PetBehaviorEntry glow = new PetBehaviorEntry("night_glow", Map.of("light", 12));
        List<PetSpecies.Variant> variants = List.of(new PetSpecies.Variant("em_pet_jellyfish_gold", 1D, List.of(glow)),
                new PetSpecies.Variant("em_pet_jellyfish_wide", 1D, List.of(new PetBehaviorEntry("drift", Map.of("radius", 16)))));
        assertEquals(List.of(STAY, DRIFT, glow),
                PetSpecies.behaviors(List.of(STAY, DRIFT), MindBodyLocomotion.FLYING, variants, "em_pet_jellyfish_gold"));
        assertEquals(List.of(STAY, new PetBehaviorEntry("drift", Map.of("radius", 16))),
                PetSpecies.behaviors(List.of(STAY, DRIFT), MindBodyLocomotion.FLYING, variants, "em_pet_jellyfish_wide"));
        List<PetBehaviorEntry> defaults = new ArrayList<>(PetPrograms.defaults(MindBodyLocomotion.FLYING));
        defaults.add(glow);
        assertEquals(defaults, PetSpecies.behaviors(null, MindBodyLocomotion.FLYING, variants, "em_pet_jellyfish_gold"),
                "a variant adds to the locomotion's defaults when the species lists none");
        assertEquals(List.of(STAY, DRIFT),
                PetSpecies.behaviors(List.of(STAY, DRIFT), MindBodyLocomotion.FLYING, variants, "em_pet_jellyfish"),
                "a model no variant names runs the species' list");
    }

    @Test
    void aVariantFindsItsModelWhateverTheCaseTheFileWroteItIn() {
        PetBehaviorEntry glow = new PetBehaviorEntry("night_glow", Map.of());
        List<PetSpecies.Variant> variants = List.of(new PetSpecies.Variant("EM_Pet_Jellyfish_Gold", 1D, List.of(glow)));
        assertEquals(List.of(STAY, glow),
                PetSpecies.behaviors(List.of(STAY), MindBodyLocomotion.FLYING, variants, "em_pet_jellyfish_gold"));
    }
}
