package com.magmaguy.freeminecraftmodels.config;

import com.google.gson.GsonBuilder;

import java.util.*;

/**
 * Detects bow and crossbow draw state model groups and generates the appropriate
 * conditional item definition JSON for Minecraft's resource pack system.
 * <p>
 * Naming convention:
 * <ul>
 *   <li>{name}_idle — in hand, not drawing</li>
 *   <li>{name}_draw_start — just started pulling</li>
 *   <li>{name}_draw_half — halfway drawn</li>
 *   <li>{name}_draw_full — fully drawn</li>
 *   <li>{name}_charged — loaded crossbow (crossbow only)</li>
 * </ul>
 */
public final class BowStateDetector {

    private static final String SUFFIX_IDLE = "_idle";
    private static final String SUFFIX_DRAW_START = "_draw_start";
    private static final String SUFFIX_DRAW_HALF = "_draw_half";
    private static final String SUFFIX_DRAW_FULL = "_draw_full";
    private static final String SUFFIX_CHARGED = "_charged";

    public static final List<String> ALL_STATE_SUFFIXES = List.of(
            SUFFIX_IDLE, SUFFIX_DRAW_START, SUFFIX_DRAW_HALF, SUFFIX_DRAW_FULL, SUFFIX_CHARGED);

    private BowStateDetector() {
    }

    /**
     * Checks if a model ID has a bow/crossbow draw state suffix.
     */
    public static boolean isDrawStateSuffix(String modelId) {
        for (String suffix : ALL_STATE_SUFFIXES) {
            if (modelId.endsWith(suffix)) return true;
        }
        return false;
    }

    /**
     * Strips the draw state suffix from a model ID to get the base name.
     * Returns the original ID if no suffix is found.
     */
    public static String stripStateSuffix(String modelId) {
        for (String suffix : ALL_STATE_SUFFIXES) {
            if (modelId.endsWith(suffix)) {
                return modelId.substring(0, modelId.length() - suffix.length());
            }
        }
        return modelId;
    }

    /**
     * Scans a set of model IDs that have display JSONs and detects bow/crossbow groups.
     * A group is detected when an _idle model has matching _draw_start, _draw_half, _draw_full siblings.
     * If _charged also exists, it's a crossbow.
     *
     * @param displayModelIds set of all model IDs that have display JSONs
     * @return list of detected state groups
     */
    public static List<StateGroup> detectStateGroups(Set<String> displayModelIds) {
        List<StateGroup> groups = new ArrayList<>();

        for (String modelId : displayModelIds) {
            if (!modelId.endsWith(SUFFIX_IDLE)) continue;
            String base = modelId.substring(0, modelId.length() - SUFFIX_IDLE.length());

            // Check required siblings
            if (!displayModelIds.contains(base + SUFFIX_DRAW_START)) continue;
            if (!displayModelIds.contains(base + SUFFIX_DRAW_HALF)) continue;
            if (!displayModelIds.contains(base + SUFFIX_DRAW_FULL)) continue;

            boolean isCrossbow = displayModelIds.contains(base + SUFFIX_CHARGED);
            groups.add(new StateGroup(base, isCrossbow));
        }

        return groups;
    }

