package com.magmaguy.freeminecraftmodels.pets.migration;

import com.magmaguy.freeminecraftmodels.pets.MigrationRoute;
import com.magmaguy.freeminecraftmodels.pets.PetControl;
import com.magmaguy.freeminecraftmodels.pets.PetManager;
import com.magmaguy.freeminecraftmodels.pets.PetSpecies;
import com.magmaguy.freeminecraftmodels.pets.interactions.PetInteractions;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One migrating flock: wild pets of a species crossing the sky along a straight path that passes over a player halfway
 * through its flight. Each member is an ordinary wild pet, full model and animations, flying this route at the top of
 * its Mind program: a slot in a loose formation around an anchor that moves along the path, nudged apart from its
 * neighbours and bobbing gently. A player who reaches one can tame it, which takes it out of the flock. When the
 * crossing ends, the members still flying go.
 */
public final class MigrationFlock implements MigrationRoute {
    // Formation radius grows with the flock; members keep this far apart at least.
    private static final double SPREAD_BASE = 6D, SPREAD_PER_ROOT = 3D, SEPARATION_RADIUS = 5D, SEPARATION_PUSH = 1.5D;
    private static final double BOB_HEIGHT = 1.5D, BOB_PERIOD_TICKS = 160D, HEIGHT_SPREAD = 5D;
    // A member may fly this much faster than the anchor to take or keep its slot.
    private static final double CATCH_UP = 1.8D;

    private record Slot(double x, double y, double z, double phase) {
    }

    private final World world;
    private final Vector heading;
    private final Vector anchorStart;
    private final double speed;
    private final double altitude;
    private final int durationTicks;
    private final Map<PetControl, Slot> members = new IdentityHashMap<>();
    private BukkitTask task;
    private int age;
    private boolean done;

    private MigrationFlock(World world, Vector heading, Vector anchorStart, double speed, double altitude, int durationTicks) {
        this.world = world;
        this.heading = heading;
        this.anchorStart = anchorStart;
        this.speed = speed;
        this.altitude = altitude;
        this.durationTicks = durationTicks;
    }

    /** Puts a flock of the species in the air on a path over the player, or returns null when none could spawn. */
    static MigrationFlock launch(Plugin plugin, Player player, PetSpecies species, PetInteractions.Migration look,
                                 PetMigrations.Settings settings) {
        PetManager manager = PetManager.get();
        if (manager == null) return null;
        World world = player.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble() * Math.PI * 2D;
        Vector heading = new Vector(Math.cos(angle), 0D, Math.sin(angle));
        Location over = player.getLocation();
        int ground = world.getHighestBlockYAt(over.getBlockX(), over.getBlockZ(), HeightMap.MOTION_BLOCKING);
        double height = Math.min(world.getMaxHeight() - 4D, Math.max(over.getY(), ground) + settings.altitude());
        // Halfway through the flight the anchor passes over the player. It starts where its members still tick and
        // can be seen: inside the simulation distance and the view distance.
        double simulated = Math.max(32D, (world.getSimulationDistance() - 1) * 16D);
        double approach = Math.min(settings.speed() * settings.durationTicks() / 2D,
                Math.min(simulated * 0.8D, settings.viewDistance() * 0.9D));
        Vector start = over.toVector().subtract(heading.clone().multiply(approach)).setY(height);
        int size = settings.minFlockSize() + random.nextInt(settings.maxFlockSize() - settings.minFlockSize() + 1);
        MigrationFlock flock = new MigrationFlock(world, heading, start, settings.speed(), height, settings.durationTicks());
        double spread = SPREAD_BASE + SPREAD_PER_ROOT * Math.sqrt(size);
        // A rare flock: one unusual colour for every member. Otherwise each member picks its own, like a wild spawn.
        String model = look.model();
        if (model == null && random.nextDouble() < settings.rareFlockChance()) model = species.pickRareVariant(random);
        for (int index = 0; index < size; index++) {
            double around = random.nextDouble() * Math.PI * 2D, out = Math.sqrt(random.nextDouble()) * spread;
            Slot slot = new Slot(Math.cos(around) * out, (random.nextDouble() - 0.5D) * HEIGHT_SPREAD, Math.sin(around) * out,
                    random.nextDouble() * Math.PI * 2D);
            Location at = new Location(world, start.getX() + slot.x(), height + slot.y(), start.getZ() + slot.z());
            if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            PetControl member = manager.spawnMigrant(species, at, model, look.scale(), settings.viewDistance(), flock);
            if (member == null) continue;
            if (look.animation() != null) member.modeled().playAnimation(look.animation(), false, true);
            flock.members.put(member, slot);
        }
        if (flock.members.isEmpty()) {
            Logger.warn("A " + species.id() + " migration could not start: no member could spawn near " + player.getName() + ".");
            return null;
        }
        flock.task = Bukkit.getScheduler().runTaskTimer(plugin, flock::tick, 1L, 1L);
        return flock;
    }

