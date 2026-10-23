package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitboxContactEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityLeftClickEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityRightClickEvent;
import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import com.magmaguy.freeminecraftmodels.pets.interactions.PetSting;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;

/** Routes clicks, damage, deaths and player sessions to {@link PetManager}. */
public final class PetListeners implements Listener {
    private static Player attacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event instanceof PlayerInteractAtEntityEvent)) click(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        click(event);
    }

    /**
     * A right-click on a pet body. Whatever the manager makes of it, the vanilla mob underneath never gets the click:
     * an allay would pocket the food it was offered. A lead still goes through.
     */
    private static void click(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        PetManager manager = PetManager.get();
        if (manager == null) return;
        PetControl pet = manager.pet(event.getRightClicked());
        if (pet == null) return;
        boolean consumed = manager.interact(event.getPlayer(), pet);
        if (consumed || event.getPlayer().getInventory().getItemInMainHand().getType() != Material.LEAD) event.setCancelled(true);
    }

    /** A right-click on the model itself, which hides the body from clients. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onModelRightClick(ModeledEntityRightClickEvent event) {
        PetManager manager = PetManager.get();
        if (manager == null) return;
        Entity body = event.getEntity().getUnderlyingEntity();
        PetControl pet = body == null ? null : manager.pet(body);
        if (pet != null && manager.interact(event.getPlayer(), pet)) event.setCancelled(true);
    }

    /**
     * A left-click on a pet that cannot be hurt never becomes an attack. Cancelling only the damage still let FMM
     * swing at the body and flash the model red, which looked like hitting it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onModelLeftClick(ModeledEntityLeftClickEvent event) {
        PetManager manager = PetManager.get();
        if (manager == null) return;
        Entity body = event.getEntity().getUnderlyingEntity();
        PetControl pet = body == null ? null : manager.pet(body);
        if (pet != null && protectedFrom(pet, event.getPlayer())) event.setCancelled(true);
    }

    /** Owned pets are safe from their owner, from everyone in COSMETIC mode, and from players when so configured. */
    private static boolean protectedFrom(PetControl pet, Player attacker) {
        if (pet.isWild()) return false;
        return PetsConfig.mode == PetsConfig.Mode.COSMETIC || pet.isOwnedBy(attacker) || PetsConfig.petsInvulnerableToPlayers;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        PetManager manager = PetManager.get();
        if (manager == null) return;
        PetControl pet = manager.pet(event.getEntity());
        if (pet == null || pet.isWild()) return;
        // Cosmetic pets cannot be hurt at all.
        if (PetsConfig.mode == PetsConfig.Mode.COSMETIC) {
            event.setCancelled(true);
            return;
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return;
        Player attacker = attacker(byEntity.getDamager());
        if (attacker != null && (pet.isOwnedBy(attacker) || PetsConfig.petsInvulnerableToPlayers)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTargeted(EntityTargetLivingEntityEvent event) {
        PetManager manager = PetManager.get();
        // Pets are no one's target and target no one.
        if (manager != null && (manager.pet(event.getEntity()) != null || manager.pet(event.getTarget()) != null))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        PetManager manager = PetManager.get();
        if (manager == null) return;
        PetControl pet = manager.pet(event.getEntity());
        if (pet == null) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        manager.bodyDied(pet);
    }

    /** A pet's model touched a player: wild pets sting, and no pet deals FMM's contact attack. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onModelContact(ModeledEntityHitboxContactEvent event) {
        PetSting.onContact(event);
    }

    /** Pet bodies are never saved, but a crash can leave one in a chunk; nothing would drive or show it. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        PetManager manager = PetManager.get();
        for (Entity entity : event.getEntities())
            if (PetManager.isPetBody(entity) && (manager == null || manager.pet(entity) == null)) entity.remove();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PetManager manager = PetManager.get();
        if (manager != null) manager.playerJoined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PetManager manager = PetManager.get();
        if (manager != null) manager.playerLeft(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // The watchdog brings equipped pets to the new world; bodies left behind go now.
        PetManager manager = PetManager.get();
        if (manager == null) return;
        for (PetControl control : new ArrayList<>(manager.pets()))
            if (control.isOwnedBy(event.getPlayer())) manager.despawn(control);
    }
}
