package com.magmaguy.freeminecraftmodels.pets;

import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * A pet's stats, each a factor of its species' base value: 1 is the base. health scales its max health, speed every
 * cruise its modules steer at, sting each sting's damage and poison how long each sting's effect lasts. Wild, given and
 * migrating pets roll each in {@link #WILD_MIN} to {@link #WILD_MAX}; a baby gets its parents' average, give or take
 * {@link #WOBBLE}, kept between {@link #FLOOR} and its species' cap, so careful breeding climbs. Plain fields for Gson,
 * as the player's pet records keep them.
 */
public final class PetStats {
    static final double WILD_MIN = 0.8D, WILD_MAX = 1.0D, FLOOR = 0.5D, WOBBLE = 0.05D;
    /** Babies use this share of their health, sting and poison until they grow up. */
    static final double BABY_SHARE = 0.5D;
    /** The size of a pet at the floor and at the cap; at the base it is 1. */
    static final double SMALLEST = 0.5D, LARGEST = 1.5D;

    public double health = 1D;
    public double speed = 1D;
    public double sting = 1D;
    public double poison = 1D;

    public PetStats() {
    }

    PetStats(double health, double speed, double sting, double poison) {
        this.health = health;
        this.speed = speed;
        this.sting = sting;
        this.poison = poison;
    }

    /** A wild pet's roll: each stat on its own, in WILD_MIN to WILD_MAX. */
    static PetStats wild(RandomGenerator random) {
        return new PetStats(roll(random), roll(random), roll(random), roll(random));
    }

    private static double roll(RandomGenerator random) {
        return WILD_MIN + random.nextDouble() * (WILD_MAX - WILD_MIN);
    }

    /** A baby's stats: each the parents' average, give or take WOBBLE, kept between FLOOR and cap. */
    static PetStats inherit(PetStats mother, PetStats father, double cap, RandomGenerator random) {
        PetStats mum = orBase(mother), dad = orBase(father);
        return new PetStats(child(mum.health, dad.health, cap, random), child(mum.speed, dad.speed, cap, random),
                child(mum.sting, dad.sting, cap, random), child(mum.poison, dad.poison, cap, random));
    }

    private static double child(double mother, double father, double cap, RandomGenerator random) {
        double wobble = (random.nextDouble() * 2D - 1D) * WOBBLE;
        return clamp((mother + father) / 2D + wobble, FLOOR, Math.max(FLOOR, cap));
    }

    /** Stats from before stats were kept count as the base. */
    static PetStats orBase(PetStats stats) {
        return stats == null ? new PetStats() : stats;
    }

    PetStats copy() {
        return new PetStats(health, speed, sting, poison);
    }

    double mean() {
        return (health + speed + sting + poison) / 4D;
    }

    /**
     * The size the stats give, as a multiplier on the species' scale: 1 at the base, SMALLEST at the floor and
     * LARGEST at the cap, in a straight line between them on each side.
     */
    static double size(PetStats stats, double cap) {
        double mean = orBase(stats).mean();
        if (mean >= 1D) {
            if (cap <= 1D) return 1D;
            return clamp(1D + (LARGEST - 1D) * (mean - 1D) / (cap - 1D), 1D, LARGEST);
        }
        return clamp(1D - (1D - SMALLEST) * (1D - mean) / (1D - FLOOR), SMALLEST, 1D);
    }

    /** A factor as the share of its base a passport shows, such as "127%". */
    static String percent(double factor) {
        return String.format(Locale.ROOT, "%d%%", Math.round(factor * 100D));
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