    /**
     * Generates the conditional item definition JSON for a bow state group.
     */
    public static String generateBowItemJson(String baseName, String namespace) {
        String prefix = namespace + ":" + "display/";
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("type", "minecraft:condition");
        condition.put("property", "minecraft:using_item");

        // on_false: idle state
        Map<String, Object> onFalse = new LinkedHashMap<>();
        onFalse.put("type", "minecraft:model");
        onFalse.put("model", prefix + baseName + SUFFIX_IDLE);
        condition.put("on_false", onFalse);

        // on_true: range dispatch for draw states
        Map<String, Object> rangeDispatch = new LinkedHashMap<>();
        rangeDispatch.put("type", "minecraft:range_dispatch");
        rangeDispatch.put("property", "minecraft:use_duration");
        rangeDispatch.put("scale", 0.05);

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("type", "minecraft:model");
        fallback.put("model", prefix + baseName + SUFFIX_DRAW_START);
        rangeDispatch.put("fallback", fallback);

        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> entry1 = new LinkedHashMap<>();
        entry1.put("threshold", 0.65);
        Map<String, Object> model1 = new LinkedHashMap<>();
        model1.put("type", "minecraft:model");
        model1.put("model", prefix + baseName + SUFFIX_DRAW_HALF);
        entry1.put("model", model1);
        entries.add(entry1);

        Map<String, Object> entry2 = new LinkedHashMap<>();
        entry2.put("threshold", 0.9);
        Map<String, Object> model2 = new LinkedHashMap<>();
        model2.put("type", "minecraft:model");
        model2.put("model", prefix + baseName + SUFFIX_DRAW_FULL);
        entry2.put("model", model2);
        entries.add(entry2);

        rangeDispatch.put("entries", entries);
        condition.put("on_true", rangeDispatch);
        root.put("model", condition);

        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /**
     * Generates the conditional item definition JSON for a crossbow state group.
     */
    public static String generateCrossbowItemJson(String baseName, String namespace) {
        String prefix = namespace + ":" + "display/";
        Map<String, Object> root = new LinkedHashMap<>();

        // Top level: select on charge_type
        Map<String, Object> select = new LinkedHashMap<>();
        select.put("type", "minecraft:select");
        select.put("property", "minecraft:charge_type");

        // Cases: arrow and rocket both show charged model
        List<Map<String, Object>> cases = new ArrayList<>();
        for (String type : List.of("arrow", "rocket")) {
            Map<String, Object> caseEntry = new LinkedHashMap<>();
            caseEntry.put("when", type);
            Map<String, Object> chargedModel = new LinkedHashMap<>();
            chargedModel.put("type", "minecraft:model");
            chargedModel.put("model", prefix + baseName + SUFFIX_CHARGED);
            caseEntry.put("model", chargedModel);
            cases.add(caseEntry);
        }
        select.put("cases", cases);

        // Fallback: condition on using_item
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("type", "minecraft:condition");
        condition.put("property", "minecraft:using_item");

        // on_false: idle
        Map<String, Object> onFalse = new LinkedHashMap<>();
        onFalse.put("type", "minecraft:model");
        onFalse.put("model", prefix + baseName + SUFFIX_IDLE);
        condition.put("on_false", onFalse);

        // on_true: range dispatch for pull states
        Map<String, Object> rangeDispatch = new LinkedHashMap<>();
        rangeDispatch.put("type", "minecraft:range_dispatch");
        rangeDispatch.put("property", "minecraft:crossbow/pull");

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("type", "minecraft:model");
        fallback.put("model", prefix + baseName + SUFFIX_DRAW_START);
        rangeDispatch.put("fallback", fallback);

        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> entry1 = new LinkedHashMap<>();
        entry1.put("threshold", 0.58);
        Map<String, Object> model1 = new LinkedHashMap<>();
        model1.put("type", "minecraft:model");
        model1.put("model", prefix + baseName + SUFFIX_DRAW_HALF);
        entry1.put("model", model1);
        entries.add(entry1);

        Map<String, Object> entry2 = new LinkedHashMap<>();
        entry2.put("threshold", 1.0);
        Map<String, Object> model2 = new LinkedHashMap<>();
        model2.put("type", "minecraft:model");
        model2.put("model", prefix + baseName + SUFFIX_DRAW_FULL);
        entry2.put("model", model2);
        entries.add(entry2);

        rangeDispatch.put("entries", entries);
        condition.put("on_true", rangeDispatch);
        select.put("fallback", condition);

        root.put("model", select);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /**
     * Represents a detected bow or crossbow state group.
     */
    public record StateGroup(String baseName, boolean isCrossbow) {
    }
}
