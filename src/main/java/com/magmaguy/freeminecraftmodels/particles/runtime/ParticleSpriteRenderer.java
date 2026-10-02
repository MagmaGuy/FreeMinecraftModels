package com.magmaguy.freeminecraftmodels.particles.runtime;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.freeminecraftmodels.particles.ParticleSprites;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint.FacingMode;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Draws one emitter's particles for Java players as packet-only item displays. The display's
 * billboard orientation is applied before its transformation, so movement goes through the
 * entity position (viewers glide over {@link #UPDATE_INTERVAL_TICKS}) while spin and size use
 * the transformation. Updates are sent at most every interval and only when something changed.
 */
public final class ParticleSpriteRenderer {
    /** Creates a packet item display at a location; production uses the NMS adapter. */
    @FunctionalInterface
    public interface SpriteFactory {
        PacketModelEntity create(Location location);
    }

    public static final int UPDATE_INTERVAL_TICKS = 2;
    private static final Map<UUID, AtomicInteger> LIVE_BY_VIEWER = new ConcurrentHashMap<>();

    private final ParticleSprites sprites;
    private final SpriteFactory factory;
    private final int maxPerViewer;
    private final FacingMode facing;
    private final Display.Billboard billboard;
    private final boolean lit;
    private final Map<Integer, Rendered> rendered = new HashMap<>();
    private final Vector3d position = new Vector3d();

    private static final class Rendered {
        PacketModelEntity sprite;
        final Set<UUID> viewers = new HashSet<>();
        final Vector3d position = new Vector3d();
        final Quaternionf orientation = new Quaternionf();
        double width, height, rotation;
        int spriteIndex, color, lastUpdate;
    }

    public ParticleSpriteRenderer(ParticleSprites sprites, ParticleEffectBlueprint effect, SpriteFactory factory,
                                  int maxPerViewer) {
        this.sprites = sprites;
        this.factory = factory;
        this.maxPerViewer = maxPerViewer;
        this.facing = effect.particle().billboard().facing();
        this.billboard = switch (facing) {
            case ROTATE_XYZ, LOOKAT_XYZ, LOOKAT_DIRECTION -> Display.Billboard.CENTER;
            case ROTATE_Y, LOOKAT_Y -> Display.Billboard.VERTICAL;
            default -> Display.Billboard.FIXED;
        };
        this.lit = effect.particle().lit();
    }

    /** Forgets every viewer's live-particle count, for tests and plugin shutdown. */
    public static void resetViewerCounts() {
        LIVE_BY_VIEWER.clear();
    }

    public boolean isEmpty() {
        return rendered.isEmpty();
    }

    public void render(ParticleEmitter emitter, Vector3d emitterPosition, Quaterniond emitterRotation, World world,
                       Collection<UUID> viewers, AbstractPacketBundle bundle, int tick) {
        for (SimulatedParticle expired : emitter.drainExpired()) {
            Rendered entry = rendered.remove(expired.id());
            if (entry != null) discard(entry);
        }
        for (SimulatedParticle particle : emitter.particles()) {
            particle.worldPosition(emitterPosition, emitterRotation, position);
            Rendered entry = rendered.get(particle.id());
            if (entry == null) {
                spawn(particle, emitterRotation, world, viewers, tick);
            } else if (entry.sprite != null && tick - entry.lastUpdate >= UPDATE_INTERVAL_TICKS) {
                update(entry, particle, emitterRotation, world, bundle, tick);
            }
        }
    }

    private void spawn(SimulatedParticle particle, Quaterniond emitterRotation, World world, Collection<UUID> viewers,
                       int tick) {
        Rendered entry = new Rendered();
        rendered.put(particle.id(), entry);
        List<UUID> eligible = new ArrayList<>();
        for (UUID viewer : viewers) {
            AtomicInteger live = LIVE_BY_VIEWER.get(viewer);
            if (live == null || live.get() < maxPerViewer) eligible.add(viewer);
        }
        // A particle nobody may see stays unrendered for its whole life.
        if (eligible.isEmpty()) return;

        Location location = new Location(world, position.x, position.y, position.z);
        PacketModelEntity sprite = factory.create(location);
        entry.sprite = sprite;
        entry.position.set(position);
        entry.spriteIndex = sprites.spriteIndex(particle.uvU(), particle.uvV(), particle.frame());
        entry.color = particle.color();
        entry.width = particle.width();
        entry.height = particle.height();
        entry.rotation = particle.rotationDegrees();
        entry.orientation.set(orientation(particle, emitterRotation));
        entry.lastUpdate = tick;

        String model = sprites.itemModel(entry.spriteIndex);
        sprite.initializeModel(location, model);
        sprite.setItemModel(model, Color.fromRGB(entry.color & 0xFFFFFF));
        sprite.setBillboard(billboard);
        if (!lit) sprite.setBrightness(15, 15);
        sprite.setTeleportDuration(UPDATE_INTERVAL_TICKS);
        sprite.setTransformation(new Vector3f(), new Quaternionf(entry.orientation), scale(particle), 0);
        for (UUID viewer : eligible) {
            sprite.displayTo(viewer);
            entry.viewers.add(viewer);
            LIVE_BY_VIEWER.computeIfAbsent(viewer, ignored -> new AtomicInteger()).incrementAndGet();
        }
    }

    private void update(Rendered entry, SimulatedParticle particle, Quaterniond emitterRotation, World world,
                        AbstractPacketBundle bundle, int tick) {
        boolean changed = false;
        if (entry.position.distanceSquared(position) > 1e-6) {
            entry.position.set(position);
            entry.sprite.teleport(new Location(world, position.x, position.y, position.z), bundle);
            changed = true;
        }

        boolean metadata = false;
        Quaternionf orientation = orientation(particle, emitterRotation);
        double spin = Math.abs(particle.rotationDegrees() - entry.rotation);
        if (Math.abs(particle.width() - entry.width) > 1e-4 || Math.abs(particle.height() - entry.height) > 1e-4
                || spin > 0.01 || !orientation.equals(entry.orientation, 1e-4f)) {
            entry.width = particle.width();
            entry.height = particle.height();
            entry.rotation = particle.rotationDegrees();
            entry.orientation.set(orientation);
            // Viewers slerp the shortest way, so a step of half a turn or more snaps instead.
            entry.sprite.setTransformation(new Vector3f(), orientation, scale(particle),
                    spin >= 170 ? 0 : UPDATE_INTERVAL_TICKS);
            metadata = true;
        }

        int spriteIndex = sprites.spriteIndex(particle.uvU(), particle.uvV(), particle.frame());
        if (spriteIndex != entry.spriteIndex || (particle.color() & 0xFFFFFF) != (entry.color & 0xFFFFFF)) {
            entry.spriteIndex = spriteIndex;
            entry.color = particle.color();
            entry.sprite.setItemModel(sprites.itemModel(spriteIndex), Color.fromRGB(entry.color & 0xFFFFFF));
            metadata = true;
        }

        if (metadata) entry.sprite.queueMetadataUpdate(bundle);
        if (changed || metadata) entry.lastUpdate = tick;
    }

    private Quaternionf orientation(SimulatedParticle particle, Quaterniond emitterRotation) {
        Quaternionf base = switch (facing) {
            case DIRECTION_X -> towards(new Vector3f(1, 0, 0), particle.facingDirection());
            case DIRECTION_Y -> towards(new Vector3f(0, 1, 0), particle.facingDirection());
            case DIRECTION_Z -> towards(new Vector3f(0, 0, 1), particle.facingDirection());
            case EMITTER_TRANSFORM_XY -> new Quaternionf(emitterRotation);
            case EMITTER_TRANSFORM_XZ -> new Quaternionf(emitterRotation).rotateX((float) Math.toRadians(-90));
            case EMITTER_TRANSFORM_YZ -> new Quaternionf(emitterRotation).rotateY((float) Math.toRadians(90));
            default -> new Quaternionf();
        };
        return base.rotateZ((float) Math.toRadians(particle.rotationDegrees()));
    }

    private static Quaternionf towards(Vector3f axis, Vector3d direction) {
        Vector3f target = new Vector3f((float) direction.x, (float) direction.y, (float) direction.z);
        if (target.lengthSquared() < 1e-8f) return new Quaternionf();
        return new Quaternionf().rotationTo(axis, target.normalize());
    }

    private static Vector3f scale(SimulatedParticle particle) {
        // Snowstorm sizes are half-extents and the sprite quad is one block wide.
        return new Vector3f((float) (particle.width() * 2), (float) (particle.height() * 2), 1);
    }

    /** Hides every particle from a viewer that stopped seeing the owner. */
    public void hideFrom(UUID viewer) {
        for (Rendered entry : rendered.values()) {
            if (entry.sprite == null || !entry.viewers.remove(viewer)) continue;
            entry.sprite.hideFrom(viewer);
            release(viewer);
        }
    }

    /** Removes every particle from every viewer. */
    public void removeAll() {
        for (Rendered entry : rendered.values()) discard(entry);
        rendered.clear();
    }

    private void discard(Rendered entry) {
        if (entry.sprite == null) return;
        entry.sprite.remove();
        for (UUID viewer : entry.viewers) release(viewer);
        entry.viewers.clear();
    }

    private static void release(UUID viewer) {
        LIVE_BY_VIEWER.computeIfPresent(viewer, (ignored, live) -> live.decrementAndGet() <= 0 ? null : live);
    }
}
