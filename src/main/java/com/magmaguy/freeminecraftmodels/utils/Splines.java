package com.magmaguy.freeminecraftmodels.utils;

/**
 * The curve kernels Blockbench evaluates with three.js, shared by animation keyframes and Snowstorm particle curves.
 */
public final class Splines {
    private Splines() {
    }

    /**
     * A uniform Catmull-Rom segment from {@code p1} to {@code p2}, shaped by the points on either side (three.js
     * {@code CatmullRom}). At t = 0 it returns p1 and at t = 1 it returns p2.
     */
    public static double catmullRom(double t, double p0, double p1, double p2, double p3) {
        double v0 = (p2 - p0) * 0.5, v1 = (p3 - p1) * 0.5;
        double t2 = t * t, t3 = t2 * t;
        return (2 * p1 - 2 * p2 + v0 + v1) * t3 + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * t2 + v0 * t + p1;
    }

    /** A cubic Bezier from {@code p0} to {@code p3} with control points {@code p1} and {@code p2}, at t in 0..1. */
    public static double cubicBezier(double p0, double p1, double p2, double p3, double t) {
        double k = 1 - t;
        return k * k * k * p0 + 3 * k * k * t * p1 + 3 * k * t * t * p2 + t * t * t * p3;
    }
}
