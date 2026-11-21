package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetColoniesTest {
    private static PetControl wildAt(double x, double z) {
        return new PetControl(null, null, null, "fmm_penguin", false, new Location(null, x, 64, z));
    }

    @Test
    void homesWithinTheRadiusOfOneAnotherThroughAnyChainAreOneColony() {
        List<PetControl> pets = new ArrayList<>();
        // A chain 10 blocks apart, each link within 12, across cell borders.
        for (int index = 0; index < 5; index++) pets.add(wildAt(index * 10D - 3D, 5D));
        // A pair far off, and one alone.
        pets.add(wildAt(200D, 200D));
        pets.add(wildAt(205D, 196D));
        pets.add(wildAt(-300D, 0D));
        List<List<PetControl>> groups = PetColonies.groups(pets, 12D);
        groups.sort(Comparator.comparingInt(List::size));
        assertEquals(3, groups.size());
        assertEquals(1, groups.get(0).size(), "the lone one");
        assertEquals(2, groups.get(1).size(), "the far pair");
        assertEquals(5, groups.get(2).size(), "the chain");
        // Homes 13 apart are two colonies.
        assertEquals(2, PetColonies.groups(List.of(wildAt(0D, 0D), wildAt(13D, 0D)), 12D).size());
    }

    @Test
    void theShoreByDayBeforeDuskAndHomeAtDuskAndNight() {
        long dusk = 10500L;
        assertTrue(PetColonies.shoreTime(true, 0L, dusk), "morning");
        assertTrue(PetColonies.shoreTime(true, 6000L, dusk), "noon");
        assertTrue(PetColonies.shoreTime(true, 23900L, dusk), "dawn, before 0");
        assertFalse(PetColonies.shoreTime(true, 10500L, dusk), "dusk");
        assertFalse(PetColonies.shoreTime(true, 12000L, dusk), "sunset, still day by PetSky");
        assertFalse(PetColonies.shoreTime(false, 18000L, dusk), "night");
        assertEquals(PetColonies.Place.SHORE, PetColonies.wanted(true, true));
        assertEquals(PetColonies.Place.HOME, PetColonies.wanted(true, false), "no water: home by day");
        assertEquals(PetColonies.Place.HOME, PetColonies.wanted(false, true));
    }
}
