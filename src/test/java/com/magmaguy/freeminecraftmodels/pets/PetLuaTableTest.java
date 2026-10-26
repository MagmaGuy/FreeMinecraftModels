package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindMemoryView;
import com.magmaguy.magmacore.ai.MindNavigationStatus;
import com.magmaguy.magmacore.ai.MindPerception;
import com.magmaguy.magmacore.ai.MindPosition;
import com.magmaguy.magmacore.scripting.LuaEnvironmentFactory;
import com.magmaguy.shaded.luaj.vm2.Globals;
import com.magmaguy.shaded.luaj.vm2.LuaError;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;
import com.magmaguy.shaded.luaj.vm2.Varargs;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * c.pet as a module sees it, in the sandbox Mind modules run in, which keeps rawset and setmetatable, and the helpers
 * behind its methods. A whole PetLuaTable needs a server, so this drives the parts it is built from.
 */
class PetLuaTableTest {
    private final List<String> messages = new ArrayList<>();
    private final List<Throwable> causes = new ArrayList<>();
    private PetLuaTable.Warnings warnings = new PetLuaTable.Warnings((message, failure) -> {
        messages.add(message);
        causes.add(failure);
    });
    private String species = "jellyfish";
    private final PetLuaTable.Fields pet = new PetLuaTable.Fields("c.pet",
            (call, failure) -> warnings.failure(species, "fmm:pet/test", call, failure));
    private double speed = 1D;
    private final World world = world("world");

    PetLuaTableTest() {
        pet.value("speed", () -> LuaValue.valueOf(speed));
        pet.method("add", args -> LuaValue.valueOf(args.checkdouble(1) + args.checkdouble(2)));
        pet.value("broken", () -> {
            throw new IllegalStateException("the body is gone");
        });
        pet.method("explode", args -> {
            throw new IllegalStateException("the model is gone");
        });
        pet.method("unlinked", args -> {
            throw new NoSuchFieldError("SCALE");
        });
    }

    private LuaValue run(String script) {
        Globals globals = LuaEnvironmentFactory.createGlobals();
        LuaTable c = new LuaTable();
        c.set("pet", pet.view());
        globals.set("c", c);
        return globals.load(script, "module").call();
    }

    private String error(String expression) {
        return run("local ok, message = pcall(function() return " + expression + " end) return message").tojstring();
    }

    @Test
    void propertiesAreReadFreshOnEveryAccess() {
        assertEquals(1D, run("return c.pet.speed").todouble());
        speed = 2D;
        assertEquals(2D, run("return c.pet.speed").todouble());
        assertTrue(run("return c.pet.unknown == nil").toboolean());
    }

    @Test
    void methodsTakeTheSameArgumentsWithAColonOrADot() {
        assertEquals(33D, run("return c.pet:add(1, 2) + c.pet.add(10, 20)").todouble());
    }

    @Test
    void modulesCannotChangeTheTable() {
        LuaError write = assertThrows(LuaError.class, () -> run("c.pet.speed = 5"));
        assertTrue(write.getMessage().contains("c.pet.speed cannot be set; store per-pet values in c.memory"),
                write.getMessage());
        for (String script : List.of("c.pet.add = function() return 0 end", "c.pet.new_field = 1",
                "rawset(c.pet, 'speed', 5)", "table.insert(c.pet, 1)", "setmetatable(c.pet, nil)",
                "setmetatable(c.pet, {})"))
            assertThrows(LuaError.class, () -> run(script), script);
        assertEquals(1D, run("return c.pet.speed").todouble());
        assertEquals(3D, run("return c.pet:add(1, 2)").todouble());
        assertEquals(LuaValue.FALSE, run("return getmetatable(c.pet)"));
        assertEquals(LuaValue.NIL, run("return next(c.pet)"));
    }

