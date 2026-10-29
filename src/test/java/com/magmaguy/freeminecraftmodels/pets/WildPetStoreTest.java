package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wild pets wait in the store by chunk, travel in a chunk's persistent data as lines, and the old file is read once. */
class WildPetStoreTest {
    private static final UUID OVERWORLD = UUID.fromString("6f6c8e9a-1b5d-4a6e-9a51-3c2b7d0e4f11");
    private static final UUID NETHER = UUID.fromString("0a3e2d1c-5b4f-4e6d-8c7b-9a8f7e6d5c4b");

    private static WildPetStore.Entry jellyfish(double x, double z) {
        return new WildPetStore.Entry("jellyfish", "em_pet_jellyfish_blue", false, x, 70.25D, z, x + 2D, 68D, z - 1D);
    }

    @Test
    void aPetWaitsInTheChunkHoldingItsPlace() {
        WildPetStore store = new WildPetStore();
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
        WildPetStore store = new WildPetStore();
        store.put(OVERWORLD, jellyfish(3D, 4D));
        store.put(OVERWORLD, jellyfish(5D, 6D));
        assertEquals(List.of(jellyfish(3D, 4D), jellyfish(5D, 6D)), store.take(OVERWORLD, 0, 0));
        assertEquals(List.of(), store.take(OVERWORLD, 0, 0));
        assertEquals(0, store.size());
    }

    @Test
    void anEntryRoundTripsThroughItsLine() {
        WildPetStore.Entry entry = new WildPetStore.Entry("jellyfish", "em_pet_jellyfish_baby", true, -120.5D, 64D, 33.25D,
                -118D, 63.5D, 30D, 7.5D, 1793300000000L, 1.25D, 0.875D, 1.5D, 0.9D);
        assertEquals("jellyfish;em_pet_jellyfish_baby;true;-120.50;64.00;33.25;-118.00;63.50;30.00;7.50;1793300000000;"
                + "1.2500;0.8750;1.5000;0.9000", entry.encode());
        assertEquals(entry, WildPetStore.Entry.decode(entry.encode()));
        assertEquals(1.5D, entry.stats().sting);
    }

    @Test
    void aChunksEntriesRoundTripThroughItsPersistentData() {
        List<WildPetStore.Entry> entries = List.of(jellyfish(1D, 2D), jellyfish(3D, 4D));
        assertEquals(entries, WildPetStore.decodeAll(WildPetStore.encodeAll(entries)));
        assertEquals(List.of(), WildPetStore.decodeAll(null));
        assertEquals(List.of(jellyfish(1D, 2D)), WildPetStore.decodeAll("not an entry\n" + jellyfish(1D, 2D).encode()));
    }

    @Test
    void anEntryFromBeforeStatsRollsThem() {
        assertNull(jellyfish(1D, 2D).stats());
        assertNull(WildPetStore.Entry.decode("jellyfish;m;false;1;2;3;4;5;6;7.5;0").stats());
    }

    @Test
    void aLineFromBeforeHealthAndBirthsReadsAsFullAndUnborn() {
        assertEquals(new WildPetStore.Entry("jellyfish", "em_pet_jellyfish_baby", true, -120.5D, 64D, 33.25D, -118D, 63.5D,
                        30D, 0D, 0L, 0D, 0D, 0D, 0D),
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
    void theOldFileIsReadByWorld(@TempDir File folder) throws java.io.IOException {
        File file = new File(folder, "wild_pets.yml");
        java.nio.file.Files.writeString(file.toPath(), OVERWORLD + ":\n  -3,56:\n  - " + jellyfish(-40D, 900D).encode() + "\n"
                + NETHER + ":\n  0,0:\n  - " + jellyfish(10D, 10D).encode() + "\n");
        Map<UUID, List<WildPetStore.Entry>> legacy = WildPetStore.readLegacy(file);
        assertEquals(List.of(jellyfish(-40D, 900D)), legacy.get(OVERWORLD));
        assertEquals(List.of(jellyfish(10D, 10D)), legacy.get(NETHER));
        assertEquals(Map.of(), WildPetStore.readLegacy(new File(folder, "missing.yml")));
    }
}
