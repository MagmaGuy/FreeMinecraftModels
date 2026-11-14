package com.magmaguy.freeminecraftmodels.mobbehaviors;

import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindControl;
import com.magmaguy.magmacore.ai.MindMemoryStore;
import com.magmaguy.magmacore.ai.MindNavigationStatus;
import com.magmaguy.magmacore.ai.MindPerception;
import com.magmaguy.magmacore.ai.MindPosition;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindStopReason;
import com.magmaguy.magmacore.scripting.LuaMindDefinition;
import com.magmaguy.magmacore.scripting.LuaMindModuleDescriptor;
import com.magmaguy.magmacore.scripting.LuaMindModuleRegistry;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import com.magmaguy.magmacore.shaded.luaj.vm2.LuaTable;
import com.magmaguy.magmacore.shaded.luaj.vm2.LuaValue;
import com.magmaguy.magmacore.shaded.luaj.vm2.Varargs;
import com.magmaguy.magmacore.shaded.luaj.vm2.lib.VarArgFunction;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mob behaviors shipped in the jar, loaded as MagmaCore loads them and run as a Mind would run them, against a
 * stand-in for {@code c.overlay} and an actuator that writes down what it is asked.
 */
class ShippedMobBehaviorsTest {
    private static final Map<UUID, LivingEntity> ENTITIES = new HashMap<>();
    private static Server previousServer;

    @BeforeAll
    static void setUp() throws ReflectiveOperationException {
        Field server = serverField();
        previousServer = (Server) server.get(null);
        server.set(null, stub(Server.class, (proxy, method, args) -> switch (method.getName()) {
            case "getEntity" -> ENTITIES.get((UUID) args[0]);
            case "getLogger" -> Logger.getLogger("FMM-shipped-mob-behaviors-test");
            default -> plain(proxy, method, args);
        }));
    }

    @AfterAll
    static void tearDown() throws ReflectiveOperationException {
        serverField().set(null, previousServer);
        previousServer = null;
        ENTITIES.clear();
    }

    @Test
    void theJarShipsTheListedBehaviorsEachUnderTheIdOfItsFile() throws Exception {
        Path folder = Path.of(ShippedMobBehaviorsTest.class.getResource("/mob_behaviors").toURI());
        Set<String> shipped = new TreeSet<>();
        try (Stream<Path> files = Files.list(folder)) {
            files.forEach(file -> shipped.add(file.getFileName().toString()));
        }
        Set<String> listed = new TreeSet<>();
        for (String name : MobBehaviors.DEFAULTS) listed.add(name + ".lua");
        assertEquals(listed, shipped);
        for (String name : MobBehaviors.DEFAULTS) {
            assertEquals("fmm:mob/" + name,
                    LuaMindModuleRegistry.inspectModule(name + ".lua", source(name)).identifier());
        }
    }

    @Test
    void allTheShippedBehaviorsRunTogetherAndTheFightsComeFirst() {
        Mob mob = new Mob(EntityType.ZOMBIE, MobBehaviors.DEFAULTS.toArray(String[]::new));
        assertEquals(List.of("fmm:mob/coward_flee", "fmm:mob/guardian_fight", "fmm:mob/hostile_hunt",
                        "fmm:mob/guardian_follow", "fmm:mob/wander"),
                mob.program.behaviors().stream().map(MindBehavior::identifier).toList());
        assertTrue(mob.behavior("guardian_fight").controls().contains(MindControl.ATTACK));
        assertEquals(Set.of(MindControl.MOVE), mob.behavior("wander").controls());
    }

    @Test
    void aGuardianChasesWhoeverHurtItsOwnerAndHitsThemUpClose() {
        Mob mob = new Mob(EntityType.ZOMBIE, "guardian");
        LivingEntity raider = entity(EntityType.PILLAGER, 10, 64, 0);
        MindBehavior fight = mob.behavior("guardian_fight");
        assertFalse(fight.canStart(mob.at(1)));

        mob.overlay.set("owner_attacker", LuaLivingEntityTable.build(raider));
        assertTrue(fight.canStart(mob.at(2)));
        fight.start(mob.at(2));
        fight.tick(mob.at(2));
        assertEquals(List.of("look_at 10.0 65.0 0.0", "shoot " + raider.getUniqueId(), "move_to 10.0 64.0 0.0 x1.3"),
                mob.asked());

        // Paths are planned every 10 ticks, the look is kept every tick, and a kind that cannot shoot is not asked
        // to again.
        fight.tick(mob.at(3));
        assertEquals(List.of("look_at 10.0 65.0 0.0"), mob.asked());

        move(raider, 1.5, 64, 0);
        fight.tick(mob.at(4));
        assertEquals(List.of("look_at 1.5 65.0 0.0", "stop_moving", "attack " + raider.getUniqueId()), mob.asked());
        // Its next hit waits out attack_ticks.
        fight.tick(mob.at(5));
        assertEquals(List.of("look_at 1.5 65.0 0.0", "stop_moving"), mob.asked());
        fight.tick(mob.at(24));
        assertEquals(List.of("look_at 1.5 65.0 0.0", "stop_moving", "attack " + raider.getUniqueId()), mob.asked());

        mob.overlay.set("owner_attacker", LuaValue.NIL);
        assertFalse(fight.canContinue(mob.at(25)));
        fight.stop(mob.at(25), MindStopReason.COMPLETED);
        assertEquals(List.of("stop_moving"), mob.asked());
    }

