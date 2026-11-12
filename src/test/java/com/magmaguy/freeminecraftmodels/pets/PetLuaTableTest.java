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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void aFieldAsksItsSupplierOnEveryRead() {
        assertEquals(1D, run("return c.pet.speed").todouble());
        speed = 2D;
        assertEquals(2D, run("return c.pet.speed").todouble());
        assertTrue(run("return c.pet.unknown == nil").toboolean());
    }

    /** c.pet with one property read through {@code ticks}, from {@code source}. */
    private LuaValue readThrough(PetLuaTable.Ticks ticks, Supplier<LuaValue> source) {
        PetLuaTable.Fields kept = new PetLuaTable.Fields("c.pet",
                (call, failure) -> warnings.failure(species, "fmm:pet/test", call, failure));
        kept.value("speed", ticks.perTick(source));
        Globals globals = LuaEnvironmentFactory.createGlobals();
        LuaTable c = new LuaTable();
        c.set("pet", kept.view());
        globals.set("c", c);
        return globals.load("return function() return c.pet.speed end", "module").call();
    }

    @Test
    void aPropertyIsReadOncePerTickAndAgainOnTheNextTick() {
        PetLuaTable.Ticks ticks = new PetLuaTable.Ticks();
        int[] reads = {0};
        LuaValue read = readThrough(ticks, () -> {
            reads[0]++;
            return LuaValue.valueOf(speed);
        });
        Object body = new Object();

        assertEquals(1D, read.call().todouble());
        speed = 2D;
        assertEquals(2D, read.call().todouble(), "before any callback binds a tick, every read asks");

        ticks.bind(7L, 100L, body);
        assertEquals(2D, read.call().todouble());
        speed = 3D;
        int before = reads[0];
        assertEquals(2D, read.call().todouble(), "the same tick keeps its value");
        ticks.bind(7L, 100L, body);
        assertEquals(2D, read.call().todouble(), "another callback on the same tick keeps it too");
        assertEquals(before, reads[0]);

        ticks.bind(8L, 101L, body);
        assertEquals(3D, read.call().todouble(), "the next tick reads it again");
    }

    @Test
    void aMoveAnotherBodyOrAnotherClockReadsAfresh() {
        PetLuaTable.Ticks ticks = new PetLuaTable.Ticks();
        LuaValue read = readThrough(ticks, () -> LuaValue.valueOf(speed));
        Object body = new Object();
        ticks.bind(7L, 100L, body);
        assertEquals(1D, read.call().todouble());

        speed = 2D;
        ticks.moved();
        assertEquals(2D, read.call().todouble(), "c.pet moved the body");
        speed = 3D;
        ticks.bind(7L, 100L, new Object());
        assertEquals(3D, read.call().todouble(), "another body");
        speed = 4D;
        ticks.bind(7L, 55L, body);
        assertEquals(4D, read.call().todouble(), "another world's game tick");
        speed = 5D;
        ticks.bind(9L, 55L, body);
        assertEquals(5D, read.call().todouble(), "the next pet tick in a world whose time stands still");
    }

    @Test
    void aFailedReadIsReportedAndNotKept() {
        PetLuaTable.Ticks ticks = new PetLuaTable.Ticks();
        boolean[] broken = {true};
        LuaValue read = readThrough(ticks, () -> {
            if (broken[0]) throw new IllegalStateException("the body is gone");
            return LuaValue.valueOf(speed);
        });
        ticks.bind(7L, 100L, new Object());

        assertThrows(LuaError.class, read::call);
        assertEquals(1, messages.size());
        broken[0] = false;
        assertEquals(1D, read.call().todouble(), "the same tick asks again after a failure");
    }

    @Test
    void aKeptLocationGivesAFreshTableOnEveryRead() {
        // c.pet's location path: a location kept for the tick, a table built from it on every read.
        PetLuaTable.Ticks ticks = new PetLuaTable.Ticks();
        PetLuaTable.LocationTables tables = new PetLuaTable.LocationTables();
        int[] reads = {0};
        Supplier<Location> kept = ticks.perTick(() -> {
            reads[0]++;
            return new Location(world, 1.5D, 64D, -2D);
        });
        PetLuaTable.Fields located = new PetLuaTable.Fields("c.pet",
                (call, failure) -> warnings.failure(species, "fmm:pet/test", call, failure));
        located.value("position", () -> tables.table(kept.get()));
        Globals globals = LuaEnvironmentFactory.createGlobals();
        LuaTable c = new LuaTable();
        c.set("pet", located.view());
        globals.set("c", c);
        ticks.bind(7L, 100L, new Object());

        Varargs both = globals.load("""
                local first = c.pet.position
                first.x = 99
                local second = c.pet.position
                return first == second, second.world, second.x, second.y, second.z
                """, "module").invoke();
        assertFalse(both.arg(1).toboolean(), "each read is its own table");
        assertEquals("world", both.arg(2).tojstring());
        assertEquals(1.5D, both.arg(3).todouble(), "changing one read leaves the next alone");
        assertEquals(64D, both.arg(4).todouble());
        assertEquals(-2D, both.arg(5).todouble());
        assertEquals(1, reads[0], "one location for the tick");
    }

    @Test
    void locationTablesNameTheirWorldOnceUntilItChanges() {
        PetLuaTable.LocationTables tables = new PetLuaTable.LocationTables();
        LuaValue first = tables.table(new Location(world, 0D, 64D, 0D)).get("world");
        assertSame(first, tables.table(new Location(world, 5D, 70D, 5D)).get("world"));
        World nether = world("world_nether");
        assertEquals("world_nether", tables.table(new Location(nether, 0D, 64D, 0D)).get("world").tojstring());
        assertTrue(tables.table(new Location(null, 0D, 64D, 0D)).get("world").isnil());
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

    @Test
    void unknownParticlesAreSaidOncePerSpeciesAndParticleEachLoad() {
        warnings.unknownParticle("penguin", "fmm:pet/strike_back", "c.pet:strike_attacker", "SNOWFLAKES");
        warnings.unknownParticle("penguin", "fmm:pet/strike_back", "c.pet:strike_attacker", "snowflakes");
        warnings.unknownEffect("penguin", "fmm:pet/strike_back", "c.pet:strike_attacker", "SNOWFLAKES");
        assertEquals(List.of("The penguin pet behavior fmm:pet/strike_back gave c.pet:strike_attacker the particle "
                        + "SNOWFLAKES, which does not exist or needs more than a name; it shows none.",
                "The penguin pet behavior fmm:pet/strike_back gave c.pet:strike_attacker the potion effect SNOWFLAKES, "
                        + "which does not exist; it applies none."), messages);
    }

    @Test
    void aStrikePlaysTheAnimationItNamesAndNoneForNone() {
        assertEquals("attack", PetLuaTable.STING_ANIMATION, "what a strike plays when its module names none");
        assertEquals("headbutt", PetLuaTable.strikeAnimation("headbutt"));
        assertEquals("attack.headbutt", PetLuaTable.strikeAnimation(" attack.headbutt "));
        assertNull(PetLuaTable.strikeAnimation("NONE"));
        assertNull(PetLuaTable.strikeAnimation("none"));
        assertNull(PetLuaTable.strikeAnimation(""));
        assertNull(PetLuaTable.strikeAnimation(null));
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
    void anOwnerEffectLeavesOnlyAStrongerAndLongerOneAlone() {
        // A drunk potion: as strong or stronger, with more time left, or infinite.
        assertTrue(PetLuaTable.covers(0, false, 9600, 0, 300));
        assertTrue(PetLuaTable.covers(1, false, 300, 0, 300));
        assertTrue(PetLuaTable.covers(0, true, -1, 0, 300));
        // The pet's own effect a second on, with 280 ticks left, is renewed.
        assertFalse(PetLuaTable.covers(0, false, 280, 0, 300));
        // Weaker, however long: renewed.
        assertFalse(PetLuaTable.covers(0, true, -1, 1, 300));
        assertFalse(PetLuaTable.covers(0, false, 9600, 1, 300));
        // Stronger but shorter: renewed, and vanilla keeps it hidden beneath the stronger one.
        assertFalse(PetLuaTable.covers(2, false, 100, 0, 300));
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
        final List<Double> speeds = new ArrayList<>();
        int stops;
        MindPosition destination;
        long tick;
        final MindActuator actuator = stub(MindActuator.class, (proxy, method, args) -> {
            if (method.getName().equals("moveTo")) {
                Location to = (Location) args[0];
                requests.add(to);
                speeds.add((Double) args[1]);
                destination = MindPosition.from(to);
                return true;
            }
            if (method.getName().equals("stopMoving")) {
                stops++;
                destination = null;
                return null;
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
    void aWalkAtAMovingTargetKeepsItsPathWhileTheTargetStaysAhead() {
        Walker walker = new Walker();
        PetLuaTable.WalkPath path = new PetLuaTable.WalkPath();
        assertTrue(path.steer(walker.context(), new Location(world, 10, 64, 0), 0.06D));
        // The target runs on along the line of the walk: more than a block from the request, within 25 degrees.
        walker.tick = 4;
        path.steer(walker.context(), new Location(world, 12, 64, 0.5), 0.06D);
        walker.tick = PetLuaTable.WalkPath.REPATH_TICKS - 1;
        path.steer(walker.context(), new Location(world, 13, 64, 1), 0.06D);
        assertEquals(1, walker.requests.size(), "a target still ahead keeps the request for REPATH_TICKS");
        walker.tick = PetLuaTable.WalkPath.REPATH_TICKS;
        path.steer(walker.context(), new Location(world, 13, 64, 1), 0.06D);
        assertEquals(2, walker.requests.size(), "then the walk asks for the target where it is now");
        // A target that turns away sharply is asked for at once.
        walker.tick = PetLuaTable.WalkPath.REPATH_TICKS + 2;
        path.steer(walker.context(), new Location(world, 4, 64, 12), 0.06D);
        assertEquals(3, walker.requests.size(), "a turn of more than 25 degrees asks again");
        // So is one the body is nearly at: the old point would lead it astray.
        path.forget();
        walker.tick = 100;
        path.steer(walker.context(), new Location(world, 1.5, 64, 0), 0.06D);
        walker.tick = 102;
        path.steer(walker.context(), new Location(world, 1.5, 64, 1.2), 0.06D);
        assertEquals(5, walker.requests.size(), "a target within 2 blocks asks again when it moves a block");
    }

    @Test
    void aFlyersStuckCountFollowsItsSteersTickByTick() {
        PetLuaTable.FlightTrack track = new PetLuaTable.FlightTrack();
        Location here = new Location(world, 0, 64, 0), target = new Location(world, 10, 64, 0);
        assertEquals(0, track.stuck(0, here), "nothing steered yet");
        for (long tick = 1; tick <= 25; tick++) track.steered(tick, here, target, "fmm:pet/drift");
        assertEquals(24, track.stuck(25, here), "a body that stays put counts a tick for each tick after the first steer");
        // Progress of more than 0.05 blocks starts the count again.
        track.steered(26, new Location(world, 0.2, 64, 0), target, "fmm:pet/drift");
        assertEquals(0, track.stuck(26, new Location(world, 0.2, 64, 0)));
        track.steered(27, new Location(world, 0.22, 64, 0), target, "fmm:pet/drift");
        assertEquals(1, track.stuck(27, new Location(world, 0.22, 64, 0)), "a creep under 0.05 blocks is no progress");
        // Another module steering starts it again, and so does a tick without a steer.
        track.steered(28, new Location(world, 0.22, 64, 0), target, "fmm:pet/hover");
        assertEquals(0, track.stuck(28, new Location(world, 0.22, 64, 0)));
        track.steered(29, new Location(world, 0.22, 64, 0), target, "fmm:pet/hover");
        assertEquals(1, track.stuck(29, new Location(world, 0.22, 64, 0)));
        assertEquals(0, track.stuck(31, new Location(world, 0.22, 64, 0)), "a tick passed without a steer");
        // Within half a block of the point steered at, the flight has arrived.
        track.steered(40, here, target, "fmm:pet/drift");
        track.steered(41, here, target, "fmm:pet/drift");
        assertEquals(1, track.stuck(41, here));
        track.steered(42, new Location(world, 9.7, 64, 0), target, "fmm:pet/drift");
        assertEquals(0, track.stuck(43, new Location(world, 9.7, 64, 0)));
        track.steered(50, here, target, "fmm:pet/drift");
        track.steered(51, here, target, "fmm:pet/drift");
        track.reset();
        assertEquals(0, track.stuck(52, here), "c.pet:stop ends the count");
    }

    @Test
    void aFlyerSteeredEveryNthTickStillCountsAsStuck() {
        Location here = new Location(world, 0, 64, 0), target = new Location(world, 10, 64, 0);
        // Without the allowance a behavior that ticks every 5th tick never counts: each gap ends the flight.
        PetLuaTable.FlightTrack every = new PetLuaTable.FlightTrack();
        for (long tick = 0; tick <= 60; tick += 5) every.steered(tick, here, target, "fmm:pet/drift");
        assertEquals(0, every.stuck(60, here));
        // Told the program's longest tick interval, it counts game ticks across the gaps.
        PetLuaTable.FlightTrack allowed = new PetLuaTable.FlightTrack();
        allowed.allow(5);
        for (long tick = 0; tick <= 60; tick += 5) allowed.steered(tick, here, target, "fmm:pet/drift");
        assertEquals(60, allowed.stuck(60, here), "twelve gaps of five ticks");
        assertEquals(63, allowed.stuck(63, here), "and on between steers");
        assertEquals(0, allowed.stuck(66, here), "more than the allowance without a steer ends the flight");
        // Progress still starts it again.
        allowed.steered(70, here, target, "fmm:pet/drift");
        allowed.steered(75, new Location(world, 1, 64, 0), target, "fmm:pet/drift");
        assertEquals(0, allowed.stuck(75, new Location(world, 1, 64, 0)));
        allowed.allow(0);
        allowed.steered(80, here, target, "fmm:pet/drift");
        allowed.steered(81, here, target, "fmm:pet/drift");
        assertEquals(0, allowed.stuck(83, here), "an allowance under 1 is 1");
    }

    @Test
    void aMaterialListIsParsedOnceForTheSameEntries() {
        PetLuaTable.MaterialLists lists = new PetLuaTable.MaterialLists();
        assertNull(lists.kept(list("COD", "SALMON")), "nothing kept yet");
        assertNull(lists.kept(LuaValue.NIL), "only tables are kept");
        Set<Material> parsed = new java.util.HashSet<>();
        Set<Material> kept = lists.keep(list("COD", "SALMON"), parsed);
        assertSame(kept, lists.kept(list("COD", "SALMON")), "another table with the same entries gives the kept set");
        assertThrows(UnsupportedOperationException.class, () -> kept.add(null), "the kept set is read only");
        assertNull(lists.kept(list("COD")), "a shorter list is another list");
        assertNull(lists.kept(list("COD", "TROPICAL_FISH")), "a changed entry is another list");
        assertNull(lists.kept(list("SALMON", "COD")), "entries are compared in order");
        // Four slots, reused in turn: a fifth list takes the oldest one's place.
        for (String other : new String[]{"A", "B", "C"}) lists.keep(list(other), new java.util.HashSet<>());
        assertSame(kept, lists.kept(list("COD", "SALMON")));
        lists.keep(list("D"), new java.util.HashSet<>());
        assertNull(lists.kept(list("COD", "SALMON")), "the oldest list gave up its slot");
        assertFalse(lists.kept(list("D")) == null);
        // The raw comparison the campfire's soul variants use.
        LuaValue[] entries = PetLuaTable.MaterialLists.entries(list("gold", "blue"));
        assertTrue(PetLuaTable.MaterialLists.same(entries, list("gold", "blue")));
        assertFalse(PetLuaTable.MaterialLists.same(entries, list("gold")));
        assertFalse(PetLuaTable.MaterialLists.same(entries, LuaValue.NIL));
        assertFalse(PetLuaTable.MaterialLists.same(null, LuaValue.NIL), "nothing read yet matches nothing");
        assertTrue(PetLuaTable.MaterialLists.same(PetLuaTable.MaterialLists.entries(LuaValue.NIL), new LuaTable()),
                "no list and an empty one both name nothing");
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

    /** A loaded or unloaded world holding water up to y 63 and air above it. */
    private static World sea(boolean loaded) {
        return stub(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "isChunkLoaded" -> loaded;
            case "getBlockAt" -> {
                int y = args.length == 1 ? ((Location) args[0]).getBlockY() : (Integer) args[1];
                Material type = y <= 63 ? Material.WATER : Material.AIR;
                yield stub(org.bukkit.block.Block.class, (block, call, ignored) ->
                        call.getName().equals("getType") ? type : plain(block, call, ignored));
            }
            default -> plain(proxy, method, args);
        });
    }

    @Test
    void aSwimmerRestingInWaterKeepsItsDepth() {
        World sea = sea(true);
        Location deep = new Location(sea, 0.5, 55.5, 0.5);
        for (int draw = 0; draw < 20; draw++) {
            Location spot = PetMotion.levelWaterPoint(deep, 2);
            assertEquals(55.5, spot.getY(), "a swimmer told to stay 8 blocks down bobs at that depth");
            assertTrue(spot.distance(deep) <= 2);
        }
        assertEquals(deep, PetMotion.levelWaterPoint(deep, 0));
        assertNull(PetMotion.levelWaterPoint(new Location(sea, 0.5, 70, 0.5), 2), "out of the water: the usual spot");
        assertNull(PetMotion.levelWaterPoint(new Location(sea(false), 0.5, 55.5, 0.5), 2), "an unloaded chunk");
    }

    @Test
    void aCatchUpLandsAtLeastABlockNearerTheOwner() {
        assertTrue(PetMotion.closesIn(2.5, 30), "a far pet lands beside its owner");
        assertTrue(PetMotion.closesIn(4, 5));
        assertFalse(PetMotion.closesIn(4.5, 5), "half a block nearer is not worth a jump");
        assertFalse(PetMotion.closesIn(6, 5), "a catch-up never lands farther away");
        assertFalse(PetMotion.closesIn(0.5, 1.2), "a pet already beside its owner stays");
    }

    @Test
    void aWalkingBodyTakesSlowSpeedsAsGivenAndStandsStillAtZero() {
        Walker walker = new Walker();
        PetLuaTable.WalkPath path = new PetLuaTable.WalkPath();
        Location target = new Location(world, 10, 64, 0);
        double[] cruises = {0.25, 0.15, 0.1, 0.03, 0.001};
        for (double cruise : cruises) {
            path.forget();
            assertTrue(path.steer(walker.context(), target, cruise));
        }
        assertEquals(List.of(1D, 0.6D, 0.4D, 0.25D, 0.25D), walker.speeds,
                "4 x cruise multiplies the body's movement speed, never under a quarter of it");

        assertFalse(path.steer(walker.context(), target, 0D), "a cruise of 0 does not go there");
        assertFalse(path.steer(walker.context(), target, -1D));
        assertEquals(5, walker.requests.size(), "a cruise of 0 asks for no path");
        assertEquals(2, walker.stops, "a cruise of 0 stops the body");
        assertTrue(path.steer(walker.context(), target, 0.15D));
        assertEquals(6, walker.requests.size(), "moving again asks for a fresh path");
    }

    /** Stone up to y 63 with air above it, and when walled a stone block at x 1, y 64, z 0. */
    private static World ground(boolean walled) {
        World world = world("ground");
        return stub(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "getBlockAt" -> {
                int x = (Integer) args[0], y = (Integer) args[1], z = (Integer) args[2];
                Material type = y <= 63 || walled && x == 1 && y == 64 && z == 0 ? Material.STONE : Material.AIR;
                yield stub(org.bukkit.block.Block.class, (block, call, ignored) -> switch (call.getName()) {
                    case "getType" -> type;
                    case "isPassable" -> type == Material.AIR;
                    default -> plain(block, call, ignored);
                });
            }
            default -> method.invoke(world, args);
        });
    }

    /** Stone up to the y this gives for each x, the same along z, with air above it. */
    private static World terrain(java.util.function.IntUnaryOperator top) {
        World world = world("terrain");
        return stub(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "getBlockAt" -> {
                Material type = (Integer) args[1] <= top.applyAsInt((Integer) args[0]) ? Material.STONE : Material.AIR;
                yield stub(org.bukkit.block.Block.class, (block, call, ignored) -> switch (call.getName()) {
                    case "getType" -> type;
                    case "isPassable" -> type == Material.AIR;
                    default -> plain(block, call, ignored);
                });
            }
            default -> method.invoke(world, args);
        });
    }

    /** The calls steer_onto's walk makes for a body standing at x 0.5, y 64, z 0.5, as "name x speed". */
    private static List<String> steerOnto(World world, boolean inWater, Location target, double cruise) {
        return walk(world, inWater, (path, context) -> path.steerOnto(context, target, cruise));
    }

    /** The calls steer_onto's walk makes for a body standing at this place, out of water, as "name x speed". */
    private static List<String> steerOntoFrom(Location body, Location target, double cruise) {
        return walk(body, false, (path, context) -> path.steerOnto(context, target, cruise));
    }

    /** The calls a walk makes for a body standing at x 0.5, y 64, z 0.5, as "name x speed". */
    private static List<String> walk(World world, boolean inWater,
                                     java.util.function.BiConsumer<PetLuaTable.WalkPath, MindContext> steer) {
        return walk(new Location(world, 0.5, 64, 0.5), inWater, steer);
    }

    /** The calls a walk makes for a body standing at this place, as "name x speed". */
    private static List<String> walk(Location standing, boolean inWater,
                                     java.util.function.BiConsumer<PetLuaTable.WalkPath, MindContext> steer) {
        List<String> calls = new ArrayList<>();
        MindActuator actuator = stub(MindActuator.class, (proxy, method, args) -> switch (method.getName()) {
            case "moveTo", "moveDirect" -> {
                calls.add(String.format("%s %.2f %.2f", method.getName(), ((Location) args[0]).getX(), (Double) args[1]));
                yield true;
            }
            case "stopMoving" -> {
                calls.add("stopMoving");
                yield null;
            }
            default -> plain(proxy, method, args);
        });
        MindPerception perception = stub(MindPerception.class, (proxy, method, args) ->
                method.getName().equals("navigation") ? MindNavigationStatus.idle() : plain(proxy, method, args));
        LivingEntity body = stub(LivingEntity.class, (proxy, method, args) -> switch (method.getName()) {
            case "getLocation" -> standing.clone();
            case "isInWater" -> inWater;
            default -> plain(proxy, method, args);
        });
        steer.accept(new PetLuaTable.WalkPath(), new MindContext(body, 0L, 1L,
                stub(MindMemoryView.class, PetLuaTableTest::plain), perception, actuator));
        return calls;
    }

    /** The calls a walk at a creature makes, whose feet are at this point and whose middle is 0.9 above them. */
    private static List<String> steerAt(World world, boolean inWater, Location feet, double cruise) {
        return walk(world, inWater, (path, context) ->
                path.steerAt(context, feet.clone().add(0D, 0.9D, 0D), feet, cruise));
    }

    @Test
    void aWalkAtACreatureWalksTheLastStretchStraightAtItsFeet() {
        World ground = ground(false);
        assertEquals(List.of("moveTo 5.50 0.60"), steerAt(ground, false, new Location(ground, 5.5, 64, 0.5), 0.15D),
                "far off it paths at the creature's middle, as steer_to does");
        assertEquals(List.of("moveDirect 2.00 0.60"), steerAt(ground, false, new Location(ground, 2, 64, 0.5), 0.15D),
                "where a path would end about a block short, it walks straight at the creature's feet");
        assertEquals(List.of("moveDirect 0.90 1.20"), steerAt(ground, false, new Location(ground, 0.9, 64, 0.5), 0.3D),
                "at its full speed to the end: the caller decides when it is near enough");
        assertEquals(List.of("moveTo 1.70 0.60"), steerAt(ground, false, new Location(ground, 1.7, 65, 0.5), 0.15D),
                "a creature a block up is a climb for the path");
        assertEquals(List.of("moveTo 1.70 0.60"), steerAt(ground, true, new Location(ground, 1.7, 64, 0.5), 0.15D),
                "a body in water keeps to its path");
        World walled = ground(true);
        assertEquals(List.of("moveTo 1.70 0.60"), steerAt(walled, false, new Location(walled, 1.7, 64, 0.5), 0.15D),
                "a block in the way is for the path to go round");
        assertEquals(List.of("stopMoving"), steerAt(ground, false, new Location(ground, 1.7, 64, 0.5), 0D),
                "a cruise of 0 stops it");
    }

    @Test
    void steerOntoWalksTheLastStretchStraightOntoItsPoint() {
        World ground = ground(false);
        assertEquals(List.of("moveTo 5.50 0.60"), steerOnto(ground, false, new Location(ground, 5.5, 64, 0.5), 0.15D),
                "far off it paths, as steer_to does");
        assertEquals(List.of("moveDirect 2.00 0.60"), steerOnto(ground, false, new Location(ground, 2, 64, 0.5), 0.15D),
                "where a path would end about a block short, it walks straight at the point");
        assertEquals(List.of("moveDirect 0.90 0.48"), steerOnto(ground, false, new Location(ground, 0.9, 64, 0.5), 0.3D),
                "easing off over the last block");
        assertEquals(List.of("moveDirect 0.60 0.36"), steerOnto(ground, false, new Location(ground, 0.6, 64, 0.62), 0.3D),
                "never under 0.3 of its speed");
        assertEquals(List.of("moveDirect 0.60 0.25"), steerOnto(ground, false, new Location(ground, 0.6, 64, 0.62), 0.15D),
                "nor under the slowest walk");
        assertEquals(List.of("stopMoving"), steerOnto(ground, false, new Location(ground, 0.55, 64, 0.5), 0.15D),
                "on the point it stands still");
        assertEquals(List.of("moveTo 1.70 0.60"), steerOnto(ground, false, new Location(ground, 1.7, 65, 0.5), 0.15D),
                "a point a block up is a climb for the path");
        assertEquals(List.of("moveTo 1.70 0.60"), steerOnto(ground, true, new Location(ground, 1.7, 64, 0.5), 0.15D),
                "a body in water keeps to its path");
        World walled = ground(true);
        assertEquals(List.of("moveTo 1.70 0.60"), steerOnto(walled, false, new Location(walled, 1.7, 64, 0.5), 0.15D),
                "a block in the way is for the path to go round");
        assertEquals(List.of("stopMoving"), steerOnto(ground, false, new Location(ground, 1.7, 64, 0.5), 0D),
                "a cruise of 0 stops it");
    }

    @Test
    void steerOntoWalksStraightOverASingleStep() {
        // The body stands at x 0.5, y 64 on stone whose top is y 63; from x 1 on the ground is a block lower.
        World down = terrain(x -> x >= 1 ? 62 : 63);
        assertEquals(List.of("moveDirect 1.40 0.54"), steerOnto(down, false, new Location(down, 1.4, 63, 0.5), 0.15D),
                "a point down a step is walked at straight, off the edge, with no path");
        assertEquals(List.of("moveDirect 2.40 0.60"), steerOnto(down, false, new Location(down, 2.4, 63, 0.5), 0.15D),
                "from as far as a point on its own level");
        assertEquals(List.of("moveTo 2.60 0.60"), steerOnto(down, false, new Location(down, 2.6, 63, 0.5), 0.15D),
                "farther off it paths");
        assertEquals(List.of("moveDirect 1.90 0.51"),
                steerOntoFrom(new Location(down, 1.05, 64, 0.5), new Location(down, 1.9, 63, 0.5), 0.15D),
                "a body on the edge of the step, its middle over the lower ground already, walks on off it");
        assertEquals(List.of("moveTo 1.40 0.60"), steerOnto(down, true, new Location(down, 1.4, 63, 0.5), 0.15D),
                "a body in water keeps to its path");

        World up = terrain(x -> x >= 1 ? 64 : 63);
        assertEquals(List.of("moveDirect 1.40 0.54"), steerOnto(up, false, new Location(up, 1.4, 65, 0.5), 0.15D),
                "a point up a step, within a block, is walked at straight: there a body steps or jumps up");
        assertEquals(List.of("moveTo 1.80 0.60"), steerOnto(up, false, new Location(up, 1.8, 65, 0.5), 0.15D),
                "from farther off a climb is for the path");

        // A ditch one block wide and one deep across the way at x 1.
        World ditch = terrain(x -> x == 1 ? 62 : 63);
        assertEquals(List.of("moveDirect 1.40 0.54"), steerOnto(ditch, false, new Location(ditch, 1.4, 63, 0.5), 0.15D),
                "into a ditch");
        assertEquals(List.of("moveDirect 2.20 0.42"),
                steerOntoFrom(new Location(ditch, 1.5, 63, 0.5), new Location(ditch, 2.2, 64, 0.5), 0.15D),
                "and out of it on the far side");

        World cliff = terrain(x -> x >= 1 ? 61 : 63);
        assertEquals(List.of("moveTo 1.40 0.60"), steerOnto(cliff, false, new Location(cliff, 1.4, 62, 0.5), 0.15D),
                "a drop of two is for the path");
        World wall = terrain(x -> x >= 1 ? 65 : 63);
        assertEquals(List.of("moveTo 1.40 0.60"), steerOnto(wall, false, new Location(wall, 1.4, 66, 0.5), 0.15D),
                "and so is a wall of two");
        World flat = ground(false);
        assertEquals(List.of("moveTo 1.40 0.60"), steerOnto(flat, false, new Location(flat, 1.4, 65, 0.5), 0.15D),
                "a point in the air over level ground is not a step");
    }

    @Test
    void aStraightWalkDoesNotLookAtTheGroundBetween() {
        // From x 1 on the ground is two blocks lower: a drop steer_onto leaves to the path.
        World cliff = terrain(x -> x >= 1 ? 61 : 63);
        Location point = new Location(cliff, 1.4, 64, 0.5);
        assertEquals(List.of("moveTo 1.40 0.60"), steerOnto(cliff, false, point, 0.15D),
                "steer_onto looks at the ground and paths");
        assertEquals(List.of("moveDirect 1.40 0.54"),
                walk(cliff, false, (path, context) -> path.walkStraight(context, point, 0.15D)),
                "a straight walk goes at its point, easing off as steer_onto does");
        Location far = new Location(cliff, 2.6, 64, 0.5);
        assertEquals(List.of("moveTo 2.60 0.60"),
                walk(cliff, false, (path, context) -> path.walkStraight(context, far, 0.15D)),
                "past steer_onto's reach it paths");
        assertEquals(List.of("moveTo 1.40 0.60"),
                walk(cliff, true, (path, context) -> path.walkStraight(context, point, 0.15D)),
                "a body in water keeps to its path");
        assertEquals(List.of("stopMoving"),
                walk(cliff, false, (path, context) -> path.walkStraight(context, point, 0D)), "a cruise of 0 stops it");
    }

    @Test
    void steerOntoStopsOnItsPointOnce() {
        World ground = ground(false);
        List<String> calls = new ArrayList<>();
        MindActuator actuator = stub(MindActuator.class, (proxy, method, args) -> {
            calls.add(method.getName());
            return plain(proxy, method, args);
        });
        LivingEntity body = stub(LivingEntity.class, (proxy, method, args) -> method.getName().equals("getLocation")
                ? new Location(ground, 0.5, 64, 0.5) : plain(proxy, method, args));
        MindContext context = new MindContext(body, 0L, 1L, stub(MindMemoryView.class, PetLuaTableTest::plain),
                stub(MindPerception.class, PetLuaTableTest::plain), actuator);
        PetLuaTable.WalkPath path = new PetLuaTable.WalkPath();
        Location spot = new Location(ground, 0.6, 64, 0.5);
        assertTrue(path.steerOnto(context, spot, 0.15D));
        assertTrue(path.stoppedNow());
        assertTrue(path.steerOnto(context, spot, 0.15D));
        assertFalse(path.stoppedNow(), "standing on the same point it does not stop again");
        assertEquals(List.of("stopMoving"), calls);
        assertTrue(path.steerOnto(context, new Location(ground, 0.45, 64, 0.5), 0.15D));
        assertEquals(List.of("stopMoving", "stopMoving"), calls, "a new point under it stops it again");
    }
}
