package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.scripting.LuaMindDefinition;
import com.magmaguy.magmacore.scripting.LuaMindModuleDescriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * One Mind program per species and variant, assembled from the catalog's modules and built once, with the settings
 * each module reads as c.settings. A pet instantiates its own copy (Lua state is per pet; compiled code is shared).
 */
public final class PetPrograms {
    /** A creature's program, and each of its modules' merged settings by module id. */
    public record Program(LuaMindDefinition definition, Map<String, Map<String, Object>> settings) {
    }

    // About one probe per movement behavior plus the sensors due, per pet tick; the load check (Task 19) tunes these.
    private static final String BUDGET = "budget = { callback_micros = 1500, entity_micros = 3000, server_micros = 10000,"
            + " max_callbacks = 32, max_action_requests = 1 }, runaway = { cpu_micros = 20000, max_instructions = 20000 }";
    /** The most modules an unknown-module warning lists; past that it stays short. */
    private static final int MAX_LISTED = 10;

    private final PetBehaviorCatalog catalog;
    private final Consumer<String> warn;
    private final Map<Key, Program> programs = new HashMap<>();

    public PetPrograms(PetBehaviorCatalog catalog, Consumer<String> warn) {
        this.catalog = catalog;
        this.warn = warn;
    }

    /** The list a species without one runs, by how its body moves. */
    public static List<PetBehaviorEntry> defaults(MindBodyLocomotion locomotion) {
        List<String> names = switch (locomotion) {
            case FLYING -> List.of("migrate", "stay", "follow_owner", "drift", "hover");
            case AQUATIC -> List.of("stay", "follow_owner", "swim");
            default -> List.of("stay", "follow_owner", "wander", "idle_look");
        };
        return names.stream().map(name -> new PetBehaviorEntry(name, Map.of())).toList();
    }

    /**
     * The program for one variant of a species. {@code where} names the pet file in warnings; a module the catalog
     * lacks is left out with a warning, so the creature still loads. Modules that cannot run together leave the
     * creature with none of them, after a warning. Either way the outcome is kept, so a broken pet file warns once
     * per load, not once per pet.
     */
    public Program program(String species, String variant, List<PetBehaviorEntry> entries, String where) {
        return programs.computeIfAbsent(new Key(species, variant), key -> build(species, variant, entries, where));
    }

    private Program build(String species, String variant, List<PetBehaviorEntry> entries, String where) {
        List<String> ids = new ArrayList<>();
        Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
        for (PetBehaviorEntry entry : entries) {
            Optional<LuaMindModuleDescriptor> module = catalog.module(entry.module());
            if (module.isEmpty()) {
                warn.accept(where + ": lists the pet behavior " + entry.module() + ", which is not in pet_behaviors/"
                        + available() + "; the creature runs without it.");
                continue;
            }
            ids.add(module.get().identifier());
            settings.put(module.get().identifier(),
                    PetBehaviorSettings.merge(entry.module(), module.get().settings(), entry.settings(), where, warn));
        }
        String id = PetBehaviorCatalog.ID_PREFIX + "creature/" + key(species) + "/" + key(variant);
        String label = where + ", variant " + variant;
        try {
            return new Program(catalog.owner().validateProgram(label, source(id, ids)), Map.copyOf(settings));
        } catch (RuntimeException failure) {
            warn.accept(label + ": the pet behaviors it lists do not fit together ("
                    + Objects.requireNonNullElse(failure.getMessage(), "no reason was given")
                    + "); the creature runs none of them.");
            // A program without modules needs no catalog, and its source is all generated here, so this cannot fail.
            return new Program(LuaMindDefinition.validate(label, source(id, List.of())), Map.of());
        }
    }

    /** The modules there are, for a server owner who mistyped one, when they are few enough to list. */
    private String available() {
        List<String> names = List.copyOf(catalog.names());
        if (names.isEmpty()) return " (no pet behavior loaded from there)";
        if (names.size() == 1) return " (the only one there is " + names.getFirst() + ")";
        if (names.size() > MAX_LISTED) return "";
        return " (the ones there are " + String.join(", ", names.subList(0, names.size() - 1)) + " and "
                + names.getLast() + ")";
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
}
