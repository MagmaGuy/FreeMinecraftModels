package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.freeminecraftmodels.pets.PetLuaTable;
import com.magmaguy.magmacore.ai.MindActionRequester;
import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindMemoryView;
import com.magmaguy.magmacore.ai.MindPerception;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindSensor;
import com.magmaguy.magmacore.ai.MindStopReason;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;
import com.magmaguy.shaded.luaj.vm2.Varargs;
import com.magmaguy.shaded.luaj.vm2.lib.VarArgFunction;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShippedPetModulesTest {
    @TempDir
    Path folder;
    private final List<String> warnings = new ArrayList<>();

    private PetBehaviorCatalog catalog() {
        return PetBehaviorCatalog.load(folder, PetBehaviorCatalog.DEFAULTS, name -> {
            try (InputStream in = getClass().getResourceAsStream("/pet_behaviors/" + name + ".lua")) {
                if (in == null) throw new IllegalStateException("No resource for the default " + name);
                Files.copy(in, folder.resolve(name + ".lua"));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }, warnings::add);
    }

    @Test
    void everyShippedModuleLoads() {
        try (PetBehaviorCatalog catalog = catalog()) {
            assertEquals(Set.copyOf(PetBehaviorCatalog.DEFAULTS), catalog.names());
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void everyDefaultListRunsEveryCallback() {
        try (PetBehaviorCatalog catalog = catalog()) {
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            // Task 16 (when wander, idle_look and swim ship) drops this filter, so every locomotion is exercised.
            List<MindBodyLocomotion> locomotions = Arrays.stream(MindBodyLocomotion.values())
                    .filter(ShippedPetModulesTest::shipped).toList();
            assertTrue(locomotions.contains(MindBodyLocomotion.FLYING), "the flying list ships, so it is exercised");
            for (MindBodyLocomotion locomotion : locomotions)
                exercise(programs.program("test", locomotion.name(), PetPrograms.defaults(locomotion), locomotion.name()));
            exercise(programs.program("test", "everything", PetBehaviorCatalog.DEFAULTS.stream()
                    .map(name -> new PetBehaviorEntry(name, Map.of())).toList(), "everything"));
        }
        assertEquals(List.of(), warnings);
    }

    /** Whether every module of a locomotion's default list ships already. */
    private static boolean shipped(MindBodyLocomotion locomotion) {
        return PetPrograms.defaults(locomotion).stream()
                .allMatch(entry -> PetBehaviorCatalog.DEFAULTS.contains(entry.module()));
    }

    private static void exercise(PetPrograms.Program program) {
        for (boolean wild : new boolean[]{true, false})
            for (boolean staying : new boolean[]{false, true})
                for (boolean night : new boolean[]{false, true}) {
                    LuaTable pet = stubPet(wild, staying, night);
                    MindProgram instance = program.definition().instantiate((table, context, contribution) -> {
                        table.set("pet", pet);
                        Map<String, Object> values = program.settings().getOrDefault(contribution, Map.of());
                        table.set("settings", PetBehaviorSettings.toLua(values));
                    });
                    MindContext context = context();
                    for (int tick = 0; tick < 3; tick++) {
                        for (MindSensor sensor : instance.sensors()) sensor.sense(context);
                        for (MindBehavior behavior : instance.behaviors()) {
                            if (behavior.canStart(context)) behavior.start(context);
                            behavior.tick(context);
                            behavior.canContinue(context);
                            behavior.stop(context, MindStopReason.values()[0]);
                        }
                    }
                }
    }

    private static LuaTable location() {
        LuaTable table = new LuaTable();
        table.set("world", "world");
        table.set("x", 0.5);
        table.set("y", 70);
        table.set("z", 0.5);
        return table;
    }

    /** A c.pet with exactly PetLuaTable's names: properties for the given state, methods returning plain values. */
    static LuaTable stubPet(boolean wild, boolean staying, boolean night) {
        LuaTable species = new LuaTable();
        for (String field : PetLuaTable.SPECIES_FIELDS) species.set(field, field.equals("id") ? LuaValue.valueOf("test") : LuaValue.valueOf(1.5));
        Map<String, LuaValue> properties = new HashMap<>();
        properties.put("wild", LuaValue.valueOf(wild));
        properties.put("staying", LuaValue.valueOf(staying));
        properties.put("settled", LuaValue.valueOf(!staying));
        properties.put("baby", LuaValue.FALSE);
        properties.put("migrating", LuaValue.valueOf(wild));
        properties.put("model", LuaValue.valueOf("em_pet_jellyfish"));
        properties.put("species", species);
        properties.put("position", location());
        properties.put("home", location());
        properties.put("anchor", location());
        properties.put("stay_anchor", staying ? location() : LuaValue.NIL);
        properties.put("owner_location", wild ? LuaValue.NIL : location());
        properties.put("owner_distance", LuaValue.valueOf(wild ? Double.POSITIVE_INFINITY : 30));
        properties.put("night", LuaValue.valueOf(night));
        properties.put("day", LuaValue.valueOf(!night));
        properties.put("raining", LuaValue.valueOf(night));
        properties.put("speed", LuaValue.valueOf(0));
        properties.put("route_cruise", LuaValue.valueOf(0.3));
        assertEquals(PetLuaTable.PROPERTIES, properties.keySet(), "the stub's properties match PetLuaTable's");
        LuaTable pet = new LuaTable();
        properties.forEach(pet::set);
        for (String method : PetLuaTable.METHODS) {
            LuaValue result = switch (method) {
                case "hover_point", "ground_point", "water_point", "trail_point", "route_target", "find_perch",
                     "nearest_campfire" -> location();
                case "ground_below", "distance_to", "sting_contacts", "sting_enemies" -> LuaValue.valueOf(1);
                case "steer_to", "teleport_near_owner", "campfire_usable", "drizzle", "owner_effect", "play_animation" -> LuaValue.TRUE;
                default -> LuaValue.NIL;
            };
            pet.set(method, new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs args) {
                    return result;
                }
            });
        }
        return pet;
    }

    /** A Mind context whose memory really remembers, and whose other parts answer with plain defaults. */
    private static MindContext context() {
        Map<Object, Object> memory = new HashMap<>();
        MindMemoryView view = stub(MindMemoryView.class, (proxy, method, args) -> switch (method.getName()) {
            case "get" -> Optional.ofNullable(memory.get(args[0]));
            case "set" -> memory.put(args[0], args[1]);
            case "forget" -> memory.remove(args[0]);
            case "contains" -> memory.containsKey(args[0]);
            default -> null;
        });
        return new MindContext(stub(LivingEntity.class, ShippedPetModulesTest::plain), 0L, 1L, view,
                stub(MindPerception.class, ShippedPetModulesTest::plain), stub(MindActuator.class, ShippedPetModulesTest::plain),
                stub(MindActionRequester.class, ShippedPetModulesTest::plain));
    }

    private static Object plain(Object proxy, java.lang.reflect.Method method, Object[] args) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == Optional.class) return Optional.empty();
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
