package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What /fmm petadmin march tells its sender, word for word, and that each request gets one answer. */
class MarchRequestsTest {
    private static final UUID PET = UUID.randomUUID(), OTHER_PET = UUID.randomUUID(), ADMIN = UUID.randomUUID();
    private static final long WAIT = MarchRequests.WAIT_TICKS;

    private final MarchRequests requests = new MarchRequests();

    private static String neverFree(UUID pet, String species) {
        return MarchRequests.notFree(species);
    }

    // ---- the words ---------------------------------------------------------------------------------------------

    @Test
    void theCommandSaysWhatTheMarchNeedsFromTheSpeciesOwnSetting() {
        // The shipped module's settings, as a pet file that changes none of them runs it.
        assertEquals("&aAsked a wild penguin to call a march. It calls one once it is free, with 4 of its kind within"
                        + " 12 blocks, itself included, and clear ground to set off over. You are told how it went"
                        + " within 20 seconds.",
                MarchRequests.asked("penguin", Map.of("radius", 12D, "min_group", 4D, "every_minutes", 8D)));
        // A pet file that asks for less, over a wider reach.
        assertEquals("&aAsked a wild penguin to call a march. It calls one once it is free, with 3 of its kind within"
                        + " 16.5 blocks, itself included, and clear ground to set off over. You are told how it went"
                        + " within 20 seconds.",
                MarchRequests.asked("penguin", Map.of("radius", 16.5D, "min_group", 3D)));
    }

    @Test
    void theCommandGivesTheNumbersAMarchCallWouldUse() {
        // march_call takes a group of 2 to 64 and a reach of up to 32 blocks, whatever the pet file says.
        assertEquals("&aAsked a wild penguin to call a march. It calls one once it is free, with 2 of its kind within"
                        + " 32 blocks, itself included, and clear ground to set off over. You are told how it went"
                        + " within 20 seconds.",
                MarchRequests.asked("penguin", Map.of("radius", 100D, "min_group", 1D)));
    }

    @Test
    void aMarchModuleWithoutThoseSettingsGetsNoNumbers() {
        // Someone's own march.lua.
        assertEquals("&aAsked a wild penguin to call a march. You are told how it went within 20 seconds.",
                MarchRequests.asked("penguin", Map.of("speed", 0.18D)));
    }

    @Test
    void aMarchThatStartedIsSaidWithItsSize() {
        assertEquals("&aThe wild penguin called a march: 6 are in line.", MarchRequests.started("penguin", 6));
    }

    @Test
    void tooFewNearSaysHowManyWereFoundAndHowManyAreNeeded() {
        assertEquals("&cNo march. The wild penguin found 2 of its kind within 12 blocks, itself included, and a march"
                        + " needs 4.",
                MarchRequests.tooFew("penguin", 2, 2, 4, 12D));
        assertEquals("&cNo march. The wild penguin found 1 of its kind within 12 blocks, itself included, and a march"
                        + " needs 4.",
                MarchRequests.tooFew("penguin", 1, 1, 4, 12D));
    }

    @Test
    void enoughNearButTooFewFreeSaysBoth() {
        assertEquals("&cNo march. The wild penguin found 5 of its kind within 12 blocks, itself included, but only 2"
                        + " were free to march, and a march needs 4. The others were busy or in another march.",
                MarchRequests.tooFew("penguin", 2, 5, 4, 12D));
    }

    @Test
    void noClearGroundIsSaid() {
        assertEquals("&cNo march. The wild penguin has no clear ground to set off over: every way from where it"
                        + " stands is shut within 6 blocks by a wall, a drop, water or leaves.",
                MarchRequests.noGround("penguin", 6D));
    }

    @Test
    void aPetThatNeverGotToCallIsSaid() {
        assertEquals("&cNo march. The wild penguin was not free to call one in 20 seconds: something that comes"
                        + " before a march held it, such as a fight or a fish on the ground.",
                MarchRequests.notFree("penguin"));
        assertEquals("&cNo new march. The wild penguin was on a march already for the whole 20 seconds.",
                MarchRequests.onAMarch("penguin"));
        assertEquals("&cNo march. The wild penguin that was asked is gone: it died, was tamed or its chunk unloaded.",
                MarchRequests.gone("penguin"));
    }

    @Test
    void noWildAdultNearNamesTheSpeciesAndWhatWasThere() {
        assertEquals("&cNo wild adult penguin with the march behavior is within 32 blocks.",
                MarchRequests.noneNear("penguin", 32, 0, 0, 0));
        assertEquals("&cNo wild adult pet with the march behavior is within 32 blocks.",
                MarchRequests.noneNear(null, 32, 0, 0, 0));
        assertEquals("&cNo wild adult penguin with the march behavior is within 32 blocks. The wild ones there:"
                        + " 2 babies, 1 on a migration, 3 without the march behavior.",
                MarchRequests.noneNear("penguin", 32, 2, 1, 3));
        assertEquals("&cNo wild adult pet with the march behavior is within 32 blocks. The wild ones there: 1 baby.",
                MarchRequests.noneNear(null, 32, 1, 0, 0));
    }

    // ---- one answer per request --------------------------------------------------------------------------------

    @Test
    void aRequestWaitsForItsPetUntilItsTimeIsUp() {
        requests.ask(PET, ADMIN, "penguin", 1000L);
        assertTrue(requests.waits(PET, 1000L));
        assertTrue(requests.waits(PET, 999L + WAIT));
        assertFalse(requests.waits(PET, 1000L + WAIT));
        assertFalse(requests.waits(OTHER_PET, 1000L));
    }

