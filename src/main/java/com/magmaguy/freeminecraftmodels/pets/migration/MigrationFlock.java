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
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One migrating flock: wild pets of a species crossing the sky along a straight path that passes over a player halfway
 * through its flight. Each member is an ordinary wild pet, full model and animations, flying this route at the top of
 * its Mind program: a slot in a loose formation around an anchor that moves along the path, nudged apart from its
 * neighbours and bobbing gently. A player who reaches one can tame it, which takes it out of the flock. A member that
 * flies out of the chunks the server ticks would hang still in the air there, so it goes then (see
 * {@link StoppedMembers}). When the crossing ends, the members still flying go; a flock with no member left ends at
 * once.
 */
public final class MigrationFlock implements MigrationRoute {
    // Formation radius grows with the flock; members keep this far apart at least.
    private static final double SPREAD_BASE = 6D, SPREAD_PER_ROOT = 3D, SEPARATION_RADIUS = 5D, SEPARATION_PUSH = 1.5D;
    private static final double BOB_HEIGHT = 1.5D, BOB_PERIOD_TICKS = 160D, HEIGHT_SPREAD = 5D;
    // A member may fly this much faster than the anchor to take or keep its slot.
    private static final double CATCH_UP = 1.8D;
    // The ground under the route is read every SAMPLE_STEP blocks; the lowest member, at the bottom of its bob, still
    // clears the highest of it by MIN_CLEARANCE.
    private static final double SAMPLE_STEP = 4D, MIN_CLEARANCE = 2D;
    // A chunk's width in blocks.
    private static final double CHUNK = 16D;

    private record Slot(double x, double y, double z, double phase) {
    }

    /** The top of the ground at a block column, or Integer.MIN_VALUE where it is not loaded. */
    @FunctionalInterface
    interface Ground {
        int height(int x, int z);
    }

    private final World world;
    private final Vector heading;
    private final Vector anchorStart;
    private final double speed;
    private final double altitude;
    private final int durationTicks;
    private final Map<PetControl, Slot> members = new IdentityHashMap<>();
    private final StoppedMembers<PetControl> stopped;
    private BukkitTask task;
    private int age;
    private boolean done;
    // Whether this flock has said that a member could not be removed: once is enough.
    private boolean warned;
    // Scratch position for target(), read on the main thread only.
    private final Location probe = new Location(null, 0D, 0D, 0D);

