package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.constants.OverridableWanderPriority;
import com.magmaguy.easyminecraftgoals.internal.*;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production damage owner; the target models the legacy Bukkit hit gate, not native damage. */
class MagicWeaponDamageCooldownTest {
    private final Map<String, MetadataValue> metadata = new HashMap<>();
    private Field adapterField;
    private Object previousAdapter;
    private Field serverField;
    private Object previousServer;
    private MagicWeaponRuntime runtime;
    private Player owner;

    @BeforeEach
    void installLegacyAdapter() throws Exception {
        adapterField = NMSManager.class.getDeclaredField("adapter");
        adapterField.setAccessible(true);
        previousAdapter = adapterField.get(null);
        adapterField.set(null, new LegacyAdapter());
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousServer = serverField.get(null);
        serverField.set(null, proxy(Server.class, (instance, method, args) ->
                method.getName().equals("isPrimaryThread") ? true : defaultValue(method.getReturnType())));
        Plugin plugin = proxy(Plugin.class, (instance, method, args) ->
                method.getName().equals("getName") ? "MagicCooldownTest" : defaultValue(method.getReturnType()));
        owner = proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
            case "getMetadata" -> metadata.containsKey(args[0]) ? List.of(metadata.get(args[0])) : List.of();
            case "setMetadata" -> { metadata.put((String) args[0], (MetadataValue) args[1]); yield null; }
            case "removeMetadata" -> { metadata.remove(args[0]); yield null; }
            case "getUniqueId" -> new UUID(0, 1);
            default -> defaultValue(method.getReturnType());
        });
        runtime = new MagicWeaponRuntime(plugin, new MagicWeaponCatalog(List.of()));
    }

    @AfterEach
    void restoreGlobals() throws Exception {
        if (adapterField != null) adapterField.set(null, previousAdapter);
        if (serverField != null) serverField.set(null, previousServer);
    }

    @Test
    void everyMulticastBoltReachesTheSameTargetAndPreservesItsPreviousHitState() throws Throwable {
        TargetState state = new TargetState(13, 7D);
        MagicCast cast = cast(3);
        for (int bolt = 0; bolt < 3; bolt++) {
            damage(cast, state, 4D);
            assertEquals(13, state.cooldown);
            assertEquals(7D, state.lastDamage);
        }
        assertEquals(3, state.acceptedHits);
        assertEquals(88D, state.health);
        assertTrue(metadata.isEmpty(), "Damage observer metadata must be released after each bolt");
    }

    @Test
    void consecutiveSingleBoltsEachLandWithoutOpeningAHitWindow() throws Throwable {
        TargetState state = new TargetState(0, 7D);
        MagicCast cast = cast(1);
        damage(cast, state, 4D);
        damage(cast, state, 4D);
        assertEquals(2, state.acceptedHits);
        assertEquals(92D, state.health);
        assertEquals(0, state.cooldown);
        assertEquals(7D, state.lastDamage);
    }

    /** Another source hit the target for 7 three ticks ago; a weaker bolt used to vanish into that window. */
    @Test
    void aSingleBoltLandsInsideAnotherHitsCooldownAndRestoresIt() throws Throwable {
        TargetState state = new TargetState(17, 7D);
        damage(cast(1), state, 4D);
        assertEquals(1, state.acceptedHits);
        assertEquals(96D, state.health);
        assertEquals(17, state.cooldown);
        assertEquals(7D, state.lastDamage);
    }

    /** A staff strike right after the player's own fireball used to be swallowed while its reload was spent. */
    @Test
    void staffMeleeLandsInsideTheFireballsCooldownAndRestoresIt() throws Throwable {
        TargetState state = new TargetState(15, 5D);
        damage(staffCast(MagicAttackKind.STAFF_MELEE), state, 1.8D);
        assertEquals(1, state.acceptedHits);
        assertEquals(98.2D, state.health, 1.0E-9D);
        assertEquals(15, state.cooldown);
        assertEquals(5D, state.lastDamage);
    }

    @Test
    void aDirectFireballHitDealsFullPowerWhileSplashTargetsKeepTheirFalloff() throws Throwable {
        List<Entity> nearby = new ArrayList<>();
        World world = proxy(World.class, (instance, method, args) -> switch (method.getName()) {
            case "getNearbyEntities" -> List.copyOf(nearby);
            case "equals" -> instance == args[0];
            case "hashCode" -> System.identityHashCode(instance);
            default -> defaultValue(method.getReturnType());
        });
        Location impact = new Location(world, 0D, 64D, 0D);
        // Two-block-tall targets centred 1.04 blocks above their feet: the struck one 1 block from
        // the impact, the splashed one 2 blocks away.
        TargetState struck = new TargetState(0, 0D, new Location(world, 1D, 64D - 1.04D, 0D));
        TargetState splashed = new TargetState(0, 0D, new Location(world, -2D, 64D - 1.04D, 0D));
        nearby.add(struck.entity);
        nearby.add(splashed.entity);
        MagicCast fireball = staffCast(MagicAttackKind.STAFF_FIREBALL);
        double radius = fireball.definition().traits().impactRadius();
        double fullDamage = BuiltInMagicWeapons.STANDALONE_REFERENCE_DAMAGE
                * fireball.definition().basePower(MagicAttackKind.STAFF_FIREBALL);

        var blast = MagicWeaponRuntime.class.getDeclaredMethod(
                "damageBlast", MagicCast.class, LivingEntity.class, Location.class, Vector.class);
        blast.setAccessible(true);
        try { blast.invoke(runtime, fireball, struck.entity, impact, new Vector(1D, 0D, 0D)); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }

        assertEquals(1, struck.acceptedHits);
        assertEquals(100D - fullDamage, struck.health, 1.0E-9D);
        assertEquals(1, splashed.acceptedHits);
        assertEquals(100D - fullDamage * (.35D + .65D * (1D - 2D / radius)), splashed.health, 1.0E-9D);
    }

    @Test
    void cancelledMulticastRestoresThePreviousHitStateWithoutApplyingDamage() throws Throwable {
        TargetState state = new TargetState(13, 7D);
        state.cancelled = true;
        assertFalse(damage(cast(3), state, 4D));
        assertEquals(0, state.acceptedHits);
        assertEquals(100D, state.health);
        assertEquals(13, state.cooldown);
        assertEquals(7D, state.lastDamage);
        assertTrue(metadata.isEmpty());
    }

    @Test
    void aFailedDamageCallbackRestoresCooldownLastDamageAndObserverMetadata() {
        TargetState state = new TargetState(13, 7D);
        state.failure = new IllegalStateException("Damage listener failed");
        assertSame(state.failure, assertThrows(IllegalStateException.class, () -> damage(cast(3), state, 4D)));
        assertEquals(13, state.cooldown);
        assertEquals(7D, state.lastDamage);
        assertTrue(metadata.isEmpty());
    }

    private MagicCast cast(int missiles) {
        MagicWeaponDefinition base = BuiltInMagicWeapons.defaults(BuiltInMagicWeapons.DEFAULT_WAND_ID, MagicWeaponKind.WAND);
        MagicWeaponTraits traits = base.traits();
        MagicWeaponDefinition definition = base.withTraits(new MagicWeaponTraits(missiles,
                traits.spreadDegrees(), traits.impactRadius(), traits.ignitionTicks(), traits.projectileSpeed(),
                traits.range(), traits.travelTicks(), traits.aimAssistDegrees()));
        return new MagicCast(UUID.randomUUID(), owner, new ItemStack(Material.BLAZE_ROD), definition,
                MagicAttackKind.WAND_MISSILE, null, Map.of(), Map.of(), Map.of());
    }

    private MagicCast staffCast(MagicAttackKind attackKind) {
        MagicWeaponDefinition definition = BuiltInMagicWeapons.defaults(BuiltInMagicWeapons.DEFAULT_STAFF_ID, MagicWeaponKind.STAFF);
        return new MagicCast(UUID.randomUUID(), owner, new ItemStack(Material.BLAZE_ROD), definition,
                attackKind, null, Map.of(), Map.of(), Map.of());
    }

    private boolean damage(MagicCast cast, TargetState state, double amount) throws Throwable {
        var method = MagicWeaponRuntime.class.getDeclaredMethod("damageTarget", MagicCast.class, LivingEntity.class, double.class);
        method.setAccessible(true);
        try { return (boolean) method.invoke(runtime, cast, state.entity, amount); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static final class TargetState {
        private int cooldown;
        private double lastDamage;
        private double health = 100D;
        private int acceptedHits;
        private boolean cancelled;
        private RuntimeException failure;
        private final LivingEntity entity;

        private TargetState(int cooldown, double lastDamage) {
            this(cooldown, lastDamage, null);
        }

        private TargetState(int cooldown, double lastDamage, Location feet) {
            this.cooldown = cooldown;
            this.lastDamage = lastDamage;
            entity = proxy(LivingEntity.class, (instance, method, args) -> switch (method.getName()) {
                case "equals" -> instance == args[0];
                case "hashCode" -> System.identityHashCode(instance);
                case "getLocation" -> feet == null ? null : feet.clone();
                case "getWorld" -> feet == null ? null : feet.getWorld();
                case "getHeight" -> 2D;
                case "isValid" -> true;
                case "getHealth" -> health;
                case "getNoDamageTicks" -> this.cooldown;
                case "setNoDamageTicks" -> { this.cooldown = (Integer) args[0]; yield null; }
                case "getLastDamage" -> this.lastDamage;
                case "setLastDamage" -> { this.lastDamage = (Double) args[0]; yield null; }
                case "damage" -> {
                    double amount = (Double) args[0];
                    if (!cancelled && !(this.cooldown > 10 && amount <= this.lastDamage)) {
                        this.cooldown = 20;
                        this.lastDamage = amount;
                        if (failure != null) throw failure;
                        health -= amount;
                        acceptedHits++;
                    }
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
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
        @Override public boolean forcedMove(LivingEntity entity, double speed, Location target) { throw new AssertionError(); }
        @Override public void universalMove(LivingEntity entity, double speed, Location target) { throw new AssertionError(); }
        @Override public AbstractWanderBackToPoint wanderBackToPoint(LivingEntity entity, Location target, double distance,
                int ticks, OverridableWanderPriority priority) { throw new AssertionError(); }
        @Override public boolean setCustomHitbox(Entity entity, float width, float height, boolean fixed) { throw new AssertionError(); }
        @Override public float getBodyRotation(Entity entity) { throw new AssertionError(); }
        @Override public PacketModelEntity createPacketArmorStandEntity(Location location) { throw new AssertionError(); }
        @Override public PacketModelEntity createPacketDisplayEntity(Location location) { throw new AssertionError(); }
        @Override public PacketTextEntity createPacketTextArmorStandEntity(Location location) { throw new AssertionError(); }
        @Override public void doNotMove(LivingEntity entity) { throw new AssertionError(); }
        @Override public void setBlockInNativeDataPalette(World world, int x, int y, int z, BlockData data, boolean physics) { throw new AssertionError(); }
        @Override public AbstractPacketBundle createPacketBundle() { throw new AssertionError(); }
        @Override public FakeText createFakeText(Location location, FakeTextSettings settings) { throw new AssertionError(); }
    }
}