    @Test
    void javaFailuresAreSaidOncePerSpeciesAndNameEachLoad() {
        for (int pets = 0; pets < 3; pets++) {
            assertTrue(error("c.pet.broken").contains("c.pet.broken failed; the server log has the cause"));
            assertTrue(error("c.pet:explode()").contains("c.pet:explode failed; the server log has the cause"));
        }
        assertEquals(List.of(
                "The jellyfish pet behavior fmm:pet/test called c.pet.broken, which failed: "
                        + "java.lang.IllegalStateException: the body is gone",
                "The jellyfish pet behavior fmm:pet/test called c.pet:explode, which failed: "
                        + "java.lang.IllegalStateException: the model is gone"), messages);
        assertEquals("the body is gone", causes.getFirst().getMessage());
        species = "moth";
        error("c.pet.broken");
        assertEquals(3, messages.size(), "another species is said again");
        warnings = new PetLuaTable.Warnings((message, failure) -> messages.add(message));
        error("c.pet.broken");
        assertEquals(4, messages.size(), "the next load says it again");
    }

    @Test
    void linkageErrorsAreCaughtLikeExceptions() {
        assertTrue(error("c.pet:unlinked()").contains("c.pet:unlinked failed; the server log has the cause"));
        assertTrue(causes.getFirst() instanceof NoSuchFieldError);
    }

    @Test
    void argumentErrorsPassThroughUnreported() {
        assertTrue(error("c.pet:add('one', 2)").contains("number expected"));
        assertEquals(List.of(), messages);
    }

    @Test
    void unknownEffectsAreSaidOncePerSpeciesAndEffectEachLoad() {
        warnings.unknownEffect("jellyfish", "fmm:pet/sting", "c.pet:sting_contacts", "SLOWNES");
        warnings.unknownEffect("jellyfish", "fmm:pet/sting", "c.pet:sting_enemies", "slownes");
        warnings.unknownEffect("moth", "fmm:pet/sting", "c.pet:sting_contacts", "SLOWNES");
        assertEquals(List.of("The jellyfish pet behavior fmm:pet/sting gave c.pet:sting_contacts the potion effect "
                        + "SLOWNES, which does not exist; it applies none.",
                "The moth pet behavior fmm:pet/sting gave c.pet:sting_contacts the potion effect SLOWNES, which does "
                        + "not exist; it applies none."), messages);
        assertNull(causes.getFirst());
    }

    private static Varargs args(LuaValue... values) {
        return LuaValue.varargsOf(values);
    }

