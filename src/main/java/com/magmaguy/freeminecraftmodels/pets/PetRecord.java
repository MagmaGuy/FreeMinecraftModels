package com.magmaguy.freeminecraftmodels.pets;

import java.util.List;
import java.util.UUID;

/** One pet in a player's collection, as stored on the player. Plain fields so Gson can read and write it. */
public final class PetRecord {
    public String id;
    public String species;
    public String model;
    public boolean baby;
    /** Epoch milliseconds the pet was born or tamed; a baby grows up a species-defined time after it. */
    public long bornAt;
    public boolean equipped;
    /** Epoch milliseconds before which the pet cannot breed again. */
    public long breedCooldownUntil;
    public String name;
    /** The names its parents had when it was born; null for a pet that was tamed or given. */
    public List<String> parents;
    /** 1 for a tamed or given pet, one more than its older parent's for a bred one; 0 in records from before. */
    public int generation;
    /** Where it was told to stay, while it stays there; null while it follows its owner. */
    public StaySpot stay;
    /** Its health when last seen; 0 means full, as in records from before. */
    public double health;
    /** Its stats; null in records from before, which roll a wild pet's once on load. */
    public PetStats stats;
    /** How it joined: tamed, bred, mutated (bred, in a colour neither parent had) or given; null in records from before. */
    public String origin;
    /** Its parents as they were at its birth, each with their own parents under them; null unless bred since kept. */
    public List<Relative> family;
    /** The babies it has had, oldest first. */
    public List<Relative> babies;

    public static final String TAMED = "tamed", BRED = "bred", MUTATED = "mutated", GIVEN = "given";

    /** A relative as it was when the record took it down: its record id, name tag name if any, and colour. */
    public static final class Relative {
        public String id;
        public String name;
        public String model;
        /** Only on a parent: its own parents, without theirs. */
        public List<Relative> parents;

        public Relative() {
        }

        public Relative(String id, String name, String model, List<Relative> parents) {
            this.id = id;
            this.name = name;
            this.model = model;
            this.parents = parents;
        }
    }

    /** A place in a world, by the world's id, as Gson keeps it. */
    public static final class StaySpot {
        public String world;
        public double x, y, z;
        public float yaw;

        public StaySpot() {
        }

        public StaySpot(String world, double x, double y, double z, float yaw) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }
    }

    public PetRecord() {
    }

    public PetRecord(String species, String model, boolean baby, long bornAt) {
        this.id = UUID.randomUUID().toString();
        this.species = species;
        this.model = model;
        this.baby = baby;
        this.bornAt = bornAt;
        this.equipped = true;
        this.generation = 1;
    }

    public UUID uuid() {
        return UUID.fromString(id);
    }

    /** The generation to show: a record from before generations were kept counts as a first one. */
    public int generationOrFirst() {
        return Math.max(1, generation);
    }

    /** The generation of a baby of these two parents. */
    static int childGeneration(PetRecord mother, PetRecord father) {
        return Math.max(mother.generationOrFirst(), father.generationOrFirst()) + 1;
    }
}