    private void tick() {
        if (done) return;
        age++;
        if (age > durationTicks || world.getPlayers().isEmpty()) {
            remove();
            return;
        }
        // Tamed, killed or lost members leave the flock.
        PetManager manager = PetManager.get();
        if (manager == null) {
            remove();
            return;
        }
        members.keySet().removeIf(member -> member.body() == null || manager.pet(member.body()) != member);
        if (members.isEmpty()) remove();
    }

    @Override
    public Location target(PetControl pet) {
        Slot slot = done ? null : members.get(pet);
        LivingEntity body = pet.body();
        if (slot == null || body == null) return null;
        double anchorX = anchorStart.getX() + heading.getX() * speed * age;
        double anchorZ = anchorStart.getZ() + heading.getZ() * speed * age;
        double bob = Math.sin(age * Math.PI * 2D / BOB_PERIOD_TICKS + slot.phase()) * BOB_HEIGHT;
        Vector target = new Vector(anchorX + slot.x(), altitude + slot.y() + bob, anchorZ + slot.z());
        // Shoulder away from any member closer than the separation radius, so the slots never bunch up.
        Vector here = body.getLocation().toVector();
        for (PetControl other : members.keySet()) {
            if (other == pet || other.body() == null) continue;
            Vector away = here.clone().subtract(other.body().getLocation().toVector());
            double distance = away.length();
            if (distance > 1.0E-3D && distance < SEPARATION_RADIUS)
                target.add(away.multiply((SEPARATION_RADIUS - distance) / distance * SEPARATION_PUSH / SEPARATION_RADIUS));
        }
        return target.toLocation(world);
    }

    @Override
    public double cruise() {
        return speed * CATCH_UP;
    }

    public boolean isDone() {
        return done;
    }

    /** Whether the flock's anchor is within the distance of the location. */
    boolean isNear(Location location, double distance) {
        if (done || location.getWorld() != world) return false;
        double dx = anchorStart.getX() + heading.getX() * speed * age - location.getX();
        double dz = anchorStart.getZ() + heading.getZ() * speed * age - location.getZ();
        return dx * dx + dz * dz <= distance * distance;
    }

    /** Ends the crossing: members still flying it go; tamed ones already left. */
    public void remove() {
        if (done) return;
        done = true;
        if (task != null) task.cancel();
        PetManager manager = PetManager.get();
        if (manager != null) {
            List<PetControl> flying = new ArrayList<>(members.keySet());
            boolean said = false;
            for (PetControl member : flying) {
                if (member.body() == null || manager.pet(member.body()) != member) continue;
                // One member that cannot go must not keep the rest of the flock in the sky.
                try {
                    manager.despawn(member);
                } catch (RuntimeException failure) {
                    if (said) continue;
                    said = true;
                    Logger.warn("A " + member.species().id() + " migration could not remove a member of its flock: "
                            + failure);
                    failure.printStackTrace();
                }
            }
        }
        members.clear();
    }
}
