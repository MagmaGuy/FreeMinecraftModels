package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import org.bukkit.util.Vector;

import java.util.Optional;

public class IdleState implements IAnimState {
    private final ModeledEntity entity;
    private final AnimationStateConfig cfg;
    private AnimationStateType requestedNext;

    public IdleState(ModeledEntity entity, AnimationStateConfig cfg) {
        this.entity = entity;
        this.cfg = cfg;
    }

    @Override
    public void enter() {
        cfg.getAnimation().resetCounter();
    }

    @Override
    public void update() {
        requestedNext = null;
        if (entity.getUnderlyingEntity() != null) {
            Vector velocity = entity.getUnderlyingEntity().getVelocity();
            // Ignore vertical motion so gravity alone cannot trigger walking.
            if (velocity.getX() != 0 || velocity.getZ() != 0) {
                requestedNext = AnimationStateType.WALK;
            }
        }
    }

    @Override
    public void exit() {
    }

    @Override
    public AnimationStateType getType() {
        return AnimationStateType.IDLE;
    }

    @Override
    public Optional<AnimationStateType> nextState() {
        return Optional.ofNullable(requestedNext);
    }

    @Override
    public Animation getAnimation() {
        return cfg.getAnimation();
    }

    @Override
    public boolean isLoop() {
        return cfg.isLoop();
    }
}
