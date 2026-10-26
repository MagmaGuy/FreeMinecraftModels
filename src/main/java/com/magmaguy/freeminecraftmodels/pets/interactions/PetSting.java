package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitboxContactEvent;
import com.magmaguy.freeminecraftmodels.pets.PetControl;
import com.magmaguy.freeminecraftmodels.pets.PetManager;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Contact with a pet's model. No pet deals FMM's contact attack; a wild pet remembers the players whose touch reaches
 * its model, and the sting behavior module stings them, as it stings the hostile mobs that come close to an owned pet.
 */
public final class PetSting {
    private PetSting() {
    }

    /**
     * FreeMinecraftModels found a player touching a model. For a pet body this replaces FMM's contact attack: the
     * event is cancelled, and a wild pet remembers the touch of a player it may sting.
     */
    public static void onContact(ModeledEntityHitboxContactEvent event) {
        PetManager manager = PetManager.get();
        if (manager == null) return;
        Entity underlying = event.getEntity().getUnderlyingEntity();
        PetControl control = underlying == null ? null : manager.pet(underlying);
        if (control == null) return;
        event.setCancelled(true);
        if (!control.isWild()) return;
        Player player = event.getPlayer();
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.isDead()) return;
        // For a sting behavior module, which reads the touches since its last look.
        control.recordContact(player);
    }
}
