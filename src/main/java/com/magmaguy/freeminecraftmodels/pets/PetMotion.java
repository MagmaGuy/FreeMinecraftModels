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
    /** How far from the owner a catch-up spot must be, so a walker or swimmer never lands on the owner's own block. */
    static final double CATCH_UP_CLEARANCE = 1D;

    private PetMotion() {
    }

    /**
     * A point near center, inside the species' hover band above the ground under it. A point in an unloaded chunk
     * comes back at center's height, unchecked.
     */
    public static Location hoverPoint(PetControl control, Location center, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble() * Math.PI * 2D;
        double distance = radius <= 0D ? 0D : random.nextDouble() * radius;
        Location point = center.clone().add(Math.cos(angle) * distance, 0D, Math.sin(angle) * distance);
        // Reading the ground there would load its chunk.
        if (!loaded(point)) return point;
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
     * cannot fly, or when target is in another world.
     */
    public static boolean steerFlight(MindContext context, Location target, double cruise) {
        LivingEntity body = context.entity();
        if (target.getWorld() != body.getWorld()) return false;
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
     * A spot to stand on within radius of center: the floor of a random column nearest center's height. The floor
     * block is at most 3 blocks above or 6 below center's block, so the spot is about 5 below to 4 above center's
     * feet. Columns are spread evenly over the area within radius. Null over liquid or a long drop, inside solid
     * ground, on a wall under a ceiling over center, or in an unloaded chunk.
     */
    public static Location groundPoint(Location center, double radius) {
        return groundPoint(center, radius, 0D);
    }

    /** As {@link #groundPoint(Location, double)}, with the spot at least minDistance across from center. */
    static Location groundPoint(Location center, double radius, double minDistance) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 4; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2D;
            double distance = spotDistance(radius, minDistance, random.nextDouble());
            Location point = center.clone().add(Math.cos(angle) * distance, 0D, Math.sin(angle) * distance);
            if (!loaded(point)) continue;
            World world = point.getWorld();
            int x = point.getBlockX(), z = point.getBlockZ(), y = point.getBlockY();
            if (!clear(center, x, z, minDistance)) continue;
            // Outward from center's height. In the open, fall to the first block below, so a roof or a ceiling above
            // never passes for the floor. Inside the ground, climb to the top of the slope; the climb counts only with
            // headroom over center up to the spot's head, since under a ceiling it went up a wall onto the roof.
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                while (y < point.getBlockY() + 3 && world.getBlockAt(x, y + 1, z).getType().isSolid()) y++;
                if (y > point.getBlockY() && !headroom(center, y + 2)) continue;
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

    /** Whether center's own column is passable from 2 blocks above its feet up to top; false when unloaded. */
    private static boolean headroom(Location center, int top) {
        if (!loaded(center)) return false;
        World world = center.getWorld();
        int x = center.getBlockX(), z = center.getBlockZ();
        for (int y = center.getBlockY() + 2; y <= top; y++) if (!world.getBlockAt(x, y, z).isPassable()) return false;
        return true;
    }

    /**
     * A spot in water within radius of center, between minDepth and maxDepth below the topmost water the column's
     * scan finds. The scan runs from 4 blocks above center's block to 12 below and skips solid blocks above center's
     * height, so water over a ceiling still counts; ground at or below that height ends the column. Columns are
     * spread evenly over the area within radius. Null when no water is found near center's height, or in an unloaded
     * chunk.
     */
    public static Location waterPoint(Location center, double radius, double minDepth, double maxDepth) {
        return waterPoint(center, radius, minDepth, maxDepth, 0D);
    }

    /** As {@link #waterPoint(Location, double, double, double)}, with the spot at least minDistance across from center. */
    static Location waterPoint(Location center, double radius, double minDepth, double maxDepth, double minDistance) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2D;
            double distance = spotDistance(radius, minDistance, random.nextDouble());
            Location point = center.clone().add(Math.cos(angle) * distance, 0D, Math.sin(angle) * distance);
            if (!loaded(point)) continue;
            World world = point.getWorld();
            int x = point.getBlockX(), z = point.getBlockZ();
            if (!clear(center, x, z, minDistance)) continue;
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
        Location point = at.clone().add(Math.sin(yaw) * back, 0D, -Math.cos(yaw) * back);
        point.setY(at.getY() + (control.species().locomotion() == MindBodyLocomotion.FLYING ? height : 0D));
        return point;
    }

    /**
     * Puts the body beside its owner: in its hover band when floating, on standing ground when walking, in water when
     * swimming, and on ground or else in water when amphibious, so it keeps up with an owner who swims or sails. A
     * floating body faces the owner's way; the others keep their own facing. False when the owner is away or in
     * another world, or when nowhere loaded near them fits; a world change despawns the pet and the watchdog brings
     * it back instead. False too when the spot found is not at least a block nearer the owner than the body already
     * is, so a catch-up never moves a pet away from its owner, nor hops it from one spot beside them to another. A
     * ground or water spot is at least {@link #CATCH_UP_CLEARANCE} across from the owner, never on their own block; a
     * hover point is drawn as {@link #hoverPoint} draws it.
     */
    public static boolean teleportNearOwner(PetControl control, LivingEntity body, double radius) {
        Player owner = control.owner().orElse(null);
        if (owner == null || owner.getWorld() != body.getWorld()) return false;
        Location at = owner.getLocation();
        MindBodyLocomotion locomotion = control.species().locomotion();
        // Water is looked for at least 6 blocks out, since an owner on a shore or a boat may have none closer.
        Location spot = restPoint(control, at, radius, Math.max(radius, 6D), CATCH_UP_CLEARANCE);
        if (spot == null || !loaded(spot) || !closesIn(at.distance(spot), at.distance(body.getLocation()))) return false;
        // Ground and water spots are built facing south, so they take the body's yaw; a hover point keeps the
        // owner's, as the Java follow did.
        if (locomotion != MindBodyLocomotion.FLYING) spot.setYaw(body.getLocation().getYaw());
        return body.teleport(spot);
    }

    /**
     * A spot to rest within radius of center in the body's own medium: in its hover band when it flies, on standing
     * ground when it walks or cannot move, in water when it swims, and on ground or else in water when it is
     * amphibious. A swimmer resting in water stays at center's height, so one told to stay deep keeps its depth;
     * otherwise its spot is 0.5 to 2 blocks under the surface. Null when nothing near center fits. A hover point in an
     * unloaded chunk comes back at center's height, unchecked, as {@link #hoverPoint} gives it; the others are null
     * there.
     */
    public static Location restPoint(PetControl control, Location center, double radius) {
        if (control.species().locomotion() == MindBodyLocomotion.AQUATIC) {
            Location level = levelWaterPoint(center, radius);
            if (level != null) return level;
        }
        return restPoint(control, center, radius, radius);
    }

    /**
     * A spot in water within radius of center at center's own height, or null when center's block is not water, its
     * chunk is unloaded, or a few tries find no water at that height.
     */
    static Location levelWaterPoint(Location center, double radius) {
        if (!loaded(center) || center.getBlock().getType() != Material.WATER) return null;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 4; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2D, distance = spotDistance(radius, 0D, random.nextDouble());
            Location point = center.clone().add(Math.cos(angle) * distance, 0D, Math.sin(angle) * distance);
            if (loaded(point) && point.getBlock().getType() == Material.WATER) return point;
        }
        return null;
    }

    /** As {@link #restPoint(PetControl, Location, double)}, but looking for water within waterRadius. */
    static Location restPoint(PetControl control, Location center, double radius, double waterRadius) {
        return restPoint(control, center, radius, waterRadius, 0D);
    }

    /** As above, with a ground or water spot at least minDistance across from center; a hover point ignores it. */
    static Location restPoint(PetControl control, Location center, double radius, double waterRadius, double minDistance) {
        return switch (control.species().locomotion()) {
            case FLYING -> hoverPoint(control, center, radius);
            case AQUATIC -> waterPoint(center, waterRadius, 0.5D, 2D, minDistance);
            case AMPHIBIOUS -> {
                Location ground = groundPoint(center, radius, minDistance);
                yield ground != null ? ground : waterPoint(center, waterRadius, 0.5D, 2D, minDistance);
            }
            default -> groundPoint(center, radius, minDistance);
        };
    }

    /**
     * How far from center a ground or water spot is drawn, for a uniform draw from 0 to 1: the square root of it,
     * scaled to lie between minDistance and radius, so the spots spread evenly over that area. A draw scaled directly
     * would put half of them within half the radius, a quarter of the area, crowding them toward center.
     */
    static double spotDistance(double radius, double minDistance, double draw) {
        double low = Math.max(0D, Math.min(minDistance, radius));
        return Math.sqrt(low * low + draw * (radius * radius - low * low));
    }

    /** Whether the middle of the block column at x, z is at least minDistance across from center. */
    static boolean clear(Location center, int x, int z, double minDistance) {
        double dx = x + 0.5D - center.getX(), dz = z + 0.5D - center.getZ();
        return dx * dx + dz * dz >= minDistance * minDistance;
    }

    /** Whether a catch-up spot this far from the owner is worth it for a body this far: at least a block nearer. */
    static boolean closesIn(double spotDistance, double bodyDistance) {
        return spotDistance <= bodyDistance - 1D;
    }
}
