package com.magmaguy.freeminecraftmodels.pets;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A pet record keeps its stay order and health through the JSON a player's data holds, as PetCollection writes it. */
class PetRecordPersistenceTest {
    private static final Gson GSON = new Gson();

    @Test
    void aStayOrderAndHealthComeBack() {
        PetRecord record = new PetRecord("driftjelly", "fmm_driftjelly_frostveil", false, 1793300000000L);
        record.stay = new PetRecord.StaySpot("6f2c1a8e-0000-4000-8000-000000000001", 120.5D, 71.25D, -33D, 90F);
        record.health = 7.5D;
        PetRecord back = GSON.fromJson(GSON.toJson(record), PetRecord.class);
        assertEquals("6f2c1a8e-0000-4000-8000-000000000001", back.stay.world);
        assertEquals(120.5D, back.stay.x);
        assertEquals(71.25D, back.stay.y);
        assertEquals(-33D, back.stay.z);
        assertEquals(90F, back.stay.yaw);
        assertEquals(7.5D, back.health);
    }

    @Test
    void aRecordFromBeforeFollowsAtFullHealth() {
        PetRecord old = GSON.fromJson("{\"id\":\"0b6f6f2e-0000-4000-8000-000000000002\",\"species\":\"driftjelly\","
                + "\"model\":\"fmm_driftjelly_tidesurge\",\"baby\":false,\"bornAt\":1793300000000,\"equipped\":true,"
                + "\"breedCooldownUntil\":0,\"generation\":1}", PetRecord.class);
        assertNull(old.stay);
        assertEquals(0D, old.health);
    }
}