    private MigrationFlock(World world, Vector heading, Vector anchorStart, double speed, double altitude, int durationTicks) {
        this.world = world;
        this.heading = heading;
        this.anchorStart = anchorStart;
        this.speed = speed;
        this.altitude = altitude;
        this.durationTicks = durationTicks;
        this.stopped = new StoppedMembers<>(patience(speed));
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
        // Halfway through the flight the anchor passes over the player. It starts where its members still tick and
        // can be seen: inside the simulation distance and the view distance.
        double simulated = Math.max(32D, (world.getSimulationDistance() - 1) * 16D);
        double length = settings.speed() * settings.durationTicks();
        double approach = Math.min(length / 2D, Math.min(simulated * 0.8D, settings.viewDistance() * 0.9D));
        Vector start = over.toVector().subtract(heading.clone().multiply(approach));
        int size = settings.minFlockSize() + random.nextInt(settings.maxFlockSize() - settings.minFlockSize() + 1);
        double spread = SPREAD_BASE + SPREAD_PER_ROOT * Math.sqrt(size);
        // The flock flies one height for its whole crossing, so it clears the highest ground anywhere under it, not
        // just under the player: a hill where it starts would otherwise swallow members.
        int ground = routeGround(start, heading, length, spread + SEPARATION_RADIUS, (x, z) -> world.isChunkLoaded(x >> 4, z >> 4)
                ? world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING) : Integer.MIN_VALUE);
        double height = Math.min(world.getMaxHeight() - 4D, Math.max(over.getY(), ground)
                + Math.max(settings.altitude(), HEIGHT_SPREAD / 2D + BOB_HEIGHT + MIN_CLEARANCE));
        start.setY(height);
        MigrationFlock flock = new MigrationFlock(world, heading, start, settings.speed(), height, settings.durationTicks());
        // A rare flock: one unusual colour for every member. Otherwise each member picks its own, like a wild spawn.
        String model = look.model();
        if (model == null && random.nextDouble() < settings.rareFlockChance()) model = species.pickRareVariant(random);
        for (int index = 0; index < size; index++) {
            double around = random.nextDouble() * Math.PI * 2D, out = Math.sqrt(random.nextDouble()) * spread;
            Slot slot = new Slot(Math.cos(around) * out, (random.nextDouble() - 0.5D) * HEIGHT_SPREAD, Math.sin(around) * out,
                    random.nextDouble() * Math.PI * 2D);
            Location at = new Location(world, start.getX() + slot.x(), height + slot.y(), start.getZ() + slot.z());
            if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4) || !at.getBlock().isPassable()) continue;
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

    /**
     * The highest ground under a route: the strip from the start along the heading for the length, reaching
     * halfWidth past it on every side, as far as the formation around its anchor reaches. Columns the ground doesn't
     * have loaded don't count, so looking never generates terrain. Integer.MIN_VALUE when none is loaded.
     */
    static int routeGround(Vector start, Vector heading, double length, double halfWidth, Ground ground) {
        Vector side = new Vector(-heading.getZ(), 0D, heading.getX());
        int highest = Integer.MIN_VALUE;
        int alongSteps = (int) Math.ceil((length + 2D * halfWidth) / SAMPLE_STEP);
        int acrossSteps = (int) Math.ceil(2D * halfWidth / SAMPLE_STEP);
        for (int a = 0; a <= alongSteps; a++) {
            double along = Math.min(length + halfWidth, -halfWidth + a * SAMPLE_STEP);
            for (int c = 0; c <= acrossSteps; c++) {
                double across = Math.min(halfWidth, -halfWidth + c * SAMPLE_STEP);
                int x = (int) Math.floor(start.getX() + heading.getX() * along + side.getX() * across);
                int z = (int) Math.floor(start.getZ() + heading.getZ() * along + side.getZ() * across);
                highest = Math.max(highest, ground.height(x, z));
            }
        }
        return highest;
    }

    /**
     * How many ticks in a row a member's body may go without ticking before the flock takes it out: the time a player
     * keeping pace with the flock takes to cross one chunk. A member that stops at the edge of the ticking chunks
     * waits there for a player to open the next ones, and a player moving with the flock opens them one chunk's
     * crossing apart, sooner on foot or faster. A member that has waited longer has nobody keeping up with it. At the
     * default 0.15 blocks a tick that is 107 ticks, a little over five seconds.
     */
    static int patience(double speed) {
        return Math.max(1, (int) Math.ceil(CHUNK / speed));
    }

    private void tick() {
        if (done) return;
        age++;
        if (age > durationTicks || world.getPlayers().isEmpty()) {
            remove();
            return;
        }
        PetManager manager = PetManager.get();
        if (manager == null) {
            remove();
            return;
        }
        // Tamed, killed or lost members leave the flock. One whose body has stopped ticking goes out of the world, as
        // the members still flying do when the crossing ends.
        for (PetControl member : stopped.sweep(members.keySet(), gone -> !flies(manager, gone), manager::bodyTicks))
            dismiss(manager, member);
        if (members.isEmpty()) remove();
    }

    /** Whether this pet is still the wild pet behind its body: not tamed, killed or lost. */
    private static boolean flies(PetManager manager, PetControl member) {
        return member.body() != null && manager.pet(member.body()) == member;
    }

    /** Takes a member that still flies out of the world: its body, its model and its wild pet. */
    private void dismiss(PetManager manager, PetControl member) {
        if (!flies(manager, member)) return;
        // One member that cannot go must not keep the rest of the flock in the sky.
        try {
            manager.despawn(member);
        } catch (RuntimeException failure) {
            if (warned) return;
            warned = true;
            Logger.warn("A " + member.species().id() + " migration could not remove a member of its flock: " + failure);
            failure.printStackTrace();
        }
    }

    @Override
    public Location target(PetControl pet) {
        Slot slot = done ? null : members.get(pet);
        LivingEntity body = pet.body();
        if (slot == null || body == null) return null;
        double anchorX = anchorStart.getX() + heading.getX() * speed * age;
        double anchorZ = anchorStart.getZ() + heading.getZ() * speed * age;
        double bob = Math.sin(age * Math.PI * 2D / BOB_PERIOD_TICKS + slot.phase()) * BOB_HEIGHT;
        double targetX = anchorX + slot.x(), targetY = altitude + slot.y() + bob, targetZ = anchorZ + slot.z();
        // Shoulder away from any member closer than the separation radius, so the slots never bunch up. Positions are
        // read into one reused Location, with the same arithmetic the Vector form did.
        Location probe = body.getLocation(this.probe);
        double hereX = probe.getX(), hereY = probe.getY(), hereZ = probe.getZ();
        for (PetControl other : members.keySet()) {
            LivingEntity otherBody = other.body();
            if (other == pet || otherBody == null) continue;
            otherBody.getLocation(probe);
            double awayX = hereX - probe.getX(), awayY = hereY - probe.getY(), awayZ = hereZ - probe.getZ();
            double distance = Math.sqrt(awayX * awayX + awayY * awayY + awayZ * awayZ);
            if (distance > 1.0E-3D && distance < SEPARATION_RADIUS) {
                double push = (SEPARATION_RADIUS - distance) / distance * SEPARATION_PUSH / SEPARATION_RADIUS;
                targetX += awayX * push;
                targetY += awayY * push;
                targetZ += awayZ * push;
            }
        }
        return new Location(world, targetX, targetY, targetZ);
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
        if (manager != null) for (PetControl member : new ArrayList<>(members.keySet())) dismiss(manager, member);
        members.clear();
    }
}
