package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.pets.PetControl;
import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindControl;
import com.magmaguy.magmacore.ai.MindStopReason;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Campfire gathering: a settled pet drifts to the nearest lit campfire and circles slowly in its smoke column, the
 * plain drift with the fire for its centre. Wild pets look within the radius; owned pets only take a fire close
 * enough to their owner that circling it never sets off the follow. The fire search runs every few seconds through
 * the shared {@link CampfireIndex}.
 */
final class CampfireBehavior implements MindBehavior {
    static final int PRIORITY = 15;
    private final PetControl control;
    private final PetInteractions.Campfire settings;
    private Block fire;
    private Location point;
    private double angle;
    private long nextSearchTick;
    private long nextCheckTick;
    private long nextPointTick;

    CampfireBehavior(PetControl control, PetInteractions.Campfire settings) {
        this.control = control;
        this.settings = settings;
    }

    @Override
    public String identifier() {
        return "freeminecraftmodels:pet/campfire";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Set<MindControl> controls() {
        return PetSteering.MOVE_AND_LOOK;
    }

    private boolean timeAllows(World world) {
        return !settings.nightOnly() || PetSky.isNight(world);
    }

    @Override
    public boolean canStart(MindContext context) {
        LivingEntity body = context.entity();
        if (context.gameTick() < nextSearchTick || !PetSteering.settled(control, body) || !timeAllows(body.getWorld()))
            return false;
        nextSearchTick = context.gameTick() + settings.searchTicks();
        fire = CampfireIndex.nearest(body.getLocation(), settings.radius(), settings.searchTicks(), this::usable);
        return fire != null;
    }

    @Override
    public boolean canContinue(MindContext context) {
        LivingEntity body = context.entity();
        if (fire == null || fire.getWorld() != body.getWorld() || !PetSteering.settled(control, body)
                || !timeAllows(body.getWorld())) return false;
        if (context.gameTick() < nextCheckTick) return true;
        nextCheckTick = context.gameTick() + 20L;
        return usable(fire);
    }

    /** A lit fire of a kind this pet's variant likes, close enough to its owner when it has one. */
    private boolean usable(Block block) {
        if (!settings.accepts(block.getType(), control.model())) return false;
        if (!(block.getBlockData() instanceof Lightable lightable) || !lightable.isLit()) return false;
        if (control.isWild()) return true;
        Player owner = control.owner().orElse(null);
        if (owner == null || owner.getWorld() != block.getWorld()) return false;
        Location above = block.getLocation().add(0.5D, settings.height(), 0.5D);
        return owner.getLocation().distance(above) <= Math.max(1D, control.species().followDistance() - 1D);
    }

    private Location ringPoint() {
        double wobble = (ThreadLocalRandom.current().nextDouble() - 0.5D) * 0.6D;
        return fire.getLocation().add(0.5D + Math.cos(angle) * settings.ring(), settings.height() + wobble,
                0.5D + Math.sin(angle) * settings.ring());
    }

    @Override
    public void start(MindContext context) {
        angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2D;
        point = ringPoint();
        nextPointTick = context.gameTick() + 60L;
        nextCheckTick = context.gameTick() + 20L;
    }

    @Override
    public void tick(MindContext context) {
        if (fire == null) return;
        if (point == null || context.gameTick() >= nextPointTick || context.entity().getLocation().distanceSquared(point) < 0.09D) {
            // Round the column a little at a time, as moths go round a lamp.
            angle += 0.5D + ThreadLocalRandom.current().nextDouble() * 0.7D;
            point = ringPoint();
            nextPointTick = context.gameTick() + 60L + ThreadLocalRandom.current().nextInt(40);
        }
        PetSteering.steer(context, point, PetSteering.DRIFT_SPEED * control.species().speed());
    }

    @Override
    public void stop(MindContext context, MindStopReason reason) {
        fire = null;
        point = null;
    }
}
