package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The touches a wild pet keeps for its sting module: each for 20 pet clock ticks after its latest touch, at most 16
 * players at once. The contact bookkeeping needs no species, so the pet here has none.
 */
class PetControlContactsTest {
    private static final Function<UUID, UUID> ONLINE = Function.identity();
    private static final UUID ALEX = new UUID(0L, 1L), STEVE = new UUID(0L, 2L);

    private static PetControl pet() {
        return new PetControl(null, null, null, "em_pet_jellyfish", false, null);
    }

    @Test
    void aTouchWaitsTwentyTicks() {
        PetControl pet = pet();
        pet.recordContact(ALEX, 100L);
        assertEquals(List.of(ALEX), pet.drainContacts(120L, ONLINE));
        pet.recordContact(ALEX, 100L);
        assertEquals(List.of(), pet.drainContacts(121L, ONLINE));
    }

    @Test
    void takingTheTouchesClearsThem() {
        PetControl pet = pet();
        pet.recordContact(ALEX, 100L);
        assertEquals(List.of(ALEX), pet.drainContacts(102L, ONLINE));
        assertEquals(List.of(), pet.drainContacts(104L, ONLINE));
    }

    @Test
    void aLongTouchCountsOnceAndStaysFresh() {
        PetControl pet = pet();
        pet.recordContact(ALEX, 100L);
        pet.recordContact(ALEX, 115L);
        pet.recordContact(ALEX, 130L);
        assertEquals(List.of(ALEX), pet.drainContacts(150L, ONLINE));
    }

    @Test
    void offlinePlayersAreLeftOut() {
        PetControl pet = pet();
        pet.recordContact(ALEX, 100L);
        pet.recordContact(STEVE, 100L);
        assertEquals(List.of(STEVE), pet.drainContacts(101L, id -> id.equals(STEVE) ? id : null));
    }

    @Test
    void aFullPetMakesRoomOnlyByForgettingStaleTouches() {
        PetControl pet = pet();
        List<UUID> crowd = new ArrayList<>();
        for (long index = 0; index < 16; index++) {
            UUID player = new UUID(1L, index);
            crowd.add(player);
            pet.recordContact(player, 100L);
        }
        // Full of fresh touches: a newcomer waits for a later touch, while one already kept is refreshed.
        pet.recordContact(ALEX, 110L);
        pet.recordContact(crowd.getFirst(), 110L);
        // Past the others' 20 ticks, the newcomer takes a stale player's place.
        pet.recordContact(ALEX, 125L);
        assertEquals(Set.of(crowd.getFirst(), ALEX), Set.copyOf(pet.drainContacts(125L, ONLINE)));

        PetControl full = pet();
        for (UUID player : crowd) full.recordContact(player, 100L);
        full.recordContact(ALEX, 110L);
        assertEquals(crowd, full.drainContacts(110L, ONLINE));
    }
}
