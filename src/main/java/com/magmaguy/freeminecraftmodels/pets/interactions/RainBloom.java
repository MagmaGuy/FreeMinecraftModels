package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.pets.PetControl;
import com.magmaguy.freeminecraftmodels.pets.PetSpecies;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;

/**
 * Rain bloom: notes whether rain falls on the pet, and eases the body's scale attribute toward its rain size while it
 * does and back after, over the configured time. The model reads the body's scale every tick, so it swells with it.
 * {@link RainBloomBehavior} reads the same rain flag for its quicker drifts.
 */
final class RainBloom {
    private RainBloom() {
    }

    static void update(PetControl control, LivingEntity body, PetInteractions.RainBloom bloom, PetInteractionState state,
                       long ticks, int passTicks) {
        if (ticks >= state.nextRainCheck) {
            state.nextRainCheck = ticks + 10L;
            state.raining = PetSky.isRainingOn(body.getLocation());
        }
        PetSpecies species = control.species();
        double base = species.scale() * (control.isBaby() ? species.babyScale() : 1D);
        double target = state.raining ? base * bloom.scaleMultiplier() : base;
        if (Double.isNaN(state.scale)) state.scale = base;
        if (state.scale == target) return;
        double step = Math.abs(base * (bloom.scaleMultiplier() - 1D)) * passTicks / bloom.scaleTicks();
        state.scale = state.scale < target ? Math.min(target, state.scale + step) : Math.max(target, state.scale - step);
        AttributeInstance scale = body.getAttribute(Attribute.SCALE);
        if (scale != null) scale.setBaseValue(state.scale);
    }
}
