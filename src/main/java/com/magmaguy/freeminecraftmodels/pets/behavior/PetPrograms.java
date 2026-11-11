package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindControl;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindSensor;
import com.magmaguy.magmacore.ai.MindStopReason;
import com.magmaguy.magmacore.scripting.LuaMindContextExtension;
import com.magmaguy.magmacore.scripting.LuaMindDefinition;
import com.magmaguy.magmacore.scripting.LuaMindModuleDescriptor;

import java.util.ArrayDeque;
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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

/**
 * One Mind program per species, variant and ownership (wild or owned), assembled from the catalog's modules and built
 * once, with the settings each module reads as c.settings. A pet instantiates its own copy (Lua state is per pet;
 * compiled code is shared).
 * <p>
 * A body's ownership never changes (taming or setting a pet free spawns a new body), so each ownership's program
 * leaves out what can never act for it: a listed module whose settings switch it off for that ownership ({@code wild
 * = false} for wild pets, {@code pets = false} for owned ones), and the one-sided sensors of a module that serves
 * both (sting). Nothing left out would have done anything on that body; leaving it out saves its Lua callbacks.
 * <p>
 * A PetPrograms belongs to one catalog load and says each warning once for that load. Use it on the main thread only,
 * and outside Lua Mind callbacks: building a program runs Lua, which inside a callback would share that callback's
 * budget. The first call for a species, variant and ownership decides its program; later calls get that program
 * whatever entries and file name they pass.
 */
public final class PetPrograms {
    /** Whose body a program is for. */
    public enum Ownership {
        WILD, OWNED
    }

    /** The setting that switches a module on for wild pets, and the one for owned pets. */
    static final String WILD_SWITCH = "wild", PETS_SWITCH = "pets";
    /**
     * The shipped sting module's sensors, which act for one ownership each, with whether that is the wild one.
     * sting_players stings the players whose touch reached a wild pet's model, and an owned pet's model records no
     * touches; sting_mobs returns at once for a wild pet. So each body runs only its own side. This relies on an
     * edited sting.lua keeping that meaning for these sensor ids; one that renames them keeps both.
     */
    private static final Map<String, Boolean> ONE_SIDED_SENSORS = Map.of(
            PetBehaviorCatalog.ID_PREFIX + "sting_players", true,
            PetBehaviorCatalog.ID_PREFIX + "sting_mobs", false);
    /**
     * Shipped sensors that can act only while a player's latest hit on the pet is recent, so a body runs them only
     * then. strike_back answers a hit while c.pet.attacker is set, which is for 30 seconds after the hit; past that it
     * would only mark the hit answered, which no later hit can tell apart, since each hit raises c.pet.hurt_count past
     * every earlier count. This relies on an edited strike_back.lua keeping that meaning for its sensor id.
     */
    private static final Set<String> HURT_SENSORS = Set.of(PetBehaviorCatalog.ID_PREFIX + "strike_back");
    /**
     * The shipped retaliate module and its two behaviors, which can start only within some ticks of a player's latest
     * hit on the pet, so a body asks their can_start only then. The charge starts on an unanswered hit while
     * c.pet.attacker is set, which is for 30 seconds after the latest hit. The flee starts only before the
     * retaliate_flee_until the charge sets as it reaches the player, which is the module's flee_seconds after a tick
     * when c.pet.attacker was set, so it can start only within those 30 seconds plus flee_seconds of the latest hit.
     * Outside those windows their can_start could only answer false. This relies on an edited retaliate.lua keeping
     * that meaning for these behavior ids.
     */
    private static final String RETALIATE = PetBehaviorCatalog.ID_PREFIX + "retaliate",
            RETALIATE_CHARGE = PetBehaviorCatalog.ID_PREFIX + "retaliate_charge",
            RETALIATE_FLEE = PetBehaviorCatalog.ID_PREFIX + "retaliate_flee";

