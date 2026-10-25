package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.scripting.LuaMindDefinition;
import com.magmaguy.magmacore.scripting.LuaMindModuleDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * One Mind program per species and variant, assembled from the catalog's modules and built once, with the settings
 * each module reads as c.settings. A pet instantiates its own copy (Lua state is per pet; compiled code is shared).
 * <p>
 * A PetPrograms belongs to one catalog load and says each warning once for that load. Use it on the main thread only,
 * and outside Lua Mind callbacks: building a program runs Lua, which inside a callback would share that callback's
 * budget. The first call for a species and variant decides its program; later calls get that program whatever
 * entries and file name they pass.
 */
public final class PetPrograms {
    /** A creature's program, and the settings of each module it runs by module id. */
    public record Program(LuaMindDefinition definition, Map<String, Map<String, Object>> settings) {
    }

    // About one probe per movement behavior plus the sensors due, per pet tick; the load check tunes these values.
    // max_action_requests caps only c.actions requests, which come from behaviors holding the ACTION control.
    private static final String BUDGET = "budget = { callback_micros = 1500, entity_micros = 3000, server_micros = 10000,"
            + " max_callbacks = 32, max_action_requests = 1 }, runaway = { cpu_micros = 20000, max_instructions = 20000 }";
    /** The most module names a warning about a missing one lists; past that it stays short. */
    private static final int MAX_LISTED = 10;

    private final PetBehaviorCatalog catalog;
    private final Consumer<String> warn;
    private final Map<Key, Program> programs = new HashMap<>();

    public PetPrograms(PetBehaviorCatalog catalog, Consumer<String> warn) {
        this.catalog = catalog;
        // Variants share their species' list, so its problems would otherwise be said once per variant.
        Set<String> said = new HashSet<>();
        this.warn = message -> {
            if (said.add(message)) warn.accept(message);
        };
    }

    /**
     * The list a species without one runs, by how its body moves. A stationary body cannot path, so it does not
     * wander; follow_owner's catch-up teleport still keeps an owned one with its owner, and stay lets the owner
     * manage it.
     */
    public static List<PetBehaviorEntry> defaults(MindBodyLocomotion locomotion) {
        List<String> names = switch (locomotion) {
            case FLYING -> List.of("migrate", "stay", "follow_owner", "drift", "hover");
            case AQUATIC -> List.of("stay", "follow_owner", "swim");
            case GROUNDED, AMPHIBIOUS -> List.of("stay", "follow_owner", "wander", "idle_look");
            case STATIONARY -> List.of("stay", "follow_owner", "idle_look");
        };
        return names.stream().map(name -> new PetBehaviorEntry(name, Map.of())).toList();
    }

    /**
     * The program for one variant of a species, from its list: the species' entries, then the variant's.
     * {@code where} names the pet file in warnings. A module that is missing or failed to load is left out with a
     * warning; a module listed twice runs once, with its first entry's settings. When the modules cannot all run
     * together, they are added in list order and each one that clashes with those kept before it is left out with a
     * warning, so the earlier entry wins. The creature loads either way, and the outcome is kept for the next call.
     */
    public Program program(String species, String variant, List<PetBehaviorEntry> entries, String where) {
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(where, "where");
        return programs.computeIfAbsent(new Key(species, variant), key -> build(species, variant, entries, where));
    }

    private Program build(String species, String variant, List<PetBehaviorEntry> entries, String where) {
        // Each listed module that loaded, once, in list order.
        Map<String, Listed> listed = new LinkedHashMap<>();
        for (PetBehaviorEntry entry : entries) {
            Optional<LuaMindModuleDescriptor> module = catalog.module(entry.module());
            if (module.isEmpty()) warn.accept(missing(entry.module(), where));
            else listed.putIfAbsent(module.get().identifier(), new Listed(entry, module.get()));
        }
        String id = PetBehaviorCatalog.ID_PREFIX + "creature/" + key(species) + "/" + key(variant);
        String label = where + ", variant " + variant;
        List<Listed> kept = new ArrayList<>(listed.values());
        LuaMindDefinition definition;
        try {
            definition = validate(label, id, kept);
        } catch (RuntimeException clash) {
            // Something in the list does not fit; find what, one module at a time, from a program with none. The
            // warnings name the pet file without the variant, so a clash in the species' list is said once, not once
            // per variant; the module's name locates the entry.
            try {
                definition = validate(label, id, List.of());
            } catch (RuntimeException failure) {
                warn.accept(where + ": could not build its pet behaviors (" + reason(failure)
                        + "); the creature runs none of them.");
                // A program without modules needs no catalog, and its source is generated here, so this fails only
                // when that generated source is wrong.
                return new Program(LuaMindDefinition.validate(label, source(id, List.of())), Map.of());
            }
            kept.clear();
            for (Listed module : listed.values()) {
                kept.add(module);
                try {
                    definition = validate(label, id, kept);
                } catch (RuntimeException failure) {
                    kept.removeLast();
                    String name = module.entry().module();
                    String problem = kept.isEmpty() ? "could not be built" : "cannot run alongside the ones listed before it";
                    if (failsThroughDependencies(label, id, kept, module)) problem = "needs pet behaviors that " + problem;
                    warn.accept(where + ": the pet behavior " + name + " " + problem + " (" + reason(failure)
                            + "); the creature runs without " + name + ".");
                }
            }
        }
        // Settings only for what runs: the kept modules as listed, then each dependency they pull in at its defaults.
        Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
        for (Listed module : kept)
            settings.put(module.descriptor().identifier(), PetBehaviorSettings.merge(module.entry().module(),
                    module.descriptor().settings(), module.entry().settings(), where, warn));
        for (LuaMindModuleDescriptor module : definition.resolvedModules())
            settings.putIfAbsent(module.identifier(), module.settings());
        return new Program(definition, Collections.unmodifiableMap(settings));
    }

