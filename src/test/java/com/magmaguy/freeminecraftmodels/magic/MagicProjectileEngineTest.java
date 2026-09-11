package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagicProjectileEngineTest {

    @Test
    void defaultWandAcquiresTheClosestToCrosshairEnemyAcrossAGenerousForwardCone() {
        AtomicReference<Collection<Entity>> nearbyEntities = new AtomicReference<>(List.of());
        World world = world(nearbyEntities);
        Location eye = new Location(world, 0D, 64D, 0D, 0F, 0F);
        Player owner = player(world, eye);
        LivingEntity fartherFromCrosshair = target(world, targetBaseAt(eye, 44D, 5D));
        LivingEntity closestToCrosshair = target(world, targetBaseAt(eye, 40D, 10D));
        nearbyEntities.set(List.of(fartherFromCrosshair, closestToCrosshair));

        MagicWeaponTraits traits = BuiltInMagicWeapons.defaults(BuiltInMagicWeapons.DEFAULT_WAND_ID, com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind.WAND)
                .traits();
        MagicProjectileEngine engine = new MagicProjectileEngine(
                proxy(Plugin.class, (ignored, method, arguments) -> defaultValue(method.getReturnType())),
                (cast, directTarget, impact, incoming) -> { });

        Optional<LivingEntity> selected = engine.acquireTarget(
                owner, traits.range(), traits.aimAssistDegrees(), ignored -> true, ignored -> 1);

        assertAll(
                () -> assertSame(closestToCrosshair, selected.orElseThrow()),
                () -> assertEquals(45D, traits.aimAssistDegrees()));
    }

    @Test
    void staffProjectileSpawnsPointThreeBlocksBelowItsPreviousEyeLevelOrigin() {
        AtomicReference<Location> spawnedAt = new AtomicReference<>();
        World world = staffWorld(spawnedAt);
        Location eye = new Location(world, 8D, 70D, -3D);
        Player owner = player(world, eye);
        Plugin plugin = plugin();
        Plugin previousPlugin = MetadataHandler.PLUGIN;
        MetadataHandler.PLUGIN = plugin;
        try {
            MagicProjectileEngine engine = new MagicProjectileEngine(
                    plugin, (cast, directTarget, impact, incoming) -> { });
            MagicCast cast = new MagicCast(
                    UUID.randomUUID(),
                    owner,
                    new ItemStack(Material.BLAZE_ROD),
                    BuiltInMagicWeapons.defaults(BuiltInMagicWeapons.DEFAULT_STAFF_ID, com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind.STAFF),
                    MagicAttackKind.STAFF_FIREBALL, null, java.util.Map.of(), java.util.Map.of(), java.util.Map.of());

            assertTrue(engine.launchStaff(cast, new Vector(0D, 0D, 1D)));

            Location origin = spawnedAt.get();
            assertAll(
                    () -> assertEquals(eye.getX(), origin.getX(), 1.0E-9D),
                    () -> assertEquals(eye.getY() - .3D, origin.getY(), 1.0E-9D),
                    () -> assertEquals(eye.getZ() + .45D, origin.getZ(), 1.0E-9D));
        } finally {
            MetadataHandler.PLUGIN = previousPlugin;
        }
    }

    @Test
    void wandProjectileSpawnsPointThreeBlocksBelowItsPreviousEyeLevelOrigin() {
        AtomicReference<Location> spawnedAt = new AtomicReference<>();
        World world = staffWorld(spawnedAt);
        Location eye = new Location(world, 8D, 70D, -3D);
        Player owner = player(world, eye);
        LivingEntity target = target(
                world,
                new Location(world, eye.getX(), eye.getY() - 1.04D, eye.getZ() + 10D));
        Plugin plugin = plugin();
        Plugin previousPlugin = MetadataHandler.PLUGIN;
        MetadataHandler.PLUGIN = plugin;
        try {
            MagicProjectileEngine engine = new MagicProjectileEngine(
                    plugin, (cast, directTarget, impact, incoming) -> { });
            MagicCast cast = new MagicCast(
                    UUID.randomUUID(),
                    owner,
                    new ItemStack(Material.BLAZE_ROD),
                    BuiltInMagicWeapons.defaults(BuiltInMagicWeapons.DEFAULT_WAND_ID, com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind.WAND),
                    MagicAttackKind.WAND_MISSILE, null, java.util.Map.of(), java.util.Map.of(), java.util.Map.of());

            assertTrue(engine.launchWand(cast, target));

            Location origin = spawnedAt.get();
            assertAll(
                    () -> assertEquals(eye.getX(), origin.getX(), 1.0E-9D),
                    () -> assertEquals(eye.getY() - .3D, origin.getY(), 1.0E-9D),
                    () -> assertEquals(eye.getZ() + .35D, origin.getZ(), 1.0E-9D));
        } finally {
            MetadataHandler.PLUGIN = previousPlugin;
        }
    }

    private static Location targetBaseAt(Location eye, double angleDegrees, double distance) {
        double radians = Math.toRadians(angleDegrees);
        return new Location(
                eye.getWorld(),
                eye.getX() + Math.sin(radians) * distance,
                eye.getY() - 1.04D,
                eye.getZ() + Math.cos(radians) * distance);
    }

    private static World world(AtomicReference<Collection<Entity>> nearbyEntities) {
        return proxy(World.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getNearbyEntities" -> nearbyEntities.get();
            case "rayTraceBlocks" -> null;
            case "equals" -> instance == arguments[0];
            case "hashCode" -> System.identityHashCode(instance);
            case "toString" -> "WorldProxy";
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static World staffWorld(AtomicReference<Location> spawnedAt) {
        AtomicReference<World> world = new AtomicReference<>();
        PersistentDataContainer data = proxy(
                PersistentDataContainer.class,
                (ignored, method, arguments) -> defaultValue(method.getReturnType()));
        World proxy = proxy(World.class, (instance, method, arguments) -> switch (method.getName()) {
            case "isChunkLoaded" -> true;
            case "spawn" -> {
                Location origin = ((Location) arguments[0]).clone();
                spawnedAt.set(origin);
                UUID projectileId = UUID.randomUUID();
                Arrow marker = proxy(Arrow.class, (arrow, arrowMethod, arrowArguments) ->
                        switch (arrowMethod.getName()) {
                            case "getUniqueId" -> projectileId;
                            case "getWorld" -> world.get();
                            case "getLocation" -> origin.clone();
                            case "getPersistentDataContainer" -> data;
                            case "equals" -> arrow == arrowArguments[0];
                            case "hashCode" -> System.identityHashCode(arrow);
                            case "toString" -> "ArrowProxy";
                            default -> defaultValue(arrowMethod.getReturnType());
                        });
                ((Consumer<Arrow>) arguments[2]).accept(marker);
                yield marker;
            }
            case "equals" -> instance == arguments[0];
            case "hashCode" -> System.identityHashCode(instance);
            case "toString" -> "StaffWorldProxy";
            default -> defaultValue(method.getReturnType());
        });
        world.set(proxy);
        return proxy;
    }

    private static Plugin plugin() {
        return proxy(Plugin.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getName" -> "FreeMinecraftModels";
            case "equals" -> instance == arguments[0];
            case "hashCode" -> System.identityHashCode(instance);
            case "toString" -> "PluginProxy";
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Player player(World world, Location eye) {
        return proxy(Player.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getEyeLocation" -> eye.clone();
            case "getWorld" -> world;
            case "equals" -> instance == arguments[0];
            case "hashCode" -> System.identityHashCode(instance);
            case "toString" -> "OwnerProxy";
            default -> defaultValue(method.getReturnType());
        });
    }

    private static LivingEntity target(World world, Location location) {
        return proxy(LivingEntity.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getLocation" -> location.clone();
            case "getWorld" -> world;
            case "getHeight" -> 2D;
            case "getHealth" -> 20D;
            case "isValid" -> true;
            case "isDead" -> false;
            case "equals" -> instance == arguments[0];
            case "hashCode" -> System.identityHashCode(instance);
            case "toString" -> "TargetProxy";
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
