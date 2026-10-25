package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.shaded.luaj.vm2.LuaTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetBehaviorSettingsTest {
    private static final Map<String, Object> DEFAULTS = Map.of("radius", 12D, "night_only", true, "effect", "SLOWNESS",
            "blocks", List.of("CAMPFIRE"));

    @Test
    void theCreaturesValuesGoOverTheDefaults() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> merged = PetBehaviorSettings.merge("campfire", DEFAULTS,
                Map.of("radius", 8, "blocks", List.of("SOUL_CAMPFIRE")), "x.pet.yml", warnings::add);
        assertEquals(Map.of("radius", 8D, "night_only", true, "effect", "SLOWNESS", "blocks", List.of("SOUL_CAMPFIRE")), merged);
        assertEquals(List.of(), warnings);
    }

    @Test
    void wrongTypesAndUnknownNamesWarnAndKeepTheDefault() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> merged = PetBehaviorSettings.merge("campfire", DEFAULTS,
                Map.of("radius", "far", "night_only", "yes", "colour", "red", "blocks", List.of(3)), "x.pet.yml", warnings::add);
        assertEquals(DEFAULTS, merged);
        assertEquals(4, warnings.size());
        assertTrue(warnings.stream().allMatch(w -> w.contains("x.pet.yml") && w.contains("campfire")), warnings.toString());
    }

    @Test
    void settingsBecomeALuaTable() {
        LuaTable table = PetBehaviorSettings.toLua(Map.of("radius", 12D, "night_only", true, "effect", "SLOWNESS",
                "blocks", List.of("CAMPFIRE", "SOUL_CAMPFIRE")));
        assertEquals(12D, table.get("radius").todouble());
        assertTrue(table.get("night_only").toboolean());
        assertEquals("SLOWNESS", table.get("effect").tojstring());
        assertEquals(2, table.get("blocks").length());
        assertEquals("SOUL_CAMPFIRE", table.get("blocks").get(2).tojstring());
    }

    @Test
    void aSettingWithNoValueIsReportedInPlainWordsAndKeepsTheDefault() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> values = new HashMap<>();
        values.put("radius", null);
        assertEquals(DEFAULTS, PetBehaviorSettings.merge("campfire", DEFAULTS, values, "x.pet.yml", warnings::add));
        assertEquals(List.of("x.pet.yml: campfire.radius has no value; using 12."), warnings);
    }

    @Test
    void aNumberThatIsNotFiniteIsReportedAndKeepsTheDefault() {
        List<String> warnings = new ArrayList<>();
        for (Object radius : List.of(Double.NaN, Double.NEGATIVE_INFINITY, "NaN", "1e999"))
            assertEquals(DEFAULTS, PetBehaviorSettings.merge("campfire", DEFAULTS, Map.of("radius", radius),
                    "x.pet.yml", warnings::add));
        assertEquals(List.of("x.pet.yml: campfire.radius must be a number, not .nan; using 12.",
                "x.pet.yml: campfire.radius must be a number, not -.inf; using 12.",
                "x.pet.yml: campfire.radius must be a number, not \"NaN\"; using 12.",
                "x.pet.yml: campfire.radius must be a number, not \"1e999\"; using 12."), warnings);
    }

    @Test
    void warningsQuoteTextButNotNumbersOrTrueOrFalse() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("radius", "far");
        values.put("night_only", 3);
        values.put("effect", Map.of("type", "SLOWNESS"));
        values.put("blocks", List.of("CAMPFIRE", 3));
        PetBehaviorSettings.merge("campfire", DEFAULTS, values, "x.pet.yml", warnings::add);
        assertEquals(List.of(
                "x.pet.yml: campfire.radius must be a number, not \"far\"; using 12.",
                "x.pet.yml: campfire.night_only must be true or false, not 3; using true.",
                "x.pet.yml: campfire.effect must be text, not { type: \"SLOWNESS\" }; using \"SLOWNESS\".",
                "x.pet.yml: campfire.blocks must be a list of text, not [\"CAMPFIRE\", 3]; using [\"CAMPFIRE\"]."), warnings);
    }

    @Test
    void aListWithAnEmptyEntryIsReportedInPlainWordsAndKeepsTheDefault() {
        List<String> warnings = new ArrayList<>();
        List<Object> blocks = new ArrayList<>();
        blocks.add("SOUL_CAMPFIRE");
        blocks.add(null);
        assertEquals(DEFAULTS, PetBehaviorSettings.merge("campfire", DEFAULTS, Map.of("blocks", blocks), "x.pet.yml",
                warnings::add));
        assertEquals(List.of("x.pet.yml: campfire.blocks has an empty entry; using [\"CAMPFIRE\"]."), warnings);
    }

    @Test
    void anUnknownSettingNamesTheModulesSettings() {
        List<String> warnings = new ArrayList<>();
        PetBehaviorSettings.merge("campfire", new TreeMap<>(DEFAULTS), Map.of("colour", "red"), "x.pet.yml", warnings::add);
        PetBehaviorSettings.merge("glow", Map.of("light", 15D), Map.of("colour", "red"), "x.pet.yml", warnings::add);
        PetBehaviorSettings.merge("stay", Map.of(), Map.of("colour", "red"), "x.pet.yml", warnings::add);
        assertEquals(List.of("x.pet.yml: the pet behavior campfire has no setting colour (its settings are blocks, effect, "
                        + "night_only and radius); ignored.",
                "x.pet.yml: the pet behavior glow has no setting colour (its only setting is light); ignored.",
                "x.pet.yml: the pet behavior stay has no setting colour (it has no settings); ignored."), warnings);
    }

    @Test
    void quotedNumbersAndTrueOrFalseAreTaken() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> merged = PetBehaviorSettings.merge("campfire", DEFAULTS, Map.of("radius", "8", "night_only",
                "FALSE"), "x.pet.yml", warnings::add);
        assertEquals(8D, merged.get("radius"));
        assertEquals(false, merged.get("night_only"));
        assertEquals(0.5D, PetBehaviorSettings.merge("campfire", DEFAULTS, Map.of("radius", "0.5"), "x.pet.yml",
                warnings::add).get("radius"));
        assertEquals(true, PetBehaviorSettings.merge("campfire", DEFAULTS, Map.of("night_only", "True"), "x.pet.yml",
                warnings::add).get("night_only"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void numbersAndTrueOrFalseAreTakenAsText() {
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<Object, String> given : Map.<Object, String>of(12, "12", 0.5D, "0.5", 12D, "12", true, "true")
                .entrySet())
            assertEquals(given.getValue(), PetBehaviorSettings.merge("campfire", DEFAULTS, Map.of("effect", given.getKey()),
                    "x.pet.yml", warnings::add).get("effect"), given.getKey().toString());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aSingleWordIsAOneItemList() {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of("SOUL_CAMPFIRE"), PetBehaviorSettings.merge("campfire", DEFAULTS,
                Map.of("blocks", "SOUL_CAMPFIRE"), "x.pet.yml", warnings::add).get("blocks"));
        assertEquals(List.of(), warnings);
    }

    /** "blocks: []" is taken as asking for none, not as a mistake: a module reads an empty list as nothing to match. */
    @Test
    void anEmptyListIsTakenAsNone() {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(), PetBehaviorSettings.merge("campfire", DEFAULTS, Map.of("blocks", List.of()), "x.pet.yml",
                warnings::add).get("blocks"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void anEmptyTextDefaultShowsAsEmptyQuotes() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> values = new HashMap<>();
        values.put("label", null);
        PetBehaviorSettings.merge("name_tag", Map.of("label", ""), values, "x.pet.yml", warnings::add);
        assertEquals(List.of("x.pet.yml: name_tag.label has no value; using \"\"."), warnings);
    }

    @Test
    void theMergedSettingsAreReadOnlyAndKeepTheDefaultsOrder() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("radius", 12D);
        defaults.put("night_only", true);
        defaults.put("effect", "SLOWNESS");
        defaults.put("blocks", List.of("CAMPFIRE"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("blocks", new ArrayList<>(List.of("SOUL_CAMPFIRE")));
        values.put("radius", 8);
        Map<String, Object> merged = PetBehaviorSettings.merge("campfire", defaults, values, "x.pet.yml", warning -> {
        });
        assertEquals(List.of("radius", "night_only", "effect", "blocks"), List.copyOf(merged.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> merged.put("radius", 9D));
        @SuppressWarnings("unchecked") List<String> mergedBlocks = (List<String>) merged.get("blocks");
        assertThrows(UnsupportedOperationException.class, () -> mergedBlocks.add("CAMPFIRE"));
    }

    @Test
    void eachPetGetsItsOwnTable() {
        Map<String, Object> settings = Map.of("radius", 12D, "blocks", List.of("CAMPFIRE"));
        LuaTable first = PetBehaviorSettings.toLua(settings);
        first.set("radius", 3);
        first.get("blocks").set(1, "SOUL_CAMPFIRE");
        LuaTable second = PetBehaviorSettings.toLua(settings);
        assertEquals(12D, second.get("radius").todouble());
        assertEquals("CAMPFIRE", second.get("blocks").get(1).tojstring());
        assertNotSame(first, second);
    }

    @Test
    void aSettingOfAnUnknownTypeIsRefusedByName() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PetBehaviorSettings.toLua(Map.of("radius", 12)));
        assertTrue(refused.getMessage().contains("Integer"), refused.getMessage());
    }
}