    @Test
    void numbersMustBeFiniteAndAreClamped() {
        for (double refused : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThrows(LuaError.class, () -> PetLuaTable.number(args(LuaValue.valueOf(refused)), 1));
        assertThrows(LuaError.class, () -> PetLuaTable.number(args(LuaValue.NIL), 1));
        assertEquals(64D, PetLuaTable.number(args(LuaValue.valueOf(1e9)), 1, 0D, 64D));
        assertEquals(0D, PetLuaTable.number(args(LuaValue.valueOf(-3)), 1, 0D, 64D));
        assertEquals(2.5D, PetLuaTable.number(args(LuaValue.valueOf("2.5")), 1, 0D, 64D));
        assertEquals(2, PetLuaTable.whole(args(LuaValue.valueOf(2.7)), 1, 0, 4));
        assertEquals(4, PetLuaTable.whole(args(LuaValue.valueOf(1e12)), 1, 0, 4));
        assertEquals(1, PetLuaTable.whole(args(LuaValue.valueOf(-1e12)), 1, 1, 8));
        assertThrows(LuaError.class, () -> PetLuaTable.whole(args(LuaValue.valueOf(Double.NaN)), 1, 0, 4));
    }

    @Test
    void anInfiniteCruiseIsClampedLikeAnyOther() {
        double cap = MindActuator.MAX_FLIGHT_SPEED;
        assertEquals(cap, PetLuaTable.cruise(args(LuaValue.valueOf(Double.POSITIVE_INFINITY)), 1));
        assertEquals(cap, PetLuaTable.cruise(args(LuaValue.valueOf(1e300)), 1));
        assertEquals(0.25D, PetLuaTable.cruise(args(LuaValue.valueOf(0.25)), 1));
        assertEquals(0D, PetLuaTable.cruise(args(LuaValue.valueOf(-5)), 1));
        assertEquals(0D, PetLuaTable.cruise(args(LuaValue.valueOf(-1e300)), 1));
        assertEquals(0D, PetLuaTable.cruise(args(LuaValue.valueOf(Double.NEGATIVE_INFINITY)), 1));
        assertThrows(LuaError.class, () -> PetLuaTable.cruise(args(LuaValue.valueOf(Double.NaN)), 1));
        assertThrows(LuaError.class, () -> PetLuaTable.cruise(args(LuaValue.NIL), 1));
    }

    private static LuaTable location(Object world, double x, double y, double z) {
        LuaTable table = new LuaTable();
        if (world != null) table.set("world", world.toString());
        table.set("x", x);
        table.set("y", y);
        table.set("z", z);
        return table;
    }

    @Test
    void locationsElsewhereAreNothingAndCoordinatesMustBeFinite() {
        Location here = PetLuaTable.at(args(location("world", 1.5, 70, -2)), 1, world);
        assertSame(world, here.getWorld());
        assertEquals(1.5D, here.getX());
        assertEquals(-2D, here.getZ());
        assertSame(world, PetLuaTable.at(args(location(null, 0, 64, 0)), 1, world).getWorld());
        assertNull(PetLuaTable.at(args(location("world_nether", 0, 64, 0)), 1, world));
        assertThrows(LuaError.class, () -> PetLuaTable.at(args(location("world", Double.NaN, 64, 0)), 1, world));
        assertThrows(LuaError.class, () -> PetLuaTable.at(args(location("world", 0, Double.POSITIVE_INFINITY, 0)), 1, world));
        LuaTable missing = new LuaTable();
        missing.set("x", 1);
        assertThrows(LuaError.class, () -> PetLuaTable.at(args(missing), 1, world));
        assertThrows(LuaError.class, () -> PetLuaTable.at(args(LuaValue.valueOf(3)), 1, world));
    }

    private static LuaTable list(String... entries) {
        LuaTable table = new LuaTable();
        for (int index = 0; index < entries.length; index++) table.set(index + 1, entries[index]);
        return table;
    }

    @Test
    void listsAreReadRawAndCapped() {
        assertEquals(Set.of("a", "b"), PetLuaTable.texts(list("a", "b")));
        assertEquals(Set.of(), PetLuaTable.texts(LuaValue.valueOf("a")));
        String[] many = new String[200];
        for (int index = 0; index < many.length; index++) many[index] = "entry" + index;
        assertEquals(PetLuaTable.MAX_LIST_ENTRIES, PetLuaTable.texts(list(many)).size());
        // A length metamethod claiming a billion entries cannot stretch the read.
        LuaTable stretched = list("a");
        LuaTable metatable = new LuaTable();
        metatable.set("__len", new com.magmaguy.shaded.luaj.vm2.lib.OneArgFunction() {
            @Override
            public LuaValue call(LuaValue table) {
                return LuaValue.valueOf(1_000_000_000);
            }
        });
        stretched.setmetatable(metatable);
        assertEquals(Set.of("a"), PetLuaTable.texts(stretched));
    }

    @Test
    void campfireBlocksDefaultToBothKinds() {
        Set<Material> both = Set.of(Material.CAMPFIRE, Material.SOUL_CAMPFIRE);
        assertEquals(both, PetLuaTable.materials(list()));
        assertEquals(both, PetLuaTable.materials(LuaValue.NIL));
        assertEquals(both, PetLuaTable.materials(list("STONE", "torch")));
        assertEquals(Set.of(Material.SOUL_CAMPFIRE), PetLuaTable.materials(list("soul_campfire", "STONE")));
        assertEquals(both, PetLuaTable.materials(list("CAMPFIRE", "minecraft:soul_campfire")));
    }

    @Test
    void theSpeciesTableIsSealedAndComplete() {
        Map<String, LuaValue> values = new HashMap<>();
        for (String field : PetLuaTable.SPECIES_FIELDS) values.put(field, LuaValue.valueOf(field.length()));
        LuaTable species = PetLuaTable.speciesTable(values);
        assertEquals(5D, species.get("speed").todouble());
        assertThrows(LuaError.class, () -> species.set("speed", LuaValue.valueOf(9)));
        assertThrows(LuaError.class, () -> species.rawset("speed", LuaValue.valueOf(9)));
        assertEquals(5D, species.get("speed").todouble());
        values.remove("baby_scale");
        assertThrows(IllegalStateException.class, () -> PetLuaTable.speciesTable(values));
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object plain(Object proxy, java.lang.reflect.Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "stub";
            default -> {
                Class<?> type = method.getReturnType();
                if (type == boolean.class) yield false;
                if (type == double.class) yield 0D;
                if (type == int.class) yield 0;
                if (type == long.class) yield 0L;
                if (type == Optional.class) yield Optional.empty();
                yield null;
            }
        };
    }

    private static World world(String name) {
        return stub(World.class, (proxy, method, args) -> method.getName().equals("getName") ? name : plain(proxy, method, args));
    }

    @Test
    void leaseRefusalsBecomeAPlainLuaError() {
        MindActuator refusing = stub(MindActuator.class, (proxy, method, args) -> {
            if (method.getName().equals("stopMoving"))
                throw new IllegalStateException("Mind behavior must declare and hold the MOVE control");
            return plain(proxy, method, args);
        });
        LuaError refused = assertThrows(LuaError.class, () -> PetLuaTable.leased("c.pet:stop", "ai.controls.move", () -> {
            refusing.stopMoving();
            return null;
        }));
        assertEquals("c.pet:stop needs a behavior that declares ai.controls.move; call it from a behavior's start, "
                + "tick or stop, not from can_start or a sensor", refused.getMessage());
        IllegalStateException other = new IllegalStateException("Mind host must run on the primary thread");
        assertSame(other, assertThrows(IllegalStateException.class, () -> PetLuaTable.leased("c.pet:stop", "ai.controls.move", () -> {
            throw other;
        })));
    }

    /** A walking body at the origin whose navigation remembers the last move request until it is reset. */
    private final class Walker {
        final List<Location> requests = new ArrayList<>();
        MindPosition destination;
        long tick;
        final MindActuator actuator = stub(MindActuator.class, (proxy, method, args) -> {
            if (method.getName().equals("moveTo")) {
                Location to = (Location) args[0];
                requests.add(to);
                destination = MindPosition.from(to);
                return true;
            }
            return plain(proxy, method, args);
        });
        final MindPerception perception = stub(MindPerception.class, (proxy, method, args) -> method.getName().equals("navigation")
                ? (destination == null ? MindNavigationStatus.idle()
                : new MindNavigationStatus(Optional.of(destination), true, Optional.empty(), 0))
                : plain(proxy, method, args));
        final LivingEntity body = stub(LivingEntity.class, (proxy, method, args) -> method.getName().equals("getLocation")
                ? new Location(world, 0, 64, 0) : plain(proxy, method, args));

        MindContext context() {
            return new MindContext(body, tick, 1L, stub(MindMemoryView.class, PetLuaTableTest::plain), perception, actuator);
        }
    }

    @Test
    void aWalkingPathIsKeptOnlyWhileNavigationStillHeadsForIt() {
        Walker walker = new Walker();
        PetLuaTable.WalkPath path = new PetLuaTable.WalkPath();
        Location target = new Location(world, 10, 64, 0);
        assertTrue(path.steer(walker.context(), target, 0.06D));
        walker.tick = 5;
        assertTrue(path.steer(walker.context(), new Location(world, 10.5, 64, 0), 0.06D));
        assertEquals(1, walker.requests.size(), "a nearby target on the same request keeps the path");
        // The behavior stopped and its MOVE lease went: navigation forgot the request.
        walker.destination = null;
        walker.tick = 6;
        path.steer(walker.context(), target, 0.06D);
        assertEquals(2, walker.requests.size(), "a released lease asks again at once");
        walker.tick = 7;
        path.steer(walker.context(), target, 0.15D);
        assertEquals(3, walker.requests.size(), "another cruise asks again");
        walker.tick = 40;
        path.steer(walker.context(), target, 0.15D);
        assertEquals(4, walker.requests.size(), "a second later it asks again");
        walker.destination = MindPosition.from(new Location(world, -20, 64, 0));
        walker.tick = 41;
        path.steer(walker.context(), target, 0.15D);
        assertEquals(5, walker.requests.size(), "navigation heading elsewhere asks again");
        path.forget();
        walker.tick = 42;
        path.steer(walker.context(), target, 0.15D);
        assertEquals(6, walker.requests.size(), "a forgotten path asks again");
    }
}
