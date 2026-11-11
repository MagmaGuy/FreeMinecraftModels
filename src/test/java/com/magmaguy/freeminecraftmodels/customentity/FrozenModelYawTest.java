package com.magmaguy.freeminecraftmodels.customentity;

import com.google.gson.Gson;
import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.constants.OverridableWanderPriority;
import com.magmaguy.easyminecraftgoals.internal.*;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The yaw a model keeps once it stops following its mob. In the tick a mob spawns, the server still reads its body
 * yaw as 0, so a model frozen then faces the yaw the mob was spawned at instead. A model frozen later, and one that
 * keeps following, read the body yaw as before.
 */
class FrozenModelYawTest {
    @TempDir
    Path directory;
    private Plugin previousPlugin;
    private Field adapterField;
    private Object previousAdapter;
    private String modelId;
    // The mob as the server reports it.
    private float mobYaw, bodyYaw;
    private int ticksLived;

    @BeforeEach
    void loadModel() throws Exception {
        previousPlugin = MetadataHandler.PLUGIN;
        MetadataHandler.PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "FreeMinecraftModels";
                    throw new UnsupportedOperationException(method.getName());
                });
        adapterField = NMSManager.class.getDeclaredField("adapter");
        adapterField.setAccessible(true);
        previousAdapter = adapterField.get(null);
        adapterField.set(null, new BodyYawAdapter());
        Path source = directory.resolve("frozen_yaw_probe.bbmodel");
        Files.writeString(source, new Gson().toJson(Map.of(
                "meta", Map.of("format_version", "5.0", "model_format", "free"),
                "elements", List.of(),
                "outliner", List.of(Map.of("name", "body", "uuid", "bone-body", "origin", List.of(0, 8, 0),
                        "children", List.of())))));
        modelId = new FileModelConverter(source.toFile()).getID();
    }

    @AfterEach
    void discardModel() throws Exception {
        FileModelConverter.shutdown();
        adapterField.set(null, previousAdapter);
        MetadataHandler.PLUGIN = previousPlugin;
    }

    private DynamicEntity modelOn(float yaw, float body, int age) {
        return modelOn(yaw, body, age, null);
    }

    /** A model on a mob that faces {@code yaw}, with the body yaw and age the server reports for it. */
    private DynamicEntity modelOn(float yaw, float body, int age, World world) {
        mobYaw = yaw;
        bodyYaw = body;
        ticksLived = age;
        DynamicEntity model = new DynamicEntity(modelId, new Location(world, 10, 64, 20, yaw, 0));
        model.underlyingEntity = (LivingEntity) Proxy.newProxyInstance(LivingEntity.class.getClassLoader(),
                new Class<?>[]{LivingEntity.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getLocation" -> new Location(world, 10, 64, 20, mobYaw, 0);
                    case "getEyeLocation" -> new Location(world, 10, 65.6, 20, mobYaw, 0);
                    case "getTicksLived" -> ticksLived;
                    case "isValid" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return model;
    }

    private float frozenYaw(DynamicEntity model) {
        model.setSyncMovement(false);
        assertEquals(model.getLocation().getYaw(), model.getBoneTransformLocation().getYaw(),
                "The bones are drawn at the frozen yaw");
        return model.getLocation().getYaw();
    }

    @Test
    void aModelFrozenInItsMobsSpawnTickFacesTheYawTheMobWasSpawnedAt() {
        for (float yaw : new float[]{0F, 90F, -180F, -90F, 135F})
            assertEquals(yaw, frozenYaw(modelOn(yaw, 0F, 0)), "A mob spawned at yaw " + yaw);
    }

    @Test
    void aModelFrozenAfterItsMobTickedKeepsTheBodyYaw() {
        // One tick in, the server has turned the body 15 degrees of the way: the model trails the head by 45.
        assertEquals(45F, frozenYaw(modelOn(90F, 15F, 1)));
        assertEquals(-135F, frozenYaw(modelOn(-180F, -15F, 1)));
        // Once the body has caught up, the model faces the mob.
        assertEquals(90F, frozenYaw(modelOn(90F, 90F, 60)));
        assertEquals(-180F, frozenYaw(modelOn(-180F, -180F, 60)));
        // A mob that turned its head: the body yaw decides, not the mob's own yaw.
        assertEquals(100F, frozenYaw(modelOn(120F, 100F, 400)));
    }

    @Test
    void aLookTargetStillFacesAModelFrozenInTheSpawnTick() {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 1;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DynamicEntity model = modelOn(90F, 0F, 0, world);
        model.setLookTarget(new Location(world, 10, 64, 30));
        assertEquals(0F, frozenYaw(model), 1e-4F, "Ten blocks south of the mob is yaw 0");
    }

    @Test
    void aModelThatKeepsFollowingReadsTheBodyYawAsBefore() {
        assertEquals(45F, modelOn(90F, 0F, 0).getLocation().getYaw());
        assertEquals(90F, modelOn(90F, 90F, 60).getLocation().getYaw());
    }

    /** Reports the mob's body yaw. Nothing else native may run. */
    private final class BodyYawAdapter extends NMSAdapter {
        @Override public float getBodyRotation(Entity entity) { return bodyYaw; }
        @Override public void damageWithoutCooldown(LivingEntity target, double amount, Entity source) { throw new AssertionError(); }
        @Override public boolean move(LivingEntity entity, double speed, Location target) { throw new AssertionError(); }
        @Override public boolean forcedMove(LivingEntity entity, double speed, Location target) { throw new AssertionError(); }
        @Override public void universalMove(LivingEntity entity, double speed, Location target) { throw new AssertionError(); }
        @Override public AbstractWanderBackToPoint wanderBackToPoint(LivingEntity entity, Location target, double distance,
                int ticks, OverridableWanderPriority priority) { throw new AssertionError(); }
        @Override public boolean setCustomHitbox(Entity entity, float width, float height, boolean fixed) { throw new AssertionError(); }
        @Override public PacketModelEntity createPacketArmorStandEntity(Location location) { throw new AssertionError(); }
        @Override public PacketModelEntity createPacketDisplayEntity(Location location) { throw new AssertionError(); }
        @Override public PacketTextEntity createPacketTextArmorStandEntity(Location location) { throw new AssertionError(); }
        @Override public void doNotMove(LivingEntity entity) { throw new AssertionError(); }
        @Override public void setBlockInNativeDataPalette(World world, int x, int y, int z, BlockData data, boolean physics) { throw new AssertionError(); }
        @Override public AbstractPacketBundle createPacketBundle() { throw new AssertionError(); }
        @Override public FakeText createFakeText(Location location, FakeTextSettings settings) { throw new AssertionError(); }
    }
}
