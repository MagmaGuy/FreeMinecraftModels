package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pet manager's decisions that need no server: its health display and how its warnings name a species' program. */
class PetManagerTest {
    @Test
    void theHealthDisplayIsTheNameOverEliteMobsNumbersAndBars() {
        com.magmaguy.freeminecraftmodels.config.PetsConfig.healthBarSeparator = " &7/ ";
        List<String> named = PetManager.healthLines("Bubbles", 10D, 20D, 1D);
        assertEquals(3, named.size());
        assertEquals("Bubbles", named.get(0));
        assertEquals(com.magmaguy.magmacore.util.HealthBarText.numericLine(10D, 20D, " &7/ "), named.get(1));
        assertEquals(com.magmaguy.magmacore.util.HealthBarText.barLines(10D, 20D, 1D, false), named.subList(2, 3));
        // No name tag: no name line. A pet bred to double health gets two rows of bars, as a sturdier elite does.
        assertEquals(3, PetManager.healthLines(null, 30D, 40D, 2D).size());
    }

    @Test
    void warningsSayWhenAListIsTheLocomotionsDefault() {
        assertEquals("jellyfish.pet.yml", PetManager.where("jellyfish.pet.yml", true, MindBodyLocomotion.FLYING,
                "em_pet_jellyfish_gold", true));
        assertEquals("jellyfish.pet.yml (default behaviors for FLYING)",
                PetManager.where("jellyfish.pet.yml", false, MindBodyLocomotion.FLYING, "em_pet_jellyfish", false));
        assertEquals("fox.pet.yml (default behaviors for GROUNDED)",
                PetManager.where("fox.pet.yml", false, MindBodyLocomotion.GROUNDED, "fox", false));
        // A variant with a list of its own adds to the defaults; warnings about its entries say whose they are.
        assertEquals("jellyfish.pet.yml (default behaviors for FLYING, plus variant em_pet_jellyfish_gold's list)",
                PetManager.where("jellyfish.pet.yml", false, MindBodyLocomotion.FLYING, "em_pet_jellyfish_gold", true));
    }
}
