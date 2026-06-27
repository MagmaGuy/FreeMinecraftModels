package com.magmaguy.freeminecraftmodels.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BowStateDetectorTest {

    @Test
    void detectStateGroupsFindsCompleteBowAndCrossbowFamiliesOnly() {
        List<BowStateDetector.StateGroup> groups = BowStateDetector.detectStateGroups(Set.of(
                "oak_bow_idle",
                "oak_bow_draw_start",
                "oak_bow_draw_half",
                "oak_bow_draw_full",
                "gear_crossbow_idle",
                "gear_crossbow_draw_start",
                "gear_crossbow_draw_half",
                "gear_crossbow_draw_full",
                "gear_crossbow_charged",
                "broken_bow_idle",
                "broken_bow_draw_start"));

        assertEquals(2, groups.size());
        assertTrue(groups.contains(new BowStateDetector.StateGroup("oak_bow", false)));
        assertTrue(groups.contains(new BowStateDetector.StateGroup("gear_crossbow", true)));
        assertFalse(groups.contains(new BowStateDetector.StateGroup("broken_bow", false)));
    }

    @Test
    void suffixHelpersOnlyStripKnownDrawStates() {
        assertTrue(BowStateDetector.isDrawStateSuffix("gear_crossbow_draw_full"));
        assertEquals("gear_crossbow", BowStateDetector.stripStateSuffix("gear_crossbow_draw_full"));
        assertEquals("regular_model", BowStateDetector.stripStateSuffix("regular_model"));
        assertFalse(BowStateDetector.isDrawStateSuffix("regular_model"));
    }

    @Test
    void generatedBowItemJsonUsesUsingItemRangeDispatch() {
        JsonObject model = JsonParser.parseString(
                        BowStateDetector.generateBowItemJson("oak_bow", "freeminecraftmodels"))
                .getAsJsonObject()
                .getAsJsonObject("model");

        assertEquals("minecraft:condition", model.get("type").getAsString());
        assertEquals("minecraft:using_item", model.get("property").getAsString());
        assertEquals("freeminecraftmodels:display/oak_bow_idle",
                model.getAsJsonObject("on_false").get("model").getAsString());

        JsonObject rangeDispatch = model.getAsJsonObject("on_true");
        assertEquals("minecraft:range_dispatch", rangeDispatch.get("type").getAsString());
        assertEquals("minecraft:use_duration", rangeDispatch.get("property").getAsString());
        assertEquals("freeminecraftmodels:display/oak_bow_draw_start",
                rangeDispatch.getAsJsonObject("fallback").get("model").getAsString());
        assertEquals("freeminecraftmodels:display/oak_bow_draw_full",
                rangeDispatch.getAsJsonArray("entries")
                        .get(1).getAsJsonObject()
                        .getAsJsonObject("model")
                        .get("model").getAsString());
    }

    @Test
    void generatedCrossbowItemJsonUsesChargedSelectBeforePullFallback() {
        JsonObject model = JsonParser.parseString(
                        BowStateDetector.generateCrossbowItemJson("gear_crossbow", "freeminecraftmodels"))
                .getAsJsonObject()
                .getAsJsonObject("model");

        assertEquals("minecraft:select", model.get("type").getAsString());
        assertEquals("minecraft:charge_type", model.get("property").getAsString());
        assertEquals("arrow", model.getAsJsonArray("cases")
                .get(0).getAsJsonObject()
                .get("when").getAsString());
        assertEquals("freeminecraftmodels:display/gear_crossbow_charged",
                model.getAsJsonArray("cases")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("model")
                        .get("model").getAsString());
        assertEquals("minecraft:condition",
                model.getAsJsonObject("fallback").get("type").getAsString());
    }
}