    @Test
    void aGuardianCreeperLightsItsFuseUpCloseAndHoldsItUntilTheTargetEscapes() {
        Mob mob = new Mob(EntityType.CREEPER, "guardian");
        LivingEntity raider = entity(EntityType.PILLAGER, 10, 64, 0);
        mob.overlay.set("owner_attacker", LuaLivingEntityTable.build(raider));
        MindBehavior fight = mob.behavior("guardian_fight");
        fight.start(mob.at(1));
        fight.tick(mob.at(1));
        assertEquals(List.of("look_at 10.0 65.0 0.0", "move_to 10.0 64.0 0.0 x1.3"), mob.asked());

        move(raider, 2.5, 64, 0);
        fight.tick(mob.at(2));
        assertEquals(List.of("look_at 2.5 65.0 0.0", "stop_moving", "attack " + raider.getUniqueId()), mob.asked());
        // Out of the fuse distance but inside the escape distance, it keeps swelling.
        move(raider, 5, 64, 0);
        fight.tick(mob.at(3));
        assertEquals(List.of("look_at 5.0 65.0 0.0", "stop_moving", "attack " + raider.getUniqueId()), mob.asked());
        // Past the escape distance the fuse is out and it gives chase again.
        move(raider, 8, 64, 0);
        fight.tick(mob.at(11));
        assertEquals(List.of("look_at 8.0 65.0 0.0", "move_to 8.0 64.0 0.0 x1.3"), mob.asked());
        // Behind a wall it does not light it either.
        move(raider, 2, 64, 0);
        mob.sees = false;
        fight.tick(mob.at(12));
        assertEquals(List.of("look_at 2.0 65.0 0.0"), mob.asked());
    }

    @Test
    void aGuardianThatShootsStandsOffAndShootsAgainOnlyWhenReady() {
        Mob mob = new Mob(EntityType.SKELETON, "guardian");
        mob.shoots = true;
        LivingEntity raider = entity(EntityType.PILLAGER, 8, 64, 0);
        mob.overlay.set("owner_attacker", LuaLivingEntityTable.build(raider));
        MindBehavior fight = mob.behavior("guardian_fight");
        fight.start(mob.at(1));
        fight.tick(mob.at(1));
        assertEquals(List.of("look_at 8.0 65.0 0.0", "shoot " + raider.getUniqueId(), "stop_moving"), mob.asked());
        fight.tick(mob.at(2));
        assertEquals(List.of("look_at 8.0 65.0 0.0", "stop_moving"), mob.asked());
        fight.tick(mob.at(21));
        assertEquals(List.of("look_at 8.0 65.0 0.0", "shoot " + raider.getUniqueId(), "stop_moving"), mob.asked());
        // Out of sight it closes in rather than standing off.
        mob.sees = false;
        fight.tick(mob.at(22));
        assertEquals(List.of("look_at 8.0 65.0 0.0", "move_to 8.0 64.0 0.0 x1.3"), mob.asked());
    }

    @Test
    void aGuardianComesAfterItsOwnerAndStopsBesideThem() {
        Mob mob = new Mob(EntityType.ZOMBIE, "guardian");
        Player owner = (Player) entity(Player.class, EntityType.PLAYER, 5, 64, 0);
        mob.overlay.set("owner", LuaLivingEntityTable.build(owner));
        MindBehavior follow = mob.behavior("guardian_follow");
        assertFalse(follow.canStart(mob.at(1)));

        move(owner, 9, 64, 0);
        assertTrue(follow.canStart(mob.at(2)));
        follow.start(mob.at(2));
        follow.tick(mob.at(2));
        assertEquals(List.of("move_to 9.0 64.0 0.0 x1.2", "look_at 9.0 65.0 0.0"), mob.asked());

        move(owner, 2.5, 64, 0);
        assertFalse(follow.canContinue(mob.at(3)));
        // Without an owner it has nobody to follow.
        mob.overlay.set("owner", LuaValue.NIL);
        assertFalse(follow.canStart(mob.at(20)));
    }

