package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Movement maths shared by the pet behaviors: hover, standing and swimming spots, the ground under a point, and one
 * tick of steering. A floating body steers by velocity, since a flying mob's navigation crawls; a walking or
 * swimming body paths there natively. Speeds are blocks per tick.
 */
public final class PetMotion {
    private PetMotion() {
    }

    /** A point near center, inside the species' hover band above the ground under it. */
    public static Location hoverPoint(PetControl control, Location center, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble() * Math.PI * 2D;
        double distance = radius <= 0D ? 0D : random.nextDouble() * radius;
        Location point = center.clone().add(Math.cos(angle) * distance, 0D, Math.sin(angle) * distance);
        double ground = groundBelow(point, 12, floatsOnLiquid(control));
        double height = control.species().hoverHeight() + (random.nextDouble() * 2D - 1D) * control.species().hoverVariance();
        point.setY(Math.max(ground + Math.max(0.5D, height), center.getY() - 3D));
        return point;
    }

    /**
     * The top of the highest solid block under the point, searched a bounded number of blocks down. With
     * liquidIsGround a water or lava surface counts too, so a flyer hovers over the sea instead of sinking to the
     * seabed; swimmers keep the seabed as their floor.
     */
    public static double groundBelow(Location point, int depth, boolean liquidIsGround) {
        World world = point.getWorld();
        int x = point.getBlockX(), z = point.getBlockZ();
        for (int y = point.getBlockY(); y > point.getBlockY() - depth && y > world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType().isSolid() || (liquidIsGround && block.isLiquid())) return y + 1D;
        }
        return point.getY() - depth;
    }

    /** Whether the species hovers over liquid surfaces: everything that does not swim. */
    public static boolean floatsOnLiquid(PetControl control) {
        return control.species().locomotion() != MindBodyLocomotion.AQUATIC;
    }

    public static boolean loaded(Location location) {
        return location.getWorld() != null && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    /** Free to wander: not told to stay, and wild or within the follow distance of its owner. */
    public static boolean settled(PetControl control, LivingEntity body) {
        if (control.isStaying()) return false;
        return control.isWild() || control.ownerDistance(body) <= control.species().followDistance();
    }

    /**
     * One tick of flight toward target: accelerate to the cruise speed, ease off over the last few blocks, and hold
     * still on arrival. The body control turns the model toward the travel direction on its own. False when the body
     * cannot fly.
     */
    public static boolean steerFlight(MindContext context, Location target, double cruise) {
        LivingEntity body = context.entity();
        Vector to = target.toVector().subtract(body.getLocation().toVector());
        double distance = to.length();
        Vector current = body.getVelocity();
        Vector wanted = distance < 0.05D ? new Vector() : to.multiply(Math.min(cruise, distance * 0.25D) / distance);
        Vector change = wanted.subtract(current);
        double acceleration = Math.max(cruise * 0.15D, 0.005D);
        if (change.length() > acceleration) change.multiply(acceleration / change.length());
        return context.actuator().steerFlight(current.add(change));
    }

    /**
     * A spot to stand on within radius of center: the floor of a random column nearest center's height, at most 3
     * blocks above or 6 below it. Null over liquid or a long drop, inside solid ground, or in an unloaded chunk.
     */
    public static Location groundPoint(Location center, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 4; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2D, distance = random.nextDouble() * radius;
            Location point = center.clone().add(Math.cos(angle) * distance, 0D, Math.sin(angle) * distance);
            if (!loaded(point)) continue;
            World world = point.getWorld();
            int x = point.getBlockX(), z = point.getBlockZ(), y = point.getBlockY();
            // Outward from center's height, so a roof or a ceiling never passes for the floor: inside the ground,
            // climb to the top of the slope; in the open, fall to the first block below.
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                while (y < point.getBlockY() + 3 && world.getBlockAt(x, y + 1, z).getType().isSolid()) y++;
            } else {
                while (y > point.getBlockY() - 6 && y > world.getMinHeight() + 1 && open(world.getBlockAt(x, y, z))) y--;
            }
            Block block = world.getBlockAt(x, y, z);
            // Liquid, a long drop, or the bottom of the world.
            if (!block.getType().isSolid()) continue;
            Block feet = block.getRelative(0, 1, 0), head = block.getRelative(0, 2, 0);
            if (feet.isPassable() && !feet.isLiquid() && head.isPassable()) return new Location(world, x + 0.5D, y + 1D, z + 0.5D);
        }
        return null;
    }

    private static boolean open(Block block) {
        return !block.getType().isSolid() && !block.isLiquid();
    }

    /**
     * A spot in water within radius of center, between minDepth and maxDepth below the water's surface in its
     * column. Null when no water is found near center's height, or in an unloaded chunk.
     */
    public static Location waterPoint(Location center, double radius, double minDepth, double maxDepth) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2D, distance = random.nextDouble() * radius;
            Location point = center.clone().add(Math.cos(angle) * distance, 0D, Math.sin(angle) * distance);
            if (!loaded(point)) continue;
            World world = point.getWorld();
            int x = point.getBlockX(), z = point.getBlockZ();
            for (int y = point.getBlockY() + 4; y >= point.getBlockY() - 12 && y > world.getMinHeight(); y--) {
                Material type = world.getBlockAt(x, y, z).getType();
                // Ground at or below center's height makes the column land; water under it is a sealed pocket.
                if (y <= point.getBlockY() && type.isSolid()) break;
                if (type != Material.WATER) continue;
                double depth = minDepth + random.nextDouble() * Math.max(0D, maxDepth - minDepth);
                Location spot = new Location(world, x + 0.5D, y + 0.9D - depth, z + 0.5D);
                if (spot.getBlock().getType() == Material.WATER) return spot;
                break;
            }
        }
        return null;
    }

    /** Behind the owner: back blocks behind where they face, height above their feet when floating, at them otherwise. */
    public static Location trailPoint(PetControl control, Player owner, double back, double height) {
        Location at = owner.getLocation();
        // The facing from the yaw alone, so an owner looking straight up or down still has a behind.
        double yaw = Math.toRadians(at.getYaw());
        Location point = at.clone().subtract(-Math.sin(yaw) * back, 0D, Math.cos(yaw) * back);
        point.setY(at.getY() + (control.species().locomotion() == MindBodyLocomotion.FLYING ? height : 0D));
        return point;
    }

    /**
     * Puts the body beside its owner: in its hover band when floating, on standing ground when walking, in water when
     * swimming. False when the owner is away, in another world or in an unloaded chunk, or when nowhere near them
     * fits; a world change despawns the pet and the watchdog brings it back instead.
     */
    public static boolean teleportNearOwner(PetControl control, LivingEntity body, double radius) {
        Player owner = control.owner().orElse(null);
        if (owner == null || owner.getWorld() != body.getWorld()) return false;
        Location at = owner.getLocation();
        // A hover point reads the ground under it before any loaded check could stop it.
        if (!loaded(at)) return false;
        Location spot = switch (control.species().locomotion()) {
            case FLYING -> hoverPoint(control, at, radius);
            case AQUATIC -> waterPoint(at, Math.max(radius, 6D), 0.5D, 2D);
            default -> groundPoint(at, radius);
        };
        return spot != null && loaded(spot) && body.teleport(spot);
    }
}
