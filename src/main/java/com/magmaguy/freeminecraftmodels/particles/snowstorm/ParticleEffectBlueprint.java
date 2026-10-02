package com.magmaguy.freeminecraftmodels.particles.snowstorm;

import com.magmaguy.freeminecraftmodels.particles.molang.MolangContext;
import com.magmaguy.freeminecraftmodels.particles.molang.MolangExpression;
import org.joml.Vector3d;

import java.util.List;
import java.util.Map;

/**
 * An immutable Snowstorm particle effect. Expressions are compiled once; runtime emitters
 * evaluate them against their own Molang contexts.
 */
public record ParticleEffectBlueprint(
        String identifier,
        String texturePath,
        String material,
        Map<String, Curve> curves,
        Emitter emitter,
        Particle particle,
        List<String> unsupportedComponents) {

    public record Emitter(MolangExpression creation,
                          MolangExpression perUpdate,
                          boolean localPosition,
                          boolean localRotation,
                          Rate rate,
                          Lifetime lifetime,
                          Shape shape) {
    }

    public sealed interface Rate {
        record Instant(MolangExpression count) implements Rate {
        }

        record Steady(MolangExpression rate, MolangExpression max) implements Rate {
        }

        record None() implements Rate {
        }
    }

    public sealed interface Lifetime {
        record Looping(MolangExpression active, MolangExpression sleep) implements Lifetime {
        }

        record Once(MolangExpression active) implements Lifetime {
        }

        record Expression(MolangExpression activation, MolangExpression expiration) implements Lifetime {
        }
    }

    public enum ShapeType {POINT, SPHERE, BOX, DISC}

    public enum DirectionMode {OUTWARDS, INWARDS, VECTOR}

    public record Shape(ShapeType type,
                        Vec3 offset,
                        MolangExpression radius,
                        Vec3 halfDimensions,
                        Vec3 planeNormal,
                        boolean surfaceOnly,
                        DirectionMode directionMode,
                        Vec3 direction) {
    }

    public record Vec3(MolangExpression x, MolangExpression y, MolangExpression z) {
        public static final Vec3 ZERO = new Vec3(MolangExpression.ZERO, MolangExpression.ZERO, MolangExpression.ZERO);

        public Vector3d evaluate(MolangContext context, Vector3d result) {
            return result.set(x.evaluate(context), y.evaluate(context), z.evaluate(context));
        }
    }

    public record Particle(MolangExpression perUpdate,
                           MolangExpression perRender,
                           MolangExpression linearSpeed,
                           Vec3 velocity,
                           boolean dynamicMotion,
                           Vec3 linearAcceleration,
                           MolangExpression linearDrag,
                           MolangExpression initialRotation,
                           MolangExpression rotationRate,
                           MolangExpression rotationAcceleration,
                           MolangExpression rotationDrag,
                           MolangExpression maxLifetime,
                           MolangExpression expiration,
                           Billboard billboard,
                           Tint tint,
                           boolean lit) {
    }

    public enum FacingMode {
        ROTATE_XYZ, ROTATE_Y, LOOKAT_XYZ, LOOKAT_Y, LOOKAT_DIRECTION,
        DIRECTION_X, DIRECTION_Y, DIRECTION_Z,
        EMITTER_TRANSFORM_XY, EMITTER_TRANSFORM_XZ, EMITTER_TRANSFORM_YZ
    }

    /**
     * @param region null when the billboard uses the whole texture
     */
    public record Billboard(MolangExpression width,
                            MolangExpression height,
                            FacingMode facing,
                            boolean customDirection,
                            double minSpeedThreshold,
                            Vec3 direction,
                            int textureWidth,
                            int textureHeight,
                            TextureRegion region) {
        public int frameCount() {
            return region == null ? 1 : region.frames();
        }
    }

    /**
     * Texture pixels a particle shows. {@code u}/{@code v} are Molang because files commonly pick
     * a random variant per particle; size and step are constant. A static UV is one frame.
     */
    public record TextureRegion(MolangExpression u,
                                MolangExpression v,
                                double width,
                                double height,
                                double stepU,
                                double stepV,
                                MolangExpression framesPerSecond,
                                int frames,
                                boolean stretchToLifetime,
                                boolean loop) {
    }

    public sealed interface Tint {
        record Static(int argb) implements Tint {
        }

        record Expression(MolangExpression red, MolangExpression green, MolangExpression blue,
                          MolangExpression alpha) implements Tint {
        }

        /** Stop positions are normalized to 0..1; the interpolant is divided by {@code range}. */
        record Gradient(List<Stop> stops, MolangExpression interpolant, double range) implements Tint {
        }
    }

    public record Stop(double position, int argb) {
    }

    public enum CurveType {LINEAR, CATMULL_ROM, BEZIER, BEZIER_CHAIN}

    public record ChainNode(double time, double leftValue, double rightValue, double leftSlope, double rightSlope) {
    }

    /** A Snowstorm curve, evaluated the way Blockbench's Wintersky preview evaluates it. */
    public record Curve(CurveType type, MolangExpression input, MolangExpression range,
                        double[] nodes, List<ChainNode> chain) {

        public double evaluate(MolangContext context) {
            double inputValue = input.evaluate(context);
            double rangeValue = type == CurveType.BEZIER_CHAIN ? 1 : range.evaluate(context);
            double position = inputValue / rangeValue;
            if (Double.isNaN(position) || Double.isInfinite(position)) position = 0;
            return switch (type) {
                case LINEAR -> linear(position);
                case CATMULL_ROM -> catmullRom(position);
                case BEZIER -> bezier(nodes.length > 0 ? nodes[0] : 0, at(1), at(2), at(3), position);
                case BEZIER_CHAIN -> bezierChain(position);
            };
        }

        private double at(int index) {
            return index < nodes.length ? nodes[index] : 0;
        }

        private double linear(double position) {
            if (nodes.length == 0) return 0;
            if (nodes.length == 1) return nodes[0];
            double scaled = Math.max(0, Math.min(1, position)) * (nodes.length - 1);
            int index = Math.min((int) Math.floor(scaled), nodes.length - 2);
            double blend = scaled - index;
            return nodes[index] + (nodes[index + 1] - nodes[index]) * blend;
        }

        private double catmullRom(double position) {
            if (nodes.length < 4) return linear(position);
            int segments = nodes.length - 3;
            double point = Math.max(0, Math.min(1, position)) * segments + 1;
            int index = Math.min((int) Math.floor(point), nodes.length - 2);
            double weight = point - index;
            double p0 = nodes[Math.max(0, index - 1)];
            double p1 = nodes[index];
            double p2 = nodes[Math.min(nodes.length - 1, index + 1)];
            double p3 = nodes[Math.min(nodes.length - 1, index + 2)];
            double v0 = (p2 - p0) * 0.5, v1 = (p3 - p1) * 0.5;
            double t2 = weight * weight, t3 = t2 * weight;
            return (2 * p1 - 2 * p2 + v0 + v1) * t3 + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * t2 + v0 * weight + p1;
        }

        private static double bezier(double p0, double p1, double p2, double p3, double t) {
            double k = 1 - t;
            return k * k * k * p0 + 3 * k * k * t * p1 + 3 * k * t * t * p2 + t * t * t * p3;
        }

        private double bezierChain(double position) {
            ChainNode before = null, after = null;
            for (ChainNode node : chain) {
                if (node.time() > position) {
                    after = node;
                    break;
                }
                before = node;
            }
            if (before == null) before = new ChainNode(0, 0, 0, 0, 0);
            if (after == null) after = new ChainNode(1, 0, 0, 0, 0);
            double span = after.time() - before.time();
            if (span <= 0) return before.rightValue();
            return bezier(before.rightValue(), before.rightValue() + before.rightSlope() / 3,
                    after.leftValue() - after.leftSlope() / 3, after.leftValue(), (position - before.time()) / span);
        }
    }
}