    private LuaMindDefinition validate(String label, String id, List<Listed> modules) {
        return catalog.owner().validateProgram(label,
                source(id, modules.stream().map(module -> module.descriptor().identifier()).toList()));
    }

    /**
     * Whether the modules a left-out module needs fail beside the kept ones without it, so its warning can say the
     * trouble came through them: MagmaCore's reason then names those modules, not this one.
     */
    private boolean failsThroughDependencies(String label, String id, List<Listed> kept, Listed module) {
        if (module.descriptor().dependencies().isEmpty()) return false;
        List<String> ids = new ArrayList<>(kept.stream().map(entry -> entry.descriptor().identifier()).toList());
        for (String dependency : module.descriptor().dependencies()) if (!ids.contains(dependency)) ids.add(dependency);
        try {
            catalog.owner().validateProgram(label, source(id, ids));
            return false;
        } catch (RuntimeException failure) {
            return true;
        }
    }

    /** Why a listed module is not there: it failed to load, or no file has its name. */
    private String missing(String name, String where) {
        Optional<String> file = catalog.failedFile(name);
        if (file.isPresent())
            return where + ": the pet behavior " + name + " failed to load (see the warning about " + file.get()
                    + "); the creature runs without it.";
        return where + ": there is no pet behavior " + name + " in pet_behaviors/" + hint(name)
                + "; the creature runs without it.";
    }

    /**
     * For a server owner who mistyped a module: the closest loaded name when it is a letter or two off (one for
     * names of four letters or fewer), else the loaded names when they are few enough to list.
     */
    private String hint(String name) {
        int allowed = name.length() <= 4 ? 1 : 2;
        String closest = null;
        for (String candidate : catalog.names()) {
            // The names come sorted, so a tie goes to the first.
            int distance = distance(name, candidate);
            if (distance <= allowed) {
                allowed = distance - 1;
                closest = candidate;
            }
        }
        if (closest != null) return " (did you mean " + closest + "?)";
        List<String> names = List.copyOf(catalog.names());
        if (names.isEmpty()) return " (no pet behavior loaded from there)";
        if (names.size() == 1) return " (the only one there is " + names.getFirst() + ")";
        if (names.size() > MAX_LISTED) return "";
        return " (the ones there are " + String.join(", ", names.subList(0, names.size() - 1)) + " and "
                + names.getLast() + ")";
    }

    /** The edits between two names, counting an added, removed or changed letter, or two neighbours swapped, as one. */
    private static int distance(String a, String b) {
        int[][] edits = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) edits[i][0] = i;
        for (int j = 0; j <= b.length(); j++) edits[0][j] = j;
        for (int i = 1; i <= a.length(); i++)
            for (int j = 1; j <= b.length(); j++) {
                int change = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                edits[i][j] = Math.min(Math.min(edits[i - 1][j], edits[i][j - 1]) + 1, edits[i - 1][j - 1] + change);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    edits[i][j] = Math.min(edits[i][j], edits[i - 2][j - 2] + 1);
            }
        return edits[a.length()][b.length()];
    }

    /** A failure's message for a warning, which MagmaCore words for people; never the exception's class name. */
    private static String reason(RuntimeException failure) {
        return Objects.requireNonNullElse(failure.getMessage(), "no reason was given");
    }

    private static String source(String id, List<String> modules) {
        return "return ai.program { id = '" + id + "', revision = 1, modules = { "
                + modules.stream().map(module -> "'" + module + "'").collect(Collectors.joining(", ")) + " }, " + BUDGET + " }";
    }

    /** A name as a program id part: lower case, anything outside a-z, 0-9, '.', '_' and '-' as '_'. */
    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private record Key(String species, String variant) {
    }

    /** A listed module that loaded, with the entry that listed it first. */
    private record Listed(PetBehaviorEntry entry, LuaMindModuleDescriptor descriptor) {
    }
}
