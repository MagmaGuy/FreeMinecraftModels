package com.magmaguy.freeminecraftmodels.pets.behavior;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/** Values from a pet file written back the way the file would write them, for warnings server owners read. */
public final class PetBehaviorYaml {
    private PetBehaviorYaml() {
    }

    /**
     * As a pet file writes it: text as is (empty text as {@code ""}), whole numbers without a trailing {@code .0},
     * {@code .nan} and {@code .inf}, maps as {@code { key: value }} and a missing value as nothing.
     */
    public static String plain(Object value) {
        return write(value, false);
    }

    /** Like {@link #plain}, with text in quotes, so 8, true and "8" look different. */
    public static String typed(Object value) {
        return write(value, true);
    }

    /** A map's pairs without the braces, as one line of a pet file writes them: {@code radius: 8, height:}. */
    public static String plainPairs(Map<?, ?> map) {
        return pairs(map, false);
    }

    /** Whether a list entry holds nothing to show: no value, blank text, or an empty map or list. */
    public static boolean isEmpty(Object entry) {
        return entry == null || entry instanceof String text && text.isBlank() || entry instanceof Map<?, ?> map && map.isEmpty()
                || entry instanceof Collection<?> list && list.isEmpty();
    }

    /**
     * A bad list entry as its author would recognize it: quoted as YAML read it, since YAML turns {@code on} into true,
     * or "an empty entry" when it holds nothing.
     */
    public static String describe(Object entry) {
        if (isEmpty(entry)) return "an empty entry";
        return "\"" + (entry instanceof Map<?, ?> map ? plainPairs(map) : plain(entry)) + "\"";
    }

    private static String write(Object value, boolean quoteText) {
        if (value == null) return "";
        if (value instanceof String text) return quoteText || text.isEmpty() ? "\"" + text + "\"" : text;
        if (value instanceof Double number) {
            if (number.isNaN()) return ".nan";
            if (number.isInfinite()) return number > 0 ? ".inf" : "-.inf";
            return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Map<?, ?> map) return map.isEmpty() ? "{}" : "{ " + pairs(map, quoteText) + " }";
        if (value instanceof Collection<?> list)
            return list.stream().map(item -> write(item, quoteText)).collect(Collectors.joining(", ", "[", "]"));
        return String.valueOf(value);
    }

    private static String pairs(Map<?, ?> map, boolean quoteText) {
        return map.entrySet().stream()
                .map(pair -> pair.getKey() + ":" + (pair.getValue() == null ? "" : " " + write(pair.getValue(), quoteText)))
                .collect(Collectors.joining(", "));
    }
}