    /**
     * A creature's program, the settings of each module it runs by module id, the sensors its bodies leave out, and
     * whether a body running it reads the touches on its model (a wild pet running a module that calls
     * c.pet:sting_contacts), so a body that never would can skip recording them.
     */
    public record Program(LuaMindDefinition definition, Map<String, Map<String, Object>> settings,
                          Set<String> leftOutSensors, boolean readsContacts) {
        /** Whether the program runs the module of this file name, listed or pulled in by another module. */
        public boolean runs(String module) {
            String id = PetBehaviorCatalog.ID_PREFIX + module.toLowerCase(Locale.ROOT);
            for (LuaMindModuleDescriptor resolved : definition.resolvedModules())
                if (resolved.identifier().equals(id)) return true;
            return false;
        }

        /**
         * A fresh copy of the program for one pet body, with the extension's fields in every callback. It leaves out
         * {@link #leftOutSensors}. {@code hurtWithin} says whether the pet's latest hit from a player is at most that
         * many ticks past the 30 seconds c.pet.attacker keeps it, so {@code hurtWithin.test(0)} is whether
         * c.pet.attacker can be set. A sensor that acts only after such a hit (strike_back) runs only while that holds,
         * and retaliate's behaviors are asked to start only within their windows (see {@link #RETALIATE}). Everything
         * else runs as its module declares.
         */
        public MindProgram instantiate(LuaMindContextExtension extension, LongPredicate hurtWithin) {
            MindProgram full = definition.instantiate(extension);
            Map<String, Long> windows = hurtWindows(settings);
            if (full.sensors().stream().noneMatch(sensor -> leftOutSensors.contains(sensor.identifier())
                    || HURT_SENSORS.contains(sensor.identifier()))
                    && full.behaviors().stream().noneMatch(behavior -> windows.containsKey(behavior.identifier())))
                return full;
            MindProgram.Builder copy = MindProgram.builder(full.identifier(), full.revision())
                    .schema(full.schema())
                    .executionPolicy(full.executionPolicy());
            for (MindSensor sensor : full.sensors()) {
                if (leftOutSensors.contains(sensor.identifier())) continue;
                copy.sensor(HURT_SENSORS.contains(sensor.identifier())
                        ? new HurtSensor(sensor, () -> hurtWithin.test(0L)) : sensor);
            }
            for (MindBehavior behavior : full.behaviors()) {
                Long window = windows.get(behavior.identifier());
                if (window == null) {
                    copy.behavior(behavior);
                    continue;
                }
                long past = window;
                copy.behavior(new HurtBehavior(behavior, () -> hurtWithin.test(past)));
            }
            return copy.build();
        }
    }

    /**
     * How many ticks past c.pet.attacker's 30 seconds each of retaliate's behaviors can still start after a player's
     * latest hit, by behavior id; none for a program without retaliate. The flee's needs the module's flee_seconds,
     * read as retaliate.lua's ticks() reads it; without a number there, the flee is asked as it declares.
     */
    static Map<String, Long> hurtWindows(Map<String, Map<String, Object>> settings) {
        Map<String, Object> retaliate = settings.get(RETALIATE);
        if (retaliate == null) return Map.of();
        if (!(retaliate.get("flee_seconds") instanceof Number seconds) || Double.isNaN(seconds.doubleValue()))
            return Map.of(RETALIATE_CHARGE, 0L);
        long flee = Math.max(1L, (long) Math.floor(Math.min(seconds.doubleValue() * 20D, Integer.MAX_VALUE)));
        return Map.of(RETALIATE_CHARGE, 0L, RETALIATE_FLEE, flee);
    }

    /**
     * The longest tick interval among a program's behaviors, 1 when every one runs each tick: the most ticks that can
     * pass between two steers of a behavior that steers on every run.
     */
    public static int longestTickInterval(MindProgram program) {
        int longest = 1;
        for (MindBehavior behavior : program.behaviors()) longest = Math.max(longest, behavior.tickInterval());
        return longest;
    }

    /** A sensor whose Lua runs only while its pet's latest hit from a player is recent. */
    private record HurtSensor(MindSensor sensor, BooleanSupplier hurt) implements MindSensor {
        @Override
        public String identifier() {
            return sensor.identifier();
        }

        @Override
        public int intervalTicks() {
            return sensor.intervalTicks();
        }

        @Override
        public void sense(MindContext context) {
            if (hurt.getAsBoolean()) sensor.sense(context);
        }
    }

    /**
     * A behavior whose can_start is asked only while its pet's latest hit from a player is recent enough; otherwise
     * the probe answers false without Lua. Everything else is the behavior's own: each MindBehavior method is passed
     * on, so a method MagmaCore adds needs passing on here too, or it falls back to the interface's default.
     */
    private record HurtBehavior(MindBehavior behavior, BooleanSupplier hurt) implements MindBehavior {
        @Override
        public String identifier() {
            return behavior.identifier();
        }

        @Override
        public int priority() {
            return behavior.priority();
        }

        @Override
        public Set<MindControl> controls() {
            return behavior.controls();
        }

        @Override
        public int probeInterval() {
            return behavior.probeInterval();
        }

        @Override
        public int tickInterval() {
            return behavior.tickInterval();
        }

        @Override
        public boolean canStart(MindContext context) {
            return hurt.getAsBoolean() && behavior.canStart(context);
        }

        @Override
        public boolean canContinue(MindContext context) {
            return behavior.canContinue(context);
        }

        @Override
        public void start(MindContext context) {
            behavior.start(context);
        }

        @Override
        public void tick(MindContext context) {
            behavior.tick(context);
        }

        @Override
        public void stop(MindContext context, MindStopReason reason) {
            behavior.stop(context, reason);
        }
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
     * wander or go after an attacker (defend_owner); follow_owner's catch-up teleport still keeps an owned one with its
     * owner, and stay lets the owner manage it. It does not idle_look either: MagmaCore never ticks a stationary body's
     * look control, so the look would search for players and turn nothing.
     */
    public static List<PetBehaviorEntry> defaults(MindBodyLocomotion locomotion) {
        List<String> names = switch (locomotion) {
            case FLYING -> List.of("stay", "defend_owner", "follow_owner", "drift", "hover");
            case AQUATIC -> List.of("stay", "defend_owner", "follow_owner", "swim");
            case GROUNDED, AMPHIBIOUS -> List.of("stay", "defend_owner", "follow_owner", "wander", "idle_look");
            case STATIONARY -> List.of("stay", "follow_owner");
        };
        return names.stream().map(name -> new PetBehaviorEntry(name, Map.of())).toList();
    }

