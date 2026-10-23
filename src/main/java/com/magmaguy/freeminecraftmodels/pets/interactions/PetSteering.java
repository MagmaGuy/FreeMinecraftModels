package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.pets.PetControl;
import com.magmaguy.freeminecraftmodels.pets.PetSpecies;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindControl;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Movement shared by the interaction behaviors, matching the pet program's own steering. */
final class PetSteering {
    static final Set<MindControl> MOVE_AND_LOOK = EnumSet.of(MindControl.MOVE, MindControl.LOOK);
    // Blocks per tick at species speed 1, matching the plain drift and bob.
    static final double DRIFT_SPEED = 0.06D, SETTLE_SPEED = 0.03D;

    private PetSteering() {
    }

    /** Free to wander: not told to stay, and wild or within the follow distance of its owner. */
    static boolean settled(PetControl control, LivingEntity body) {
        if (control.isStaying()) return false;
        return control.isWild() || control.ownerDistance(body) <= control.species().followDistance();
    }

    static boolean loaded(Location location) {
        return location.getWorld() != null
                && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    /** One tick toward a point: accelerate to the cruise speed, ease off near it, hold still on arrival. */
    static void steer(MindContext context, Location target, double cruise) {
        LivingEntity body = context.entity();
        Vector to = target.toVector().subtract(body.getLocation().toVector());
        double distance = to.length();
        Vector current = body.getVelocity();
        Vector wanted = distance < 0.05D ? new Vector() : to.multiply(Math.min(cruise, distance * 0.25D) / distance);
        Vector change = wanted.subtract(current);
        double acceleration = Math.max(cruise * 0.15D, 0.005D);
        if (change.length() > acceleration) change.multiply(acceleration / change.length());
        context.actuator().steerFlight(current.add(change));
    }

    /** A point near {@code center}, inside the species' hover band above the ground under it. */
    static Location hoverPoint(PetControl control, Location center, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble() * Math.PI * 2D;
        double distance = radius <= 0D ? 0D : random.nextDouble() * radius;
        Location point = center.clone().add(Math.cos(angle) * distance, 0D, Math.sin(angle) * distance);
        PetSpecies species = control.species();
        double height = species.hoverHeight() + (random.nextDouble() * 2D - 1D) * species.hoverVariance();
        point.setY(Math.max(groundBelow(point, 12) + Math.max(0.5D, height), center.getY() - 3D));
        return point;
    }

    /** The top of the highest solid block under the point, searched a bounded number of blocks down. */
    static double groundBelow(Location point, int depth) {
        World world = point.getWorld();
        int x = point.getBlockX(), z = point.getBlockZ();
        for (int y = point.getBlockY(); y > point.getBlockY() - depth && y > world.getMinHeight(); y--)
            if (world.getBlockAt(x, y, z).getType().isSolid()) return y + 1D;
        return point.getY() - depth;
    }
}
