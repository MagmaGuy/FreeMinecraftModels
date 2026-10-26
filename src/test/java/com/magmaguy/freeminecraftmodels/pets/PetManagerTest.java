package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the pet manager's warnings name a species' program, which needs no server. */
class PetManagerTest {
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