    /**
     * The program for one variant of a species, for bodies of either ownership: nothing is left out for ownership.
     * See {@link #program(String, String, Ownership, List, String)}.
     */
    public Program program(String species, String variant, List<PetBehaviorEntry> entries, String where) {
        return program(species, variant, null, entries, where);
    }

    /**
     * The program for one variant of a species and its bodies of this ownership (null for either), from its list: the
     * species' entries, then the variant's. {@code where} names the pet file in warnings. A module that is missing or
     * failed to load is left out with a warning; a module listed twice runs once, with its first entry's settings. A
     * listed module whose settings switch it off for the ownership is left out quietly, unless a module that stays
     * needs it. When the modules cannot all run together, they are added in list order and each one that clashes with
     * those kept before it is left out with a warning, so the earlier entry wins. The creature loads either way, and
     * the outcome is kept for the next call.
     */
    public Program program(String species, String variant, Ownership ownership, List<PetBehaviorEntry> entries,
                           String where) {
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(where, "where");
        return programs.computeIfAbsent(new Key(species, variant, ownership),
                key -> build(species, variant, ownership, entries, where));
    }

    private Program build(String species, String variant, Ownership ownership, List<PetBehaviorEntry> entries,
                          String where) {
        // Each listed module that loaded, once, in list order, with its settings as this pet file sets them. Problems
        // with those settings are said below, for the modules that run.
        Map<String, Listed> loaded = new LinkedHashMap<>();
        for (PetBehaviorEntry entry : entries) {
            Optional<LuaMindModuleDescriptor> module = catalog.module(entry.module());
            if (module.isEmpty()) warn.accept(missing(entry.module(), where));
            else if (!loaded.containsKey(module.get().identifier()))
                loaded.put(module.get().identifier(), new Listed(entry, module.get(), PetBehaviorSettings.merge(
                        entry.module(), module.get().settings(), entry.settings(), where, quiet -> {
                        })));
        }
        // The ones bodies of this ownership run: a module switched off for it goes, unless one that stays needs it.
        Set<String> off = new HashSet<>();
        List<Listed> on = new ArrayList<>();
        for (Listed module : loaded.values()) {
            if (switchedOff(module.settings(), ownership)) off.add(module.descriptor().identifier());
            else on.add(module);
        }
        off.removeAll(dependencies(on));
        Map<String, Listed> listed = new LinkedHashMap<>();
        for (Listed module : loaded.values())
            if (!off.contains(module.descriptor().identifier())) listed.put(module.descriptor().identifier(), module);
        String id = PetBehaviorCatalog.ID_PREFIX + "creature/" + key(species) + "/" + key(variant)
                + (ownership == null ? "" : "/" + ownership.name().toLowerCase(Locale.ROOT));
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
                return new Program(LuaMindDefinition.validate(label, source(id, List.of())), Map.of(), Set.of(), false);
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
        boolean readsContacts = false;
        for (LuaMindModuleDescriptor module : definition.resolvedModules()) {
            settings.putIfAbsent(module.identifier(), module.settings());
            readsContacts |= catalog.readsContacts(module.identifier());
        }
        // Only a wild pet's model records touches.
        if (ownership == Ownership.OWNED) readsContacts = false;
        Set<String> leftOut = new HashSet<>();
        if (ownership != null) ONE_SIDED_SENSORS.forEach((sensor, wild) -> {
            if (wild != (ownership == Ownership.WILD)) leftOut.add(sensor);
        });
        return new Program(definition, Collections.unmodifiableMap(settings), Set.copyOf(leftOut), readsContacts);
    }

    /** Whether a module's settings switch it off for bodies of this ownership; for either ownership, never. */
    private static boolean switchedOff(Map<String, Object> settings, Ownership ownership) {
        if (ownership == null) return false;
        return Boolean.FALSE.equals(settings.get(ownership == Ownership.WILD ? WILD_SWITCH : PETS_SWITCH));
    }

    /** Every module these modules need, directly or through another. */
    private Set<String> dependencies(List<Listed> modules) {
        Set<String> needed = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        for (Listed module : modules) pending.addAll(module.descriptor().dependencies());
        while (!pending.isEmpty()) {
            String dependency = pending.poll();
            if (needed.add(dependency))
                catalog.owner().module(dependency).ifPresent(module -> pending.addAll(module.dependencies()));
        }
        return needed;
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

    /** A program's species, variant and ownership; a null ownership is for bodies of either. */
    private record Key(String species, String variant, Ownership ownership) {
    }

    /** A listed module that loaded, with the entry that listed it first and its settings as that entry sets them. */
    private record Listed(PetBehaviorEntry entry, LuaMindModuleDescriptor descriptor, Map<String, Object> settings) {
    }
}
