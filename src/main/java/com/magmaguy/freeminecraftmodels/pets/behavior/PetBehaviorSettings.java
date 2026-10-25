package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** A module's declared setting defaults with one creature's values on top, and the table Lua reads them from. */
public final class PetBehaviorSettings {
    /** What separates the items a pet file meant when it wrote a list as one line of text. */
    private static final String SEPARATORS = "[,\\s]+";
    /** Text that can only mean a one-item list. */
    private static final String WORD = "[^,\\s]+";

    private PetBehaviorSettings() {
    }

    /**
     * The defaults with the creature's values on top, read-only and in the defaults' order. A value is taken when its
     * meaning is clear even if quoted: "8" for a number, "TRUE" for true or false, and a single word (no spaces or
     * commas) for a one-item list; an empty list means none. A number or true/false given to a text setting is taken
     * as YAML read it, so on and yes become "true"; a pet file quotes such words to keep them. A setting the module
     * does not declare, a setting left without a value, a list with an empty entry, and any other value (including a
     * number that is not finite, or text holding several words for a list) are reported through {@code warn}, naming
     * {@code where}, and the default stays.
     */
    public static Map<String, Object> merge(String module, Map<String, Object> defaults, Map<String, Object> values,
                                            String where, Consumer<String> warn) {
        Map<String, Object> merged = new LinkedHashMap<>(defaults);
        for (Map.Entry<String, Object> value : values.entrySet()) {
            String name = value.getKey();
            if (!defaults.containsKey(name)) {
                warn.accept(where + ": the pet behavior " + module + " has no setting " + name + " ("
                        + settingsOf(defaults) + "); ignored.");
                continue;
            }
            Object example = defaults.get(name);
            String setting = where + ": " + module + "." + name;
            String keeping = "; using " + PetBehaviorYaml.typed(example) + ".";
            if (value.getValue() == null) {
                warn.accept(setting + " has no value" + keeping);
                continue;
            }
            if (example instanceof List<?> && value.getValue() instanceof List<?> list
                    && list.stream().anyMatch(Objects::isNull)) {
                warn.accept(setting + " has an empty entry" + keeping);
                continue;
            }
            Object coerced = coerce(example, value.getValue());
            if (coerced == null) {
                warn.accept(setting + " must be " + kind(example) + listHint(example, value.getValue()) + ", not "
                        + PetBehaviorYaml.typed(value.getValue()) + keeping);
                continue;
            }
            merged.put(name, coerced);
        }
        return Collections.unmodifiableMap(merged);
    }

    /** The module's setting names for a server owner who mistyped one. */
    private static String settingsOf(Map<String, Object> defaults) {
        List<String> names = List.copyOf(defaults.keySet());
        if (names.isEmpty()) return "it has no settings";
        if (names.size() == 1) return "its only setting is " + names.getFirst();
        return "its settings are " + String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    /** The value as the default's type, or null when it does not clearly mean one. */
    private static Object coerce(Object example, Object value) {
        if (example instanceof Double) return number(value);
        if (example instanceof Boolean) {
            if (value instanceof Boolean flag) return flag;
            if (!(value instanceof String text)) return null;
            if (text.strip().equalsIgnoreCase("true")) return true;
            if (text.strip().equalsIgnoreCase("false")) return false;
            return null;
        }
        if (example instanceof String) {
            if (value instanceof String text) return text;
            return value instanceof Number || value instanceof Boolean ? PetBehaviorYaml.plain(value) : null;
        }
        if (example instanceof List<?>) {
            if (value instanceof String text) return text.strip().matches(WORD) ? List.of(text.strip()) : null;
            if (!(value instanceof List<?> list)) return null;
            List<String> texts = new ArrayList<>(list.size());
            for (Object entry : list) {
                if (!(entry instanceof String text)) return null;
                texts.add(text);
            }
            return List.copyOf(texts);
        }
        return null;
    }

    /** A finite number, given as one or as text that reads as one ("8", "0.5", "1e3"), or null. */
    private static Double number(Object value) {
        double number;
        if (value instanceof Number given) number = given.doubleValue();
        else if (value instanceof String text) {
            try {
                number = new BigDecimal(text.strip()).doubleValue();
            } catch (NumberFormatException notANumber) {
                return null;
            }
        } else return null;
        return Double.isFinite(number) ? number : null;
    }

    /**
     * For text given to a list setting, how to write a list: with the text's own words when it has any (YAML reads
     * "CAMPFIRE, SOUL_CAMPFIRE" on one line as one text), else with the default.
     */
    private static String listHint(Object example, Object value) {
        if (!(example instanceof List<?> fallback) || !(value instanceof String text)) return "";
        List<String> words = Arrays.stream(text.split(SEPARATORS)).filter(word -> !word.isEmpty()).toList();
        return ", one per line starting with \"- \" or written "
                + PetBehaviorYaml.plain(words.isEmpty() ? fallback : words);
    }

    private static String kind(Object example) {
        if (example instanceof Double) return "a number";
        if (example instanceof Boolean) return "true or false";
        if (example instanceof String) return "text";
        if (example instanceof List<?>) return "a list of text";
        throw unsupported(example);
    }

    /**
     * The settings as the table a module reads as {@code c.settings}. A fresh table per pet, so a module that writes
     * to it changes only its own pet.
     */
    public static LuaTable toLua(Map<String, Object> settings) {
        LuaTable table = new LuaTable();
        for (Map.Entry<String, Object> setting : settings.entrySet()) table.set(setting.getKey(), toLua(setting.getValue()));
        return table;
    }

    private static LuaValue toLua(Object value) {
        if (value instanceof Double number) return LuaValue.valueOf(number);
        if (value instanceof Boolean flag) return LuaValue.valueOf(flag);
        if (value instanceof String text) return LuaValue.valueOf(text);
        if (value instanceof List<?> entries) {
            LuaTable list = new LuaTable();
            int index = 1;
            for (Object entry : entries) {
                if (!(entry instanceof String text)) throw unsupported(entry);
                list.set(index++, LuaValue.valueOf(text));
            }
            return list;
        }
        throw unsupported(value);
    }

    private static IllegalArgumentException unsupported(Object value) {
        return new IllegalArgumentException("A pet behavior setting must be a Double, Boolean, String or List of String, not "
                + (value == null ? "null" : value.getClass().getName()) + ".");
    }
}
