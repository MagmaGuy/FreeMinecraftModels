package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.pets.PetControl;
import org.bukkit.entity.LivingEntity;

/**
 * Night glow: lights the pet's model at a fixed level through FMM's display brightness override from dusk to dawn (or
 * always), and hands it back to world light by day. A model with the species' lantern bone also loops its glow
 * animation while it glows. Java viewers only: Bedrock bones keep their world light.
 */
final class NightGlow {
    private NightGlow() {
    }

    static void update(PetControl control, LivingEntity body, PetInteractions.Glow glow, PetInteractionState state) {
        DynamicEntity modeled = control.modeled();
        if (modeled == null || modeled.isRemoved()) return;
        boolean on = !glow.nightOnly() || PetSky.isNight(body.getWorld());
        if (on) {
            if (modeled.getBrightnessOverrideBlockLight() != glow.light()) modeled.setBrightnessOverride(glow.light(), glow.light());
            if (state.glowModel != modeled) {
                state.glowModel = modeled;
                state.glowAnimating = hasBone(modeled, glow.lanternBone()) && modeled.hasAnimation(glow.animation())
                        && modeled.playAnimation(glow.animation(), true, true);
            }
            return;
        }
        if (modeled.hasBrightnessOverride()) modeled.clearBrightnessOverride();
        if (state.glowModel == modeled) {
            if (state.glowAnimating) modeled.stopCurrentAnimations();
            state.glowModel = null;
            state.glowAnimating = false;
        }
    }

    private static boolean hasBone(DynamicEntity modeled, String name) {
        if (name == null || name.isBlank() || modeled.getSkeleton() == null) return false;
        for (Bone bone : modeled.getSkeleton().getBones())
            if (name.equalsIgnoreCase(bone.getBoneBlueprint().getOriginalBoneName())
                    || name.equalsIgnoreCase(bone.getBoneBlueprint().getBoneName())) return true;
        return false;
    }
}
