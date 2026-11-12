package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a pet's hit counts as landed, when it is worth trying, and how long a pet gives up on what it cannot hurt. */
class PetStrikeTest {
    @Test
    void aHitWhoseDamageEventWentThroughLanded() {
        assertTrue(PetStrike.landed(true, false, 20D, 16D, 0D, 0D));
    }

    @Test
    void aHitThatOnlyTookAbsorptionLanded() {
        // Golden apple hearts take the hit: health as it was, absorption down.
        assertTrue(PetStrike.landed(false, false, 20D, 20D, 4D, 0.8D));
    }

    @Test
    void aHitThatKilledLanded() {
        assertTrue(PetStrike.landed(false, true, 2D, 0D, 0D, 0D));
    }

    @Test
    void aCancelledHitDidNotLand() {
        // FreeMinecraftModels' own cancel, a protection plugin or a victim that cannot be hurt: nothing changed.
        assertFalse(PetStrike.landed(false, false, 20D, 20D, 0D, 0D));
    }

    @Test
    void aVictimHealedSinceIsNoHit() {
        assertFalse(PetStrike.landed(false, false, 18D, 19D, 0D, 0D));
    }

    @Test
    void aVictimInTheFirstHalfOfItsHurtImmunityIsTriedLater() {
        // A hit leaves 20 ticks, and Minecraft ignores an equal hit while more than 10 are left.
        assertFalse(PetStrike.ready(20, 20));
        assertFalse(PetStrike.ready(11, 20));
        assertTrue(PetStrike.ready(10, 20));
        assertTrue(PetStrike.ready(0, 20));
    }

    @Test
    void anOwnersPetsLeaveAloneWhatTheyGaveUpOnUntilItsTimeIsUp() {
        PetTruce truce = new PetTruce();
        UUID owner = UUID.randomUUID(), creature = UUID.randomUUID();
        assertFalse(truce.spares(owner, creature, 100L));
        truce.spare(owner, creature, 400L);
        assertTrue(truce.spares(owner, creature, 100L));
        assertTrue(truce.spares(owner, creature, 399L));
        assertFalse(truce.spares(owner, creature, 400L));
        // Its time up, it is forgotten: a later look finds nothing either.
        assertFalse(truce.spares(owner, creature, 100L));
    }

    @Test
    void aTruceIsOneOwnersAndOneCreatures() {
        PetTruce truce = new PetTruce();
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), creature = UUID.randomUUID();
        truce.spare(owner, creature, 400L);
        assertFalse(truce.spares(other, creature, 100L));
        assertFalse(truce.spares(owner, UUID.randomUUID(), 100L));
        // A wild pet has no owner: its refused hits spare nothing and never fail.
        truce.spare(null, creature, 400L);
        assertFalse(truce.spares(null, creature, 100L));
        truce.forget(owner);
        assertFalse(truce.spares(owner, creature, 100L));
    }
}
