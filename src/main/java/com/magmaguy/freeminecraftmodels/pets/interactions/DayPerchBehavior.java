package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.pets.PetControl;
import com.magmaguy.freeminecraftmodels.pets.PetSpecies;
import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindControl;
import com.magmaguy.magmacore.ai.MindStopReason;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Day perch: by day a settled pet comes down onto the nearest leaves under its hover band, or the ground right below
 * it, and idles there, a lower hover height for the day. It lifts off when this stops: at dusk, in the rain, or when
 * its owner walks away and the follow takes over.
 */
final class DayPerchBehavior implements MindBehavior {
    static final int PRIORITY = 18;
    private final PetControl control;
    private final PetInteractions.DayPerch settings;
    private final PetInteractionState state;
    private Location spot;
    private Location target;
    private long nextTryTick;
    private long nextBobTick;

    DayPerchBehavior(PetControl control, PetInteractions.DayPerch settings, PetInteractionState state) {
        this.control = control;
        this.settings = settings;
        this.state = state;
    }

    @Override
    public String identifier() {
        return "freeminecraftmodels:pet/day_perch";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Set<MindControl> controls() {
        return PetSteering.MOVE_AND_LOOK;
    }

    private boolean perchTime(LivingEntity body) {
        return !state.raining && PetSky.isDay(body.getWorld()) && PetSteering.settled(control, body);
    }

    @Override
    public boolean canStart(MindContext context) {
        LivingEntity body = context.entity();
        if (context.gameTick() < nextTryTick || !perchTime(body)) return false;
        spot = findPerch(body);
        // Nothing to sit on (water, a long drop): look again in a while instead of every tick.
        if (spot == null) nextTryTick = context.gameTick() + 100L;
        return spot != null;
    }

    @Override
    public boolean canContinue(MindContext context) {
        return spot != null && spot.getWorld() == context.entity().getWorld() && perchTime(context.entity());
    }

    @Override
    public void start(MindContext context) {
        target = spot;
    }

    @Override
    public void tick(MindContext context) {
        if (spot == null) return;
        double distance = context.entity().getLocation().distanceSquared(spot);
        if (distance < 0.04D && context.gameTick() >= nextBobTick) {
            // Settled: the barest breathing bob now and then.
            nextBobTick = context.gameTick() + 60L;
            target = spot.clone().add(0D, ThreadLocalRandom.current().nextDouble() * 0.08D, 0D);
        }
        double cruise = distance > 1D ? PetSteering.DRIFT_SPEED : PetSteering.SETTLE_SPEED;
        PetSteering.steer(context, target, cruise * control.species().speed());
    }

    @Override
    public void stop(MindContext context, MindStopReason reason) {
        spot = null;
        target = null;
        nextTryTick = context.gameTick() + 40L;
    }

    /** Leaves within the search radius under the hover band, nearest first, else the ground right below. */
    private Location findPerch(LivingEntity body) {
        Location from = body.getLocation();
        World world = from.getWorld();
        PetSpecies species = control.species();
        int depth = (int) Math.ceil(species.hoverHeight() + species.hoverVariance()) + 3;
        int radius = settings.searchRadius();
        // A model centred on its body hangs below the body's feet; lift the body so the model, not the body, rests
        // at the perch height.
        double sink = control.modeled() == null ? 0D : Math.max(0D, -control.modeled().getModelBottomHeight());
        Location leaves = null;
        double leavesScore = Double.MAX_VALUE;
        Location ground = null;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = from.getBlockX() + dx, z = from.getBlockZ() + dz;
                for (int y = from.getBlockY(); y >= from.getBlockY() - depth && y > world.getMinHeight(); y--) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (block.isLiquid()) break;
                    // Air, grass and flowers: look under them.
                    if (type.isAir() || block.isPassable()) continue;
                    if (Tag.LEAVES.isTagged(type)) {
                        double score = dx * dx + dz * dz + (from.getY() - y) * 0.5D;
                        Location top = new Location(world, x + 0.5D, y + 1D + settings.height() + sink, z + 0.5D);
                        if (score < leavesScore && withinLeash(top)) {
                            leavesScore = score;
                            leaves = top;
                        }
                    } else if (dx == 0 && dz == 0 && type.isSolid()) {
                        ground = new Location(world, from.getX(), y + 1D + settings.height() + sink, from.getZ());
                    }
                    break;
                }
            }
        }
        Location chosen = leaves != null ? leaves : ground;
        if (chosen == null || !PetSteering.loaded(chosen) || !withinLeash(chosen)) return null;
        return chosen;
    }

    private boolean withinLeash(Location spot) {
        if (control.isWild()) return true;
        Player owner = control.owner().orElse(null);
        return owner != null && owner.getWorld() == spot.getWorld()
                && owner.getLocation().distance(spot) <= control.species().followDistance();
    }
}
