package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.constants.OverridableWanderPriority;
import com.magmaguy.easyminecraftgoals.internal.*;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackCast;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackRequest;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackResolver;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponAPI;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Addon attack behaviors against the production registry, cast owner and damage path. */
class MagicAttackBehaviorTest {
    private static final String STAFF_ID = "ray_staff";

    private final Map<String, MetadataValue> metadata = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<FakeTask> scheduled = new ArrayList<>();
    private final AtomicBoolean addonEnabled = new AtomicBoolean(true);
    private Field adapterField;
    private Object previousAdapter;
    private Field serverField;
    private Object previousServer;
    private Plugin addon;
    private Plugin otherAddon;
    private Player caster;
    private World world;
    private MagicAttackBehaviorRegistry registry;
    private MagicWeaponRuntime runtime;

    @BeforeEach
    void install() throws Exception {
        adapterField = NMSManager.class.getDeclaredField("adapter");
        adapterField.setAccessible(true);
        previousAdapter = adapterField.get(null);
        adapterField.set(null, new LegacyAdapter());
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousServer = serverField.get(null);
        serverField.set(null, proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
            case "isPrimaryThread" -> true;
            case "getLogger" -> java.util.logging.Logger.getLogger("MagicAttackBehaviorTest");
            default -> defaultValue(method.getReturnType());
        }));
        addon = plugin("RayAddon", addonEnabled);
        otherAddon = plugin("OtherAddon", new AtomicBoolean(true));
        world = proxy(World.class, (instance, method, args) -> identity(instance, method.getName(), args,
                defaultValue(method.getReturnType())));
        caster = proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
            case "getMetadata" -> metadata.containsKey(args[0]) ? List.of(metadata.get(args[0])) : List.of();
            case "setMetadata" -> { metadata.put((String) args[0], (MetadataValue) args[1]); yield null; }
            case "removeMetadata" -> { metadata.remove(args[0]); yield null; }
            case "getUniqueId" -> new UUID(0, 1);
            default -> identity(instance, method.getName(), args, defaultValue(method.getReturnType()));
        });
        registry = new MagicAttackBehaviorRegistry(warnings::add);
        runtime = new MagicWeaponRuntime(plugin("FreeMinecraftModels", new AtomicBoolean(true)),
                new MagicWeaponCatalog(List.of()), registry, (owner, delay, period, task) -> {
            FakeTask fake = new FakeTask(owner, period, task);
            scheduled.add(fake);
            return () -> fake.cancelled = true;
        });
    }

    @AfterEach
    void restoreGlobals() throws Exception {
        if (adapterField != null) adapterField.set(null, previousAdapter);
        if (serverField != null) serverField.set(null, previousServer);
    }

    @Test
    void behaviorsAreOwnerScopedAndBoundPerWeaponAttack() {
        NamespacedKey ray = key("rayaddon", "ray");
        MagicWeaponDefinition staff = staff(Map.of());

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(addon, key("someoneelse", "ray"), MagicAttackKind.STAFF_FIREBALL, cast -> true));
        assertFalse(registry.bind(addon, STAFF_ID, ray), "An unregistered id cannot be bound");
        assertTrue(registry.register(addon, ray, MagicAttackKind.STAFF_FIREBALL, cast -> true));
        assertTrue(registry.isRegistered(ray));
        assertNull(registry.resolve(staff, MagicAttackKind.STAFF_FIREBALL, true), "Registering alone binds nothing");

        assertFalse(registry.bind(otherAddon, STAFF_ID, ray), "Only the owner binds its behavior");
        assertTrue(registry.bind(addon, "freeminecraftmodels:Ray_Staff", ray));
        assertSame(ray, registry.resolve(staff, MagicAttackKind.STAFF_FIREBALL, true).id());
        assertNull(registry.resolve(staff, MagicAttackKind.STAFF_MELEE, true), "The other attack keeps its built-in");
        assertEquals(MagicWeaponKind.STAFF, staff.kind(), "The weapon keeps its family");

        NamespacedKey rival = key("otheraddon", "burst");
        assertTrue(registry.register(otherAddon, rival, MagicAttackKind.STAFF_FIREBALL, cast -> true));
        assertFalse(registry.bind(otherAddon, STAFF_ID, rival), "An enabled owner's binding is not taken over");

        registry.unbind(addon, STAFF_ID, MagicAttackKind.STAFF_FIREBALL);
        assertNull(registry.resolve(staff, MagicAttackKind.STAFF_FIREBALL, true));
        assertTrue(registry.bind(addon, STAFF_ID, ray));
        registry.unregister(otherAddon, ray);
        assertTrue(registry.isRegistered(ray), "Another plugin cannot unregister it");
        registry.unregister(addon, ray);
        assertFalse(registry.isRegistered(ray));
        assertNull(registry.resolve(staff, MagicAttackKind.STAFF_FIREBALL, true), "Unregistering drops its bindings");
        assertTrue(warnings.isEmpty());
        assertEquals(8, MagicWeaponAPI.capabilityVersion());
    }

    @Test
    void anItemFileNamingAnAbsentAddonFallsBackToTheBuiltInAttackWithOneWarning() throws Exception {
        MagicWeaponDefinition plain = parse("""
                type: STAFF
                """);
        assertTrue(plain.behaviors().isEmpty());
        assertEquals(BuiltInMagicWeapons.defaults(STAFF_ID, MagicWeaponKind.STAFF), plain,
                "A weapon that names no behavior parses exactly as before");

        MagicWeaponDefinition named = parse("""
                type: STAFF
                attacks:
                  STAFF_MELEE:
                    basePower: 0.45
                    reloadTicks: 12
                  STAFF_FIREBALL:
                    basePower: 1.25
                    reloadTicks: 40
                    behavior: rayaddon:ray
                """);
        NamespacedKey ray = key("rayaddon", "ray");
        assertEquals(Map.of(MagicAttackKind.STAFF_FIREBALL, ray), named.behaviors());

        assertNull(registry.resolve(named, MagicAttackKind.STAFF_FIREBALL, true));
        assertNull(registry.resolve(named, MagicAttackKind.STAFF_FIREBALL, true));
        assertNull(registry.resolve(named, MagicAttackKind.STAFF_FIREBALL, false));
        assertEquals(1, warnings.size(), "The missing addon is reported once");
        assertTrue(warnings.get(0).contains("rayaddon:ray") && warnings.get(0).contains(STAFF_ID));
        assertFalse(MagicWeaponInputRouter.route(MagicWeaponKind.STAFF, MagicInput.AIR_RIGHT_CLICK).isEmpty(),
                "The weapon still casts its built-in attack");

        // A behavior registered for the other attack does not fit the slot the file names.
        assertTrue(registry.register(addon, ray, MagicAttackKind.STAFF_MELEE, cast -> true));
        assertNull(registry.resolve(named, MagicAttackKind.STAFF_FIREBALL, true));
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(1).contains("STAFF_MELEE"));

        // The addon arrives after the content loaded: the same definition now resolves.
        assertTrue(registry.register(addon, ray, MagicAttackKind.STAFF_FIREBALL, cast -> true));
        assertSame(ray, registry.resolve(named, MagicAttackKind.STAFF_FIREBALL, true).id());
        assertEquals(2, warnings.size());

        assertThrows(IllegalArgumentException.class, () -> parse("""
                type: WAND
                attacks:
                  WAND_MISSILE:
                    basePower: 0.672
                    reloadTicks: 11
                    behavior: NoNamespace
                """));
    }

    @Test
    void anAcceptedCastRunsTheBoundBehaviorInPlaceOfTheBuiltInLaunch() {
        MagicWeaponDefinition staff = staff(Map.of());
        Target target = new Target();
        assertFalse(runtime.launch(cast(staff, MagicAttackKind.STAFF_MELEE, null), staff, null),
                "Without a behavior the built-in strike runs, and it needs an entity");

        NamespacedKey cone = key("rayaddon", "cone");
        AtomicReference<MagicAttackCast> received = new AtomicReference<>();
        AtomicBoolean castResult = new AtomicBoolean(true);
        assertTrue(registry.register(addon, cone, MagicAttackKind.STAFF_MELEE, handle -> {
            received.set(handle);
            return castResult.get();
        }));
        assertTrue(registry.bind(addon, STAFF_ID, cone));

        MagicCast bound = cast(staff, MagicAttackKind.STAFF_MELEE, null);
        assertTrue(runtime.launch(bound, staff, target.entity));
        assertEquals(0, target.hits, "The built-in strike did not run");
        MagicAttackCast handle = received.get();
        assertEquals(bound.attackId(), handle.attackId());
        assertEquals(cone, handle.behaviorId());
        assertSame(caster, handle.caster());
        assertSame(target.entity, handle.clickedTarget());
        assertEquals(STAFF_ID, handle.weaponId());
        assertEquals(MagicWeaponKind.STAFF, handle.weaponKind());
        assertEquals(MagicAttackKind.STAFF_MELEE, handle.attackKind());
        assertEquals(Material.BLAZE_ROD, handle.weapon().getType());
        assertEquals(staff.basePower(MagicAttackKind.STAFF_MELEE), handle.basePower());
        assertEquals(staff.reloadTicks(MagicAttackKind.STAFF_MELEE), handle.reloadTicks());
        assertEquals(staff.traits().range(), handle.range());
        assertFalse(handle.isActive(), "A cast with nothing pending ends when the behavior returns");

        // Empty space: no clicked entity, and the widened staff route only exists with a behavior.
        assertTrue(runtime.launch(cast(staff, MagicAttackKind.STAFF_MELEE, null), staff, null));
        assertNull(received.get().clickedTarget());
        assertEquals(MagicAttackKind.STAFF_MELEE,
                MagicWeaponInputRouter.route(MagicWeaponKind.STAFF, MagicInput.AIR_LEFT_CLICK, true).orElseThrow());
        assertTrue(MagicWeaponInputRouter.route(MagicWeaponKind.STAFF, MagicInput.AIR_LEFT_CLICK, false).isEmpty());
        assertTrue(MagicWeaponInputRouter.route(MagicWeaponKind.WAND, MagicInput.AIR_RIGHT_CLICK, true).isEmpty());

        castResult.set(false);
        assertFalse(runtime.launch(cast(staff, MagicAttackKind.STAFF_MELEE, null), staff, target.entity),
                "A declined cast spends no reload");
        assertEquals(0, runtime.behaviorCasts().activeCount());
    }

    @Test
    void everyReportedImpactReachesTheResolverOnceUnderOneCastIdentity() throws Exception {
        List<MagicAttackRequest> requests = new ArrayList<>();
        MagicAttackResolver resolver = (request, application) -> {
            requests.add(request);
            application.apply(10D * request.balance().impactScale());
        };
        installResolver(resolver);
        MagicWeaponDefinition staff = staff(Map.of());
        Target first = new Target();
        Target second = new Target();
        NamespacedKey chain = key("rayaddon", "chain");
        AtomicReference<MagicAttackCast> received = new AtomicReference<>();
        registry.register(addon, chain, MagicAttackKind.STAFF_FIREBALL, handle -> {
            received.set(handle);
            assertTrue(handle.isTargetEligible(first.entity));
            assertFalse(handle.isTargetEligible(caster), "The caster is never a target");
            // impact's return value reflects Bukkit's damage event, which no unit test fires.
            handle.impact(first.entity, 1D);
            handle.impact(second.entity, .5D);
            assertFalse(handle.impact(second.entity, 0D), "A non-positive factor is refused");
            handle.runLater(10L, () -> handle.impact(first.entity, .25D));
            return true;
        });
        registry.bind(addon, STAFF_ID, chain);
        MagicCast cast = cast(staff, MagicAttackKind.STAFF_FIREBALL, resolver);

        assertTrue(runtime.launch(cast, staff, null));
        assertTrue(received.get().isActive(), "The delayed impact keeps the cast open");
        scheduled.get(0).run();

        assertEquals(3, requests.size(), "One resolver call per impact");
        for (MagicAttackRequest request : requests) {
            assertEquals(cast.attackId(), request.attackId());
            assertEquals(MagicAttackKind.STAFF_FIREBALL, request.attackKind());
            assertSame(caster, request.attacker());
            assertEquals(staff.basePower(MagicAttackKind.STAFF_FIREBALL), request.balance().basePower());
        }
        assertEquals(List.of(1D, .5D, .25D), requests.stream().map(request -> request.balance().impactScale()).toList());
        assertEquals(100D - 10D - 2.5D, first.health, 1.0E-9D);
        assertEquals(100D - 5D, second.health, 1.0E-9D);
        assertFalse(received.get().isActive(), "The cast ends with its last task");
        assertFalse(received.get().impact(first.entity, 1D), "An ended cast reports no impact");
        assertEquals(3, requests.size());
        assertTrue(metadata.isEmpty());
    }

    @Test
    void disablingTheOwnerUnregistersItsBehaviorsAndEndsTheirCasts() {
        MagicWeaponDefinition staff = staff(Map.of());
        Target target = new Target();
        NamespacedKey beam = key("rayaddon", "beam");
        AtomicReference<MagicAttackCast> received = new AtomicReference<>();
        AtomicInteger cleanups = new AtomicInteger();
        AtomicInteger ticks = new AtomicInteger();
        registry.register(addon, beam, MagicAttackKind.STAFF_FIREBALL, handle -> {
            received.set(handle);
            handle.onEnd(cleanups::incrementAndGet);
            handle.runTimer(1L, 1L, ticks::incrementAndGet);
            return true;
        });
        registry.bind(addon, STAFF_ID, beam);

        assertTrue(runtime.launch(cast(staff, MagicAttackKind.STAFF_FIREBALL, null), staff, null));
        FakeTask timer = scheduled.get(0);
        assertSame(addon, timer.owner, "Tasks run in the behavior owner's name");
        timer.run();
        assertEquals(1, ticks.get());
        assertEquals(1, runtime.behaviorCasts().activeCount());

        runtime.onPluginDisable(new PluginDisableEvent(addon));
        addonEnabled.set(false);

        assertTrue(timer.cancelled);
        assertEquals(1, cleanups.get());
        assertFalse(received.get().isActive());
        assertEquals(0, runtime.behaviorCasts().activeCount());
        timer.run();
        assertEquals(1, ticks.get(), "A cancelled task never runs again");
        assertFalse(received.get().impact(target.entity, 1D));
        assertEquals(0, target.hits);
        assertTrue(received.get().runLater(1L, ticks::incrementAndGet).isCancelled());
        assertFalse(registry.isRegistered(beam));
        assertNull(registry.resolve(staff, MagicAttackKind.STAFF_FIREBALL, true), "The weapon is back on its built-in attack");
        assertFalse(registry.register(addon, beam, MagicAttackKind.STAFF_FIREBALL, cast -> true),
                "A disabled plugin registers nothing");
    }

    @Test
    void casterDepartureWorldChangeReloadAndAFailingBehaviorEndTheCast() {
        MagicWeaponDefinition staff = staff(Map.of());
        NamespacedKey mark = key("rayaddon", "mark");
        List<MagicAttackCast> casts = new ArrayList<>();
        AtomicInteger cleanups = new AtomicInteger();
        registry.register(addon, mark, MagicAttackKind.STAFF_FIREBALL, handle -> {
            casts.add(handle);
            handle.onEnd(cleanups::incrementAndGet);
            handle.runLater(40L, () -> { });
            return true;
        });
        registry.bind(addon, STAFF_ID, mark);

        runtime.launch(cast(staff, MagicAttackKind.STAFF_FIREBALL, null), staff, null);
        runtime.onQuit(new PlayerQuitEvent(caster, ""));
        assertFalse(casts.get(0).isActive());

        runtime.launch(cast(staff, MagicAttackKind.STAFF_FIREBALL, null), staff, null);
        runtime.onWorldChange(new PlayerChangedWorldEvent(caster, world));
        assertFalse(casts.get(1).isActive());

        runtime.launch(cast(staff, MagicAttackKind.STAFF_FIREBALL, null), staff, null);
        runtime.pauseForContentReload();
        assertFalse(casts.get(2).isActive());

        runtime.launch(cast(staff, MagicAttackKind.STAFF_FIREBALL, null), staff, null);
        casts.get(3).end();
        casts.get(3).end();
        assertEquals(4, cleanups.get(), "Cleanup runs once per cast");
        assertTrue(scheduled.stream().allMatch(task -> task.cancelled));
        assertEquals(0, runtime.behaviorCasts().activeCount());

        // Replacing a registration ends the casts of the behavior it replaces.
        runtime.launch(cast(staff, MagicAttackKind.STAFF_FIREBALL, null), staff, null);
        assertTrue(registry.register(addon, mark, MagicAttackKind.STAFF_FIREBALL, handle -> {
            throw new IllegalStateException("broken behavior");
        }));
        assertFalse(casts.get(4).isActive());
        assertFalse(runtime.launch(cast(staff, MagicAttackKind.STAFF_FIREBALL, null), staff, null),
                "A behavior that throws casts nothing");
        assertEquals(0, runtime.behaviorCasts().activeCount());
    }

    private MagicWeaponDefinition parse(String weaponSection) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("weapon:\n" + weaponSection.indent(2));
        return MagicWeaponConfig.parse(STAFF_ID, yaml.getConfigurationSection("weapon"));
    }

    private static MagicWeaponDefinition staff(Map<MagicAttackKind, NamespacedKey> behaviors) {
        MagicWeaponDefinition defaults = BuiltInMagicWeapons.defaults(STAFF_ID, MagicWeaponKind.STAFF);
        return new MagicWeaponDefinition(STAFF_ID, defaults.kind(), defaults.traits(), defaults.basePowers(),
                defaults.reloadTicks(), behaviors);
    }

    private MagicCast cast(MagicWeaponDefinition definition, MagicAttackKind attackKind, MagicAttackResolver resolver) {
        return new MagicCast(UUID.randomUUID(), caster, new ItemStack(Material.BLAZE_ROD), definition,
                attackKind, resolver, Map.of(), Map.of(), Map.of());
    }

    private void installResolver(MagicAttackResolver resolver) throws Exception {
        Field owner = MagicWeaponRuntime.class.getDeclaredField("resolverOwner");
        owner.setAccessible(true);
        owner.set(runtime, otherAddon);
        Field active = MagicWeaponRuntime.class.getDeclaredField("resolver");
        active.setAccessible(true);
        active.set(runtime, resolver);
    }

    private static NamespacedKey key(String namespace, String name) {
        return new NamespacedKey(namespace, name);
    }

    private static Plugin plugin(String name, AtomicBoolean enabled) {
        return proxy(Plugin.class, (instance, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "isEnabled" -> enabled.get();
            default -> identity(instance, method.getName(), args, defaultValue(method.getReturnType()));
        });
    }

    private static Object identity(Object instance, String method, Object[] args, Object fallback) {
        return switch (method) {
            case "equals" -> instance == args[0];
            case "hashCode" -> System.identityHashCode(instance);
            case "toString" -> "proxy@" + System.identityHashCode(instance);
            default -> fallback;
        };
    }

    private static final class FakeTask {
        private final Plugin owner;
        private final long period;
        private final Runnable body;
        private boolean cancelled;

        private FakeTask(Plugin owner, long period, Runnable body) {
            this.owner = owner;
            this.period = period;
            this.body = body;
        }

        /** Like Bukkit: the scheduler still holds the wrapper; only a cancelled task is skipped. */
        private void run() {
            if (!cancelled) body.run();
            if (period == 0L) cancelled = true;
        }
    }

    private final class Target {
        private double health = 100D;
        private int hits;
        private final LivingEntity entity;

        private Target() {
            Location feet = new Location(world, 4D, 64D, 0D);
            entity = proxy(LivingEntity.class, (instance, method, args) -> switch (method.getName()) {
                case "getLocation" -> feet.clone();
                case "getWorld" -> world;
                case "getHeight" -> 2D;
                case "isValid" -> true;
                case "getHealth" -> health;
                case "damage" -> {
                    health -= (Double) args[0];
                    hits++;
                    yield null;
                }
                default -> identity(instance, method.getName(), args, defaultValue(method.getReturnType()));
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    /** Uses NMSAdapter's actual Bukkit cooldown accessors. Unrelated native operations must never run. */
    private static final class LegacyAdapter extends NMSAdapter {
        @Override public void damageWithoutCooldown(LivingEntity target, double amount, Entity source) { throw new AssertionError(); }
        @Override public boolean move(LivingEntity entity, double speed, Location target) { throw new AssertionError(); }
        @Override public AbstractWanderBackToPoint wanderBackToPoint(LivingEntity entity, Location target, double distance,
                int ticks, OverridableWanderPriority priority) { throw new AssertionError(); }
        @Override public boolean setCustomHitbox(Entity entity, float width, float height, boolean fixed) { throw new AssertionError(); }
        @Override public float getBodyRotation(Entity entity) { throw new AssertionError(); }
        @Override public PacketModelEntity createPacketArmorStandEntity(Location location) { throw new AssertionError(); }
        @Override public PacketModelEntity createPacketDisplayEntity(Location location) { throw new AssertionError(); }
        @Override public void doNotMove(LivingEntity entity) { throw new AssertionError(); }
        @Override public void setBlockInNativeDataPalette(World world, int x, int y, int z, BlockData data, boolean physics) { throw new AssertionError(); }
        @Override public AbstractPacketBundle createPacketBundle() { throw new AssertionError(); }
        @Override public FakeText createFakeText(Location location, FakeTextSettings settings) { throw new AssertionError(); }
    }
}
