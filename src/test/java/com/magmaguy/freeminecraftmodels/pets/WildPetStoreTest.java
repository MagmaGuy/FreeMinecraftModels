package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wild pets wait in the store by chunk while it is unloaded, and the store survives a restart through its file. */
class WildPetStoreTest {
    private static final UUID OVERWORLD = UUID.fromString("6f6c8e9a-1b5d-4a6e-9a51-3c2b7d0e4f11");
    private static final UUID NETHER = UUID.fromString("0a3e2d1c-5b4f-4e6d-8c7b-9a8f7e6d5c4b");

    private static WildPetStore.Entry jellyfish(double x, double z) {
        return new WildPetStore.Entry("jellyfish", "em_pet_jellyfish_blue", false, x, 70.25D, z, x + 2D, 68D, z - 1D);
    }

    @Test
    void aPetWaitsInTheChunkHoldingItsPlace() {
        WildPetStore store = new WildPetStore(null);
        store.put(OVERWORLD, jellyfish(-0.5D, 15.9D));
        store.put(OVERWORLD, jellyfish(-16D, 16D));
        assertTrue(store.has(OVERWORLD, -1, 0), "x -0.5 is in chunk -1");
        assertTrue(store.has(OVERWORLD, -1, 1), "x -16 is in chunk -1 and z 16 in chunk 1");
        assertFalse(store.has(OVERWORLD, 0, 0));
        assertFalse(store.has(NETHER, -1, 0), "each world keeps its own pets");
        assertEquals(Set.of(WildSpawner.chunkKey(-1, 0), WildSpawner.chunkKey(-1, 1)), store.chunks(OVERWORLD));
    }

    @Test
    void takingAChunkHandsItsPetsOverOnce() {
        WildPetStore store = new WildPetStore(null);
        store.put(OVERWORLD, jellyfish(3D, 4D));
        store.put(OVERWORLD, jellyfish(5D, 6D));
        assertEquals(List.of(jellyfish(3D, 4D), jellyfish(5D, 6D)), store.take(OVERWORLD, 0, 0));
        assertEquals(List.of(), store.take(OVERWORLD, 0, 0));
        assertEquals(0, store.size());
    }

    @Test
    void anEntryRoundTripsThroughItsLine() {
        WildPetStore.Entry entry = new WildPetStore.Entry("jellyfish", "em_pet_jellyfish_baby", true, -120.5D, 64D, 33.25D,
                -118D, 63.5D, 30D, 7.5D, 1793300000000L);
        assertEquals("jellyfish;em_pet_jellyfish_baby;true;-120.50;64.00;33.25;-118.00;63.50;30.00;7.50;1793300000000",
                entry.encode());
        assertEquals(entry, WildPetStore.Entry.decode(entry.encode()));
    }

    @Test
    void aLineFromBeforeHealthAndBirthsReadsAsFullAndUnborn() {
        assertEquals(new WildPetStore.Entry("jellyfish", "em_pet_jellyfish_baby", true, -120.5D, 64D, 33.25D, -118D, 63.5D,
                        30D, 0D, 0L),
                WildPetStore.Entry.decode("jellyfish;em_pet_jellyfish_baby;true;-120.50;64.00;33.25;-118.00;63.50;30.00"));
    }

    @Test
    void linesThatAreNotEntriesAreSkipped() {
        assertNull(WildPetStore.Entry.decode(""));
        assertNull(WildPetStore.Entry.decode("jellyfish;model;false;1;2;3"));
        assertNull(WildPetStore.Entry.decode("jellyfish;model;false;one;2;3;4;5;6"));
        assertNull(WildPetStore.Entry.decode(";model;false;1;2;3;4;5;6"));
    }

    @Test
    void theStoreComesBackFromItsFile(@TempDir File folder) {
        File file = new File(folder, "data/wild_pets.yml");
        WildPetStore store = new WildPetStore(file);
        store.put(OVERWORLD, jellyfish(-40D, 900D));
        store.put(NETHER, jellyfish(10D, 10D));
        store.save();
        assertTrue(file.isFile());
        assertFalse(new File(file.getParentFile(), "wild_pets.yml.tmp").exists(), "the temporary copy is moved into place");
        WildPetStore loaded = WildPetStore.load(file);
        assertEquals(2, loaded.size());
        assertEquals(List.of(jellyfish(-40D, 900D)), loaded.take(OVERWORLD, -3, 56));
        assertEquals(List.of(jellyfish(10D, 10D)), loaded.take(NETHER, 0, 0));
    }

    @Test
    void anUnchangedStoreWritesNothing(@TempDir File folder) {
        File file = new File(folder, "wild_pets.yml");
        new WildPetStore(file).save();
        assertFalse(file.exists());
    }
}