    @Test
    void aMarchThatStartsIsAnsweredThenAndNotAgain() {
        requests.ask(PET, ADMIN, "penguin", 1000L);
        MarchRequests.Answer answer = requests.started(PET, MarchRequests.started("penguin", 6));
        assertEquals(new MarchRequests.Answer(ADMIN, "&aThe wild penguin called a march: 6 are in line."), answer);
        assertFalse(requests.waits(PET, 1001L));
        assertEquals(List.of(), requests.runOut(1000L + WAIT, MarchRequestsTest::neverFree));
    }

    @Test
    void aCallThatFailsEveryHalfSecondIsAnsweredOnceWhenTheRequestRunsOut() {
        requests.ask(PET, ADMIN, "penguin", 1000L);
        // The march module asks again each time it is probed: 40 calls in 20 seconds.
        for (long tick = 1010L; tick < 1000L + WAIT; tick += 10L) {
            requests.refused(PET, MarchRequests.tooFew("penguin", 2, 2, 4, 12D));
            assertEquals(List.of(), requests.runOut(tick, MarchRequestsTest::neverFree), "tick " + tick);
        }
        assertEquals(List.of(new MarchRequests.Answer(ADMIN, MarchRequests.tooFew("penguin", 2, 2, 4, 12D))),
                requests.runOut(1000L + WAIT, MarchRequestsTest::neverFree));
        assertEquals(List.of(), requests.runOut(1001L + WAIT, MarchRequestsTest::neverFree));
    }

    @Test
    void theAnswerGivesTheLatestReason() {
        requests.ask(PET, ADMIN, "penguin", 1000L);
        requests.refused(PET, MarchRequests.tooFew("penguin", 2, 2, 4, 12D));
        requests.refused(PET, MarchRequests.noGround("penguin", 6D));
        assertEquals(List.of(new MarchRequests.Answer(ADMIN, MarchRequests.noGround("penguin", 6D))),
                requests.runOut(1000L + WAIT, MarchRequestsTest::neverFree));
    }

    @Test
    void aCallThatFailsAndThenWorksIsAnsweredWithTheMarch() {
        requests.ask(PET, ADMIN, "penguin", 1000L);
        requests.refused(PET, MarchRequests.tooFew("penguin", 3, 3, 4, 12D));
        assertEquals(new MarchRequests.Answer(ADMIN, MarchRequests.started("penguin", 4)),
                requests.started(PET, MarchRequests.started("penguin", 4)));
        assertEquals(List.of(), requests.runOut(1000L + WAIT, MarchRequestsTest::neverFree));
    }

    @Test
    void aPetThatNeverCalledIsAnsweredWithWhyWhenTheRequestRunsOut() {
        requests.ask(PET, ADMIN, "penguin", 1000L);
        assertEquals(List.of(), requests.runOut(999L + WAIT, MarchRequestsTest::neverFree));
        assertEquals(List.of(new MarchRequests.Answer(ADMIN, MarchRequests.notFree("penguin"))),
                requests.runOut(1000L + WAIT, MarchRequestsTest::neverFree));
    }

    @Test
    void askingTheSamePetAgainStartsTheWaitOverAndGivesOneAnswer() {
        requests.ask(PET, ADMIN, "penguin", 1000L);
        requests.refused(PET, MarchRequests.noGround("penguin", 6D));
        requests.ask(PET, ADMIN, "penguin", 1200L);
        assertEquals(List.of(), requests.runOut(1000L + WAIT, MarchRequestsTest::neverFree));
        // The first request's reason went with it.
        assertEquals(List.of(new MarchRequests.Answer(ADMIN, MarchRequests.notFree("penguin"))),
                requests.runOut(1200L + WAIT, MarchRequestsTest::neverFree));
    }

    @Test
    void eachPetAskedGetsItsOwnAnswer() {
        requests.ask(PET, ADMIN, "penguin", 1000L);
        requests.ask(OTHER_PET, ADMIN, "penguin", 1100L);
        assertEquals(1, requests.runOut(1000L + WAIT, MarchRequestsTest::neverFree).size());
        assertTrue(requests.waits(OTHER_PET, 1000L + WAIT));
        assertEquals(1, requests.runOut(1100L + WAIT, MarchRequestsTest::neverFree).size());
    }

    @Test
    void aRequestWithNobodyToTellIsKeptAndAnswersNobody() {
        // A plugin or a test asked through PetManager.requestMarch.
        requests.ask(PET, null, "penguin", 1000L);
        assertTrue(requests.waits(PET, 1000L));
        assertNull(requests.started(PET, MarchRequests.started("penguin", 4)));
        requests.ask(OTHER_PET, null, "penguin", 1000L);
        assertEquals(List.of(), requests.runOut(1000L + WAIT, MarchRequestsTest::neverFree));
        assertFalse(requests.waits(OTHER_PET, 1000L + WAIT));
    }

    @Test
    void aMarchNobodyAskedForAnswersNobody() {
        assertNull(requests.started(PET, MarchRequests.started("penguin", 4)));
        requests.refused(PET, MarchRequests.noGround("penguin", 6D));
        assertEquals(List.of(), requests.runOut(5000L, MarchRequestsTest::neverFree));
    }
}