    @Test
    void aHostileMobHuntsTheNearestTargetAndDealsItsDamageSetting() {
        Mob mob = new Mob(EntityType.CHICKEN, "hostile");
        LivingEntity player = entity(Player.class, EntityType.PLAYER, 6, 64, 0);
        MindBehavior hunt = mob.behavior("hostile_hunt");
        assertFalse(hunt.canStart(mob.at(1)));
        assertEquals(List.of("nearest 16.0 player"), mob.asked());

        mob.nearest = player;
        // It looked a tick ago, so it looks again only once LOOK_TICKS have passed.
        assertFalse(hunt.canStart(mob.at(2)));
        assertTrue(hunt.canStart(mob.at(11)));
        hunt.start(mob.at(11));
        hunt.tick(mob.at(11));
        assertEquals(List.of("nearest 16.0 player", "look_at 6.0 65.0 0.0", "shoot " + player.getUniqueId(),
                "move_to 6.0 64.0 0.0 x1.3"), mob.asked());

        mob.settings("hostile").set("damage", 4);
        move(player, 1, 64, 0);
        hunt.tick(mob.at(12));
        assertEquals(List.of("look_at 1.0 65.0 0.0", "stop_moving", "attack " + player.getUniqueId() + " for 4.0"),
                mob.asked());

        // The target gone, it gives up at its next look around.
        ENTITIES.remove(player.getUniqueId());
        mob.nearest = null;
        assertFalse(hunt.canContinue(mob.at(13)));
    }

    @Test
    void aHostileMobTurnsOnWhoeverHurtsItBeforeAnyoneElse() {
        Mob mob = new Mob(EntityType.COW, "hostile");
        LivingEntity far = entity(Player.class, EntityType.PLAYER, 12, 64, 0);
        LivingEntity wolf = entity(EntityType.WOLF, 0, 64, 4);
        mob.nearest = far;
        mob.overlay.set("attacker", LuaLivingEntityTable.build(wolf));
        MindBehavior hunt = mob.behavior("hostile_hunt");
        assertTrue(hunt.canStart(mob.at(1)));
        hunt.start(mob.at(1));
        hunt.tick(mob.at(1));
        assertEquals(List.of("look_at 0.0 65.0 4.0", "shoot " + wolf.getUniqueId(), "move_to 0.0 64.0 4.0 x1.3"),
                mob.asked());
    }

    @Test
    void aCowardRunsFromWhatItFearsUntilItIsSafe() {
        Mob mob = new Mob(EntityType.PILLAGER, "coward");
        LivingEntity player = entity(Player.class, EntityType.PLAYER, 6, 64, 0);
        MindBehavior flee = mob.behavior("coward_flee");
        assertFalse(flee.canStart(mob.at(1)));
        assertEquals(List.of("nearest 10.0 player"), mob.asked());

        mob.nearest = player;
        assertTrue(flee.canStart(mob.at(6)));
        flee.tick(mob.at(6));
        assertEquals(List.of("nearest 10.0 player", "nearest 16.0 player", "flee 6.0 64.0 0.0 x1.5"), mob.asked());

        mob.nearest = null;
        assertFalse(flee.canContinue(mob.at(11)));
        flee.stop(mob.at(11), MindStopReason.COMPLETED);
        assertEquals(List.of("nearest 16.0 player", "stop_moving"), mob.asked());
    }

    @Test
    void aCowardRunsFromWhoeverHurtItWhateverItFears() {
        Mob mob = new Mob(EntityType.ZOMBIE, "coward");
        LivingEntity wolf = entity(EntityType.WOLF, 3, 64, 0);
        mob.overlay.set("attacker", LuaLivingEntityTable.build(wolf));
        MindBehavior flee = mob.behavior("coward_flee");
        assertTrue(flee.canStart(mob.at(1)));
        flee.tick(mob.at(1));
        assertEquals(List.of("flee 3.0 64.0 0.0 x1.5"), mob.asked());
    }

