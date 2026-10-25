package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** A module's declared setting defaults with one creature's values on top, and the table Lua reads them from. */
public final class PetBehaviorSettings {
    private PetBehaviorSettings() {
    }

    /**
     * The defaults with the creature's values on top, read-only and in the defaults' order. A value for a setting the
     * module does not declare, a setting left without a value, and a value of another type than the default (or a
     * number that is not finite) are reported through {@code warn}, naming {@code where}, and the default stays.
     */
    public static Map<String, Object> merge(String module, Map<String, Object> defaults, Map<String, Object> values,
                                            String where, Consumer<String> warn) {
        Map<String, Object> merged = new LinkedHashMap<>(defaults);
        for (Map.Entry<String, Object> value : values.entrySet()) {
            Object example = defaults.get(value.getKey());
            if (example == null) {
                warn.accept(where + ": the pet behavior " + module + " has no setting " + value.getKey() + "; ignored.");
                continue;
            }
            if (value.getValue() == null) {
                warn.accept(where + ": " + module + "." + value.getKey() + " has no value; using " + plain(example) + ".");
                continue;
            }
            Object coerced = coerce(example, value.getValue());
            if (coerced == null) {
                warn.accept(where + ": " + module + "." + value.getKey() + " must be " + kind(example) + ", not \""
                        + yaml(value.getValue()) + "\"; using " + plain(example) + ".");
                continue;
            }
            merged.put(value.getKey(), coerced);
        }
        return Collections.unmodifiableMap(merged);
    }

    /** The value as the default's type, or null when it is not one. */
    private static Object coerce(Object example, Object value) {
        if (example instanceof Double)
            return value instanceof Number number && Double.isFinite(number.doubleValue()) ? number.doubleValue() : null;
        if (example instanceof Boolean) return value instanceof Boolean flag ? flag : null;
        if (example instanceof String) return value instanceof String text ? text : null;
        if (example instanceof List<?>) {
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

    private static String kind(Object example) {
        if (example instanceof Double) return "a number";
        if (example instanceof Boolean) return "true or false";
        if (example instanceof String) return "text";
        if (example instanceof List<?>) return "a list of text";
        throw unsupported(example);
    }

    /** A default as a server owner would write it, so an empty text still shows. */
    private static String plain(Object example) {
        return example instanceof String text && text.isEmpty() ? "\"\"" : yaml(example);
    }

    /** A value as YAML would write it: {@code .nan} rather than NaN, maps as {@code { key: value }}, nothing as empty. */
    private static String yaml(Object value) {
        if (value == null) return "";
        if (value instanceof Double number) {
            if (number.isNaN()) return ".nan";
            if (number.isInfinite()) return number > 0 ? ".inf" : "-.inf";
            return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            return map.entrySet().stream()
                    .map(pair -> pair.getKey() + ":" + (pair.getValue() == null ? "" : " " + yaml(pair.getValue())))
                    .collect(Collectors.joining(", ", "{ ", " }"));
        }
        if (value instanceof Collection<?> list)
            return list.stream().map(PetBehaviorSettings::yaml).collect(Collectors.joining(", ", "[", "]"));
        return String.valueOf(value);
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
