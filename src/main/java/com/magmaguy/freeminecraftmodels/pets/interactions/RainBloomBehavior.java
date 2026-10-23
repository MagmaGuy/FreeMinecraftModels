package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.pets.PetControl;
import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindControl;
import com.magmaguy.magmacore.ai.MindStopReason;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The rain bloom's movement: while rain falls on the pet, drifts come more often, a little higher and quicker, round
 * the owner or the wild pet's home. It runs ahead of the plain drift, which only fills the pauses between bloom
 * drifts. The swelling itself is {@link RainBloom}.
 */
final class RainBloomBehavior implements MindBehavior {
    static final int PRIORITY = 19;
    private final PetControl control;
    private final PetInteractions.RainBloom settings;
    private final PetInteractionState state;
    private Location destination;
    private Vector lastPosition;
    private long nextDriftTick;
    private long stopAtTick;
    private int stuckTicks;

    RainBloomBehavior(PetControl control, PetInteractions.RainBloom settings, PetInteractionState state) {
        this.control = control;
        this.settings = settings;
        this.state = state;
    }

    @Override
    public String identifier() {
        return "freeminecraftmodels:pet/rain_bloom";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Set<MindControl> controls() {
        return PetSteering.MOVE_AND_LOOK;
    }

    private Location center(LivingEntity body) {
        if (control.isWild()) return control.home() != null ? control.home() : body.getLocation();
        Player owner = control.owner().orElse(null);
        return owner == null || owner.getWorld() != body.getWorld() ? null : owner.getLocation();
    }

    @Override
    public boolean canStart(MindContext context) {
        if (!state.raining || control.isStaying() || context.gameTick() < nextDriftTick) return false;
        Location center = center(context.entity());
        return center != null && center.getWorld() == context.entity().getWorld();
    }

    @Override
    public boolean canContinue(MindContext context) {
        if (destination == null || context.gameTick() >= stopAtTick || control.isStaying()) return false;
        if (context.entity().getLocation().distanceSquared(destination) < 0.25D) return false;
        return stuckTicks < 20;
    }

    @Override
    public void start(MindContext context) {
        Location center = center(context.entity());
        if (center == null) return;
        destination = PetSteering.hoverPoint(control, center, control.species().roamRadius()).add(0D, settings.rise(), 0D);
        if (!PetSteering.loaded(destination)) {
            destination = null;
            return;
        }
        stopAtTick = context.gameTick() + 120L;
        lastPosition = context.entity().getLocation().toVector();
        stuckTicks = 0;
    }

    @Override
    public void tick(MindContext context) {
        if (destination == null) return;
        Vector now = context.entity().getLocation().toVector();
        if (lastPosition != null && now.distanceSquared(lastPosition) < 1.0E-4D) stuckTicks++;
        else stuckTicks = 0;
        lastPosition = now;
        PetSteering.steer(context, destination, PetSteering.DRIFT_SPEED * control.species().speed() * settings.speedMultiplier());
    }

    @Override
    public void stop(MindContext context, MindStopReason reason) {
        destination = null;
        long pause = (long) ((60D + ThreadLocalRandom.current().nextInt(80)) / settings.driftFrequency());
        nextDriftTick = context.gameTick() + Math.max(10L, pause);
    }
}