    @Test
    void aCowardWithNoWayOutBacksOffStraightAwayFromTheScare() {
        Mob mob = new Mob(EntityType.PILLAGER, "coward");
        mob.hemmedIn = true;
        mob.nearest = entity(Player.class, EntityType.PLAYER, 0, 64, 4);
        MindBehavior flee = mob.behavior("coward_flee");
        flee.tick(mob.at(1));
        assertEquals(List.of("nearest 16.0 player", "flee 0.0 64.0 4.0 x1.5", "move_to 0.0 64.0 -6.0 x1.5"),
                mob.asked());
        // Standing on the scare it still picks a way.
        move(mob.nearest, 0, 64, 0);
        flee.tick(mob.at(6));
        assertEquals(List.of("nearest 16.0 player", "flee 0.0 64.0 0.0 x1.5", "move_to 6.0 64.0 0.0 x1.5"),
                mob.asked());
    }

    @Test
    void aWandererStrollsWithinItsRadiusThenRests() {
        Mob mob = new Mob(EntityType.COW, "wander");
        MindBehavior wander = mob.behavior("wander");
        assertTrue(wander.canStart(mob.at(1)));
        wander.start(mob.at(1));
        List<String> asked = mob.asked();
        assertEquals(1, asked.size());
        String[] stroll = asked.get(0).split(" ");
        assertEquals("move_to", stroll[0]);
        double reach = Math.hypot(Double.parseDouble(stroll[1]), Double.parseDouble(stroll[3]));
        assertTrue(reach >= 8 * 0.4 - 1e-9 && reach <= 8 + 1e-9, asked.get(0));
        assertEquals("64.0", stroll[2]);

        mob.walking = true;
        assertTrue(wander.canContinue(mob.at(11)));
        mob.walking = false;
        assertFalse(wander.canContinue(mob.at(21)));
        wander.stop(mob.at(21), MindStopReason.COMPLETED);
        assertEquals(List.of("stop_moving"), mob.asked());
        // It rests at least rest_ticks before the next stroll.
        assertFalse(wander.canStart(mob.at(21 + 59)));
        assertTrue(wander.canStart(mob.at(21 + 60 + 120)));
    }

    /** One mob running the named behaviors: its body at the origin, its program, memory, and what it was asked. */
    private static final class Mob implements MindActuator, MindPerception {
        private final LivingEntity body;
        private final MindProgram program;
        private final MindMemoryStore memory;
        private final LuaTable overlay = new LuaTable();
        private final Map<String, LuaTable> settings = new HashMap<>();
        private final List<String> asked = new ArrayList<>();
        private LivingEntity nearest;
        private boolean sees = true;
        private boolean shoots;
        private boolean walking;
        // No way out ten blocks long: the actuator's flee finds no path.
        private boolean hemmedIn;

