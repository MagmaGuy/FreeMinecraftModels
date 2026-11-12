package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pet manager's decisions that need no server: its health display and how its warnings name a species' program. */
class PetManagerTest {
    /** A chunk's persistent data holding at most one text, counting the writes that mark the chunk unsaved. */
    private static final class CountingData implements java.lang.reflect.InvocationHandler {
        String stored;
        int sets, removes;

        org.bukkit.persistence.PersistentDataContainer container() {
            return (org.bukkit.persistence.PersistentDataContainer) java.lang.reflect.Proxy.newProxyInstance(
                    org.bukkit.persistence.PersistentDataContainer.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.persistence.PersistentDataContainer.class}, this);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "has" -> stored != null;
                case "get" -> stored;
                case "set" -> {
                    sets++;
                    stored = (String) args[2];
                    yield null;
                }
                case "remove" -> {
                    removes++;
                    stored = null;
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    @Test
    void aChunksDataIsWrittenOnlyWhenItsWaitingPetsChange() {
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey("freeminecraftmodels", "wild_pets");
        WildPetStore.Entry one = new WildPetStore.Entry("jellyfish", "em_pet_jellyfish_blue", false, 3D, 70D, 4D, 5D, 68D, 3D);
        WildPetStore.Entry two = new WildPetStore.Entry("jellyfish", "em_pet_jellyfish_gold", false, 6D, 70D, 7D, 8D, 68D, 6D);
        CountingData data = new CountingData();
        // The server counts a chunk as unsaved after any set or remove, so an empty chunk must see neither.
        PetManager.writeChunk(data.container(), key, List.of());
        assertEquals(0, data.sets + data.removes, "a chunk that never held pets is not touched");
        PetManager.writeChunk(data.container(), key, List.of(one));
        assertEquals(1, data.sets, "a first pet is written");
        assertEquals(WildPetStore.encodeAll(List.of(one)), data.stored);
        PetManager.writeChunk(data.container(), key, List.of(one));
        PetManager.writeChunk(data.container(), key, List.of(one));
        assertEquals(1, data.sets, "an unchanged list is not written again");
        PetManager.writeChunk(data.container(), key, List.of(one, two));
        assertEquals(2, data.sets, "a changed list is");
        PetManager.writeChunk(data.container(), key, List.of());
        assertEquals(1, data.removes, "the last pet leaving clears the data once");
        PetManager.writeChunk(data.container(), key, List.of());
        assertEquals(1, data.removes, "and a cleared chunk is not touched again");
        assertEquals(2, data.sets);
    }

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
