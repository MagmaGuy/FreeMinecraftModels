package com.magmaguy.freeminecraftmodels.particles.runtime;

import com.magmaguy.freeminecraftmodels.particles.molang.MolangContext;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * One live particle. Position is world space, or emitter-relative when the effect declares a
 * local-space position; {@link #worldPosition} resolves both.
 */
public final class SimulatedParticle {
    final int id;
    final MolangContext context;
    final Vector3d position = new Vector3d();
    final Vector3d speed = new Vector3d();
    final Vector3d acceleration = new Vector3d();
    final Vector3d facingDirection = new Vector3d(0, 1, 0);
    final boolean localPosition;
    final boolean localRotation;
    int ageTicks;
    int loopTicks;
    double lifetime;
    boolean expired;
    double initialRotation;
    double rotationRate;
    double rotation;
    double width;
    double height;
    int frame;
    double uvU;
    double uvV;
    int color = 0xFFFFFFFF;

    SimulatedParticle(int id, MolangContext context, boolean localPosition, boolean localRotation) {
        this.id = id;
        this.context = context;
        this.localPosition = localPosition;
        this.localRotation = localRotation;
    }

    public int id() {
        return id;
    }

    public Vector3d worldPosition(Vector3d emitterPosition, Quaterniond emitterRotation, Vector3d result) {
        result.set(position);
        if (!localPosition) return result;
        if (localRotation) emitterRotation.transform(result);
        return result.add(emitterPosition);
    }

    public boolean isExpired() {
        return expired;
    }

    public int ageTicks() {
        return ageTicks;
    }

    public double lifetime() {
        return lifetime;
    }

    public double rotationDegrees() {
        return rotation;
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    public int frame() {
        return frame;
    }

    public double uvU() {
        return uvU;
    }

    public double uvV() {
        return uvV;
    }

    public int color() {
        return color;
    }

    public Vector3d facingDirection() {
        return facingDirection;
    }

    /** Current velocity in blocks per second, in the same space as the position. */
    public Vector3d speed() {
        return speed;
    }

    public boolean isLocalPosition() {
        return localPosition;
    }
}
