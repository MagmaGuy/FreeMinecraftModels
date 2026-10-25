package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.shaded.luaj.vm2.LuaTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(DEFAULTS, PetBehaviorSettings.merge("campfire", DEFAULTS, Map.of("radius", Double.NaN),
                "x.pet.yml", warnings::add));
        assertEquals(DEFAULTS, PetBehaviorSettings.merge("campfire", DEFAULTS, Map.of("radius", Double.NEGATIVE_INFINITY),
                "x.pet.yml", warnings::add));
        assertEquals(List.of("x.pet.yml: campfire.radius must be a number, not \".nan\"; using 12.",
                "x.pet.yml: campfire.radius must be a number, not \"-.inf\"; using 12."), warnings);
    }

    @Test
    void warningsQuoteValuesAsYamlReadThemAndNeverSayNull() {
        List<String> warnings = new ArrayList<>();
        List<Object> blocks = new ArrayList<>();
        blocks.add("CAMPFIRE");
        blocks.add(null);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("blocks", blocks);
        values.put("effect", Map.of("type", "SLOWNESS"));
        values.put("radius", "far");
        PetBehaviorSettings.merge("campfire", DEFAULTS, values, "x.pet.yml", warnings::add);
        assertEquals(List.of(
                "x.pet.yml: campfire.blocks must be a list of text, not \"[CAMPFIRE, ]\"; using [CAMPFIRE].",
                "x.pet.yml: campfire.effect must be text, not \"{ type: SLOWNESS }\"; using SLOWNESS.",
                "x.pet.yml: campfire.radius must be a number, not \"far\"; using 12."), warnings);
        assertTrue(warnings.stream().noneMatch(warning -> warning.contains("null")), warnings.toString());
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
        assertFalse(first == second);
    }

    @Test
    void aSettingOfAnUnknownTypeIsRefusedByName() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PetBehaviorSettings.toLua(Map.of("radius", 12)));
        assertTrue(refused.getMessage().contains("Integer"), refused.getMessage());
    }
}