        private Mob(EntityType type, String... behaviors) {
            body = entity(type, 0, 64, 0);
            LuaMindModuleRegistry registry = new LuaMindModuleRegistry();
            LuaMindModuleRegistry.OwnerScope owner = registry.openOwner("fmm");
            List<String> ids = new ArrayList<>();
            for (String name : behaviors) {
                LuaMindModuleDescriptor module = owner.registerModule(name + ".lua", source(name));
                ids.add("'" + module.identifier() + "'");
                LuaTable values = new LuaTable();
                module.settings().forEach((key, value) -> values.set(key,
                        value instanceof Double number ? LuaValue.valueOf(number)
                                : value instanceof Boolean flag ? LuaValue.valueOf(flag)
                                : LuaValue.valueOf(String.valueOf(value))));
                settings.put(module.identifier(), values);
            }
            LuaMindDefinition definition = owner.validateProgram("test", "return ai.program { id = 'fmm:mob/test',"
                    + " revision = 1, modules = { " + String.join(", ", ids) + " } }");
            overlay.set("nearest", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs args) {
                    asked.add("nearest " + args.checkdouble(2) + " " + args.checkjstring(3));
                    return nearest == null ? LuaValue.NIL : LuaLivingEntityTable.build(nearest);
                }
            });
            overlay.set("entity", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs args) {
                    LivingEntity found = ENTITIES.get(UUID.fromString(args.checkjstring(2)));
                    return found == null ? LuaValue.NIL : LuaLivingEntityTable.build(found);
                }
            });
            program = definition.instantiate((table, context, contribution) -> {
                table.rawset("overlay", overlay);
                LuaTable own = settings.get(contribution);
                if (own != null) table.rawset("settings", own);
            });
            memory = new MindMemoryStore(program.schema());
        }

        private MindBehavior behavior(String name) {
            return program.behaviors().stream()
                    .filter(behavior -> behavior.identifier().equals("fmm:mob/" + name))
                    .findFirst().orElseThrow();
        }

        private LuaTable settings(String name) {
            return settings.get("fmm:mob/" + name);
        }

        private MindContext at(long tick) {
            return new MindContext(body, tick, 1L, memory.view(tick), this, this);
        }

        /** What the behaviors asked for since the last call, in order. */
        private List<String> asked() {
            List<String> since = List.copyOf(asked);
            asked.clear();
            return since;
        }

        private static String point(Location location) {
            return location.getX() + " " + location.getY() + " " + location.getZ();
        }

        @Override
        public boolean moveTo(Location destination, double speedModifier) {
            asked.add("move_to " + point(destination) + " x" + speedModifier);
            return true;
        }

        @Override
        public boolean moveAwayFrom(Location threat, double speedModifier) {
            asked.add("flee " + point(threat) + " x" + speedModifier);
            return !hemmedIn;
        }

        @Override
        public void stopMoving() {
            asked.add("stop_moving");
        }

        @Override
        public void lookAt(Location destination) {
            asked.add("look_at " + point(destination));
        }

        @Override
        public void jump() {
            asked.add("jump");
        }

        @Override
        public void setTarget(LivingEntity target) {
            asked.add("set_target " + target.getUniqueId());
        }

        @Override
        public void clearTarget() {
            asked.add("clear_target");
        }

        @Override
        public void attack(LivingEntity target) {
            asked.add("attack " + target.getUniqueId());
        }

        @Override
        public void attack(LivingEntity target, double damage) {
            asked.add("attack " + target.getUniqueId() + " for " + damage);
        }

        @Override
        public boolean rangedAttack(LivingEntity target) {
            asked.add("shoot " + target.getUniqueId());
            return shoots;
        }

        @Override
        public void stopAll() {
            asked.add("stop_all");
        }

        @Override
        public Optional<LivingEntity> currentTarget() {
            return Optional.empty();
        }

        @Override
        public Optional<Player> nearestPlayer(double maxDistance) {
            return Optional.empty();
        }

        @Override
        public boolean lineOfSight(LivingEntity target) {
            return sees;
        }

        @Override
        public MindNavigationStatus navigation() {
            return walking
                    ? new MindNavigationStatus(Optional.of(new MindPosition("world", 1, 64, 1)), true,
                    Optional.empty(), 0)
                    : MindNavigationStatus.idle();
        }
    }

    private static final Map<LivingEntity, Location> PLACES = new HashMap<>();

    private static LivingEntity entity(EntityType type, double x, double y, double z) {
        return entity(LivingEntity.class, type, x, y, z);
    }

    /** A living entity that knows its id, its type and where it stands, and is known to the server by its id. */
    private static LivingEntity entity(Class<? extends LivingEntity> kind, EntityType type, double x, double y, double z) {
        UUID id = UUID.randomUUID();
        LivingEntity[] self = new LivingEntity[1];
        self[0] = stub(kind, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getType" -> type;
            case "getLocation" -> PLACES.get(self[0]).clone();
            case "isValid" -> true;
            case "isDead" -> false;
            case "getGameMode" -> org.bukkit.GameMode.SURVIVAL;
            default -> plain(proxy, method, args);
        });
        PLACES.put(self[0], new Location(null, x, y, z));
        ENTITIES.put(id, self[0]);
        return self[0];
    }

    private static void move(LivingEntity entity, double x, double y, double z) {
        PLACES.put(entity, new Location(null, x, y, z));
    }

    private static String source(String name) {
        try (InputStream in = ShippedMobBehaviorsTest.class.getResourceAsStream("/mob_behaviors/" + name + ".lua")) {
            if (in == null) throw new IllegalStateException("No resource for the mob behavior " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Bukkit's server singleton, which Bukkit.setServer will not replace once set. */
    private static Field serverField() throws NoSuchFieldException {
        Field server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        return server;
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /** Object's own methods, and nothing at all for the rest: false, zero or null. */
    private static Object plain(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "stub";
            default:
                break;
        }
        Class<?> returned = method.getReturnType();
        if (returned == boolean.class) return false;
        if (returned == void.class || !returned.isPrimitive()) return null;
        if (returned == long.class) return 0L;
        if (returned == double.class) return 0D;
        if (returned == float.class) return 0F;
        if (returned == char.class) return (char) 0;
        if (returned == byte.class) return (byte) 0;
        if (returned == short.class) return (short) 0;
        return 0;
    }
}
