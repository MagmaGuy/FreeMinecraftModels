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
