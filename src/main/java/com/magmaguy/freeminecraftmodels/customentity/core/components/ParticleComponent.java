package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.animation.Animation;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.LocatorBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.ParticleKeyframe;
import com.magmaguy.freeminecraftmodels.particles.ParticleSprites;
import com.magmaguy.freeminecraftmodels.particles.ResolvedParticleEffect;
import com.magmaguy.freeminecraftmodels.particles.molang.MolangParseException;
import com.magmaguy.freeminecraftmodels.particles.molang.MolangParser;
import com.magmaguy.freeminecraftmodels.particles.runtime.ParticleEmitter;
import com.magmaguy.freeminecraftmodels.particles.runtime.ParticleSpriteRenderer;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.*;

/**
 * Plays a model's Blockbench particle keyframes. Emitters and their live particles belong to the
 * model, so {@link ModeledEntity#remove()} (chunk unload, death, deleteall, reload) clears them
 * without any per-emitter checks. Ticks on the asynchronous model clock; viewer changes arrive
 * from the primary thread, hence the synchronization.
 */
public class ParticleComponent {
    private static final double TICK_SECONDS = 1 / 20.0;
    private static volatile ParticleSpriteRenderer.SpriteFactory spriteFactory =
            location -> NMSManager.getAdapter().createPacketDisplayEntity(location);

    private final ModeledEntity modeledEntity;
    private final List<ActiveEmitter> emitters = new ArrayList<>();
    private final Vector3d position = new Vector3d();
    private final Quaterniond rotation = new Quaterniond();
    private int tick;

    private record ActiveEmitter(String animation, int keyframe, ParticleEmitter emitter,
                                 ParticleSpriteRenderer renderer, LocatorBlueprint locator) {
    }

    public ParticleComponent(ModeledEntity modeledEntity) {
        this.modeledEntity = modeledEntity;
    }

    /** Replaces how particle sprites are created, for tests. */
    public static void setSpriteFactory(ParticleSpriteRenderer.SpriteFactory factory) {
        spriteFactory = factory;
    }

    /** Starts the keyframes on this frame. A keyframe that fires again restarts its own emitter. */
    public synchronized void onAnimationFrame(Animation animation, int frame) {
        List<ParticleKeyframe> keyframes = animation.getAnimationBlueprint().getParticleKeyframes();
        if (keyframes.isEmpty() || !DefaultConfig.particleEffectsEnabled) return;
        String animationName = animation.getAnimationBlueprint().getAnimationName();
        for (int index = 0; index < keyframes.size(); index++) {
            ParticleKeyframe keyframe = keyframes.get(index);
            if (keyframe.tick() == frame) start(animationName, index, keyframe);
        }
    }

    private void start(String animationName, int index, ParticleKeyframe keyframe) {
        for (ActiveEmitter active : emitters) {
            if (active.animation().equals(animationName) && active.keyframe() == index && !active.emitter().isFinished()) {
                active.emitter().start();
                return;
            }
        }
        FileModelConverter converter = modeledEntity.getFileModelConverter();
        if (converter == null) return;
        ResolvedParticleEffect effect = converter.getParticleEffects().get(keyframe.effect());
        ParticleSprites sprites = converter.getParticleSprites().get(keyframe.effect());
        if (effect == null || sprites == null) return;

        ParticleEmitter emitter = new ParticleEmitter(effect.blueprint(), new SplittableRandom(),
                DefaultConfig.maxParticlesPerEmitter);
        emitter.start();
        if (!keyframe.script().isBlank()) {
            try {
                emitter.runScript(MolangParser.parse(keyframe.script()));
            } catch (MolangParseException ignored) {
                // A broken pre-effect script leaves the effect's own defaults in place.
            }
        }
        LocatorBlueprint locator = converter.getSkeletonBlueprint().getLocatorByName(keyframe.locator());
        emitters.add(new ActiveEmitter(animationName, index, emitter,
                new ParticleSpriteRenderer(sprites, effect.blueprint(), spriteFactory, DefaultConfig.maxParticlesPerViewer),
                locator));
    }

    /** Looping emitters stop spawning with their animation; live particles finish their lifetime. */
    public synchronized void onAnimationStopped(Animation animation) {
        if (animation == null) return;
        String animationName = animation.getAnimationBlueprint().getAnimationName();
        for (ActiveEmitter active : emitters) {
            if (active.animation().equals(animationName) && active.emitter().isLooping()) active.emitter().stopSpawning();
        }
    }

    /**
     * Advances every emitter and draws it for the given Java viewers. With nobody watching the
     * emitters pause, so unwatched models cost nothing.
     */
    public synchronized void tick(AbstractPacketBundle bundle, Collection<UUID> javaViewers) {
        if (emitters.isEmpty() || javaViewers.isEmpty()) return;
        tick++;
        Iterator<ActiveEmitter> iterator = emitters.iterator();
        while (iterator.hasNext()) {
            ActiveEmitter active = iterator.next();
            modeledEntity.getSkeleton().locatorTransform(active.locator(), position, rotation);
            active.emitter().tick(position, rotation, TICK_SECONDS);
            active.renderer().render(active.emitter(), position, rotation, modeledEntity.getWorld(), javaViewers,
                    bundle, tick);
            if (active.emitter().isFinished() && active.renderer().isEmpty()) iterator.remove();
        }
    }

    public synchronized void hideFrom(UUID viewer) {
        for (ActiveEmitter active : emitters) active.renderer().hideFrom(viewer);
    }

    public synchronized void remove() {
        for (ActiveEmitter active : emitters) active.renderer().removeAll();
        emitters.clear();
    }

    public synchronized int activeEmitterCount() {
        return emitters.size();
    }
}
