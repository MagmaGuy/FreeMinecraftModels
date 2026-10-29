package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitboxContactEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityLeftClickEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityRightClickEvent;
import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import com.magmaguy.freeminecraftmodels.pets.interactions.PetSting;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.UUID;

/** Routes clicks, damage, deaths, removals, new chunks, world saves and player sessions to {@link PetManager}. */
public final class PetListeners implements Listener {
    /**
     * Who an attack counts against: the creature that made it, or that shot the projectile, but the owner for an owned
     * pet's attack. Null when no creature is behind it, as for a dispenser's arrow.
     */
    private static UUID aggressor(PetManager manager, Entity damager) {
        Entity source = damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter
                ? shooter : damager;
        PetControl pet = manager.pet(source);
        if (pet != null && !pet.isWild()) return pet.ownerId();
        return source instanceof LivingEntity ? source.getUniqueId() : null;
    }

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

    /**
     * Owned pets are safe from everyone in COSMETIC mode, and from players, their owner included, when so configured.
     * Otherwise they are mortal, like tamed wolves.
     */
    private static boolean protectedFrom(PetControl pet, Player attacker) {
        if (pet.isWild()) return false;
        return PetsConfig.mode == PetsConfig.Mode.COSMETIC || PetsConfig.petsInvulnerableToPlayers;
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
        if (attacker != null && PetsConfig.petsInvulnerableToPlayers) event.setCancelled(true);
    }

    /**
     * An attack that went through on a player, or on an owned pet: the victim's owner's pets defend against the
     * attacker for a while, as wolves do. That is a player, a mob or a wild pet, or the shooter of a projectile. An
     * owned pet's own sting counts as its owner attacking, so a fight between two players' pets draws both swarms in.
     * Whether PvP is on is the server's call: a cancelled attack never gets here. A player's hit on a wild pet is
     * remembered on that pet instead.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttacked(EntityDamageByEntityEvent event) {
        PetManager manager = PetManager.get();
        if (manager == null) return;
        Player attacker = attacker(event.getDamager());
        UUID aggressor = aggressor(manager, event.getDamager());
        if (aggressor == null) return;
        if (event.getEntity() instanceof Player victim) {
            manager.attacked(victim.getUniqueId(), aggressor);
            return;
        }
        PetControl pet = manager.pet(event.getEntity());
        if (pet == null) return;
        if (!pet.isWild()) manager.attacked(pet.ownerId(), aggressor);
        // A wild pet remembers the player who hurt it, for its retaliate module.
        else if (attacker != null) pet.hurtBy(attacker.getUniqueId(), PetManager.ticks());
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

    /** A pet's model touched a player: a wild pet remembers it for its sting module; no pet deals FMM's contact attack. */
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

    /** New chunks are stocked with wild pets as they generate, like vanilla's farm animals. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        PetManager manager = PetManager.get();
        if (manager != null && event.isNewChunk())
            manager.chunkGenerated(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    /** A live wild pet another plugin removed stays gone; one whose chunk stopped ticking waits to come back. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        PetManager manager = PetManager.get();
        if (manager != null && event.getCause() == EntityRemoveEvent.Cause.PLUGIN) manager.bodyRemovedByPlugin(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldSave(WorldSaveEvent event) {
        PetManager manager = PetManager.get();
        if (manager != null) manager.saveWild();
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
        // The watchdog brings equipped pets to the new world; bodies left behind go now, but for pets told to stay,
        // which keep their spot.
        PetManager manager = PetManager.get();
        if (manager == null) return;
        for (PetControl control : new ArrayList<>(manager.pets()))
            if (control.isOwnedBy(event.getPlayer()) && !control.isStaying()) manager.despawn(control);
    }
}
