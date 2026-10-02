package com.magmaguy.freeminecraftmodels.particles.molang;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Variable storage for Molang evaluation. A particle context sits on top of its emitter's
 * context: reads fall through to the parent, writes stay local.
 */
public final class MolangContext {
    private final Map<String, Double> values = new HashMap<>();
    private final MolangContext parent;
    private final RandomGenerator random;

    public MolangContext(RandomGenerator random) {
        this(null, random);
    }

    public MolangContext(MolangContext parent, RandomGenerator random) {
        this.parent = parent;
        this.random = random;
    }

    public RandomGenerator random() {
        return random;
    }

    public double get(String name) {
        return getCanonical(canonical(name));
    }

    public void set(String name, double value) {
        setCanonical(canonical(name), value);
    }

    double getCanonical(String name) {
        for (MolangContext context = this; context != null; context = context.parent) {
            Double value = context.values.get(name);
            if (value != null) return value;
        }
        return 0;
    }

    boolean hasCanonical(String name) {
        for (MolangContext context = this; context != null; context = context.parent) {
            if (context.values.containsKey(name)) return true;
        }
        return false;
    }

    void setCanonical(String name, double value) {
        values.put(name, value);
    }

    /** Lowercases a name and expands the v./t./q./c. short prefixes. */
    public static String canonical(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("v.")) return "variable." + lower.substring(2);
        if (lower.startsWith("t.")) return "temp." + lower.substring(2);
        if (lower.startsWith("q.")) return "query." + lower.substring(2);
        if (lower.startsWith("c.")) return "context." + lower.substring(2);
        return lower;
    }
}
