package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;


//Used for versions prior to 1.19.4 which do not have advanced entity types that can be used for hitboxes
public class LegacyHitDetection implements Listener {

    private static final int meleeRange = 3;
    private static final int resolution = 4;
    private static final HashSet<UUID> cooldowns = new HashSet();
    @Getter
    @Setter
    private static boolean entityDamageBypass = false;

    public static void shutdown() {
        cooldowns.clear();
    }

    private static ModeledEntity raytraceForModeledEntity(Player player) {
        Location startLocation = player.getEyeLocation();
        Vector ray = player.getEyeLocation().getDirection().normalize().multiply(resolution / 10d);
        List<ModeledEntity> modeledEntities = ModeledEntityManager.getAllEntities();
        ModeledEntity modeledEntity = null;
        modeledEntities.removeIf(thisEntity -> thisEntity.getWorld() == null || !thisEntity.getWorld().equals(player.getWorld()));
        if (modeledEntities.isEmpty()) return modeledEntity;

        for (int i = 0; i < meleeRange * resolution; i++) {
            if (modeledEntity != null) break;
            startLocation.add(ray);
            for (ModeledEntity entity : modeledEntities) {
                BoundingBox boundingBox = entity.getHitbox();
                if (boundingBox == null) continue;
                if (!boundingBox.contains(startLocation.toVector())) continue;
                modeledEntity = entity;
                break;
            }
        }
        return modeledEntity;
    }

    private static void damageCustomModelEntity(ModeledEntity modeledEntity, Player player, double damage) {
        modeledEntity.damage(player, damage);
    }

    private static void addCooldown(Player player) {
        cooldowns.add(player.getUniqueId());
        new BukkitRunnable() {
            @Override
            public void run() {
                cooldowns.remove(player.getUniqueId());
            }
        }.runTaskLater(MetadataHandler.PLUGIN, 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void playerAnimation(PlayerAnimationEvent event) {
        if (cooldowns.contains(event.getPlayer().getUniqueId())) return;
        if (!event.getAnimationType().equals(PlayerAnimationType.ARM_SWING)) return;
        addCooldown(event.getPlayer());
        ModeledEntity modeledEntity = raytraceForModeledEntity(event.getPlayer());
        if (modeledEntity == null) return;
        event.setCancelled(true);
        damageCustomModelEntity(modeledEntity, event.getPlayer(), 2);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void playerMeleeAttack(EntityDamageByEntityEvent event) {
        if (cooldowns.contains(event.getDamager().getUniqueId())) return;
        if (!RegisterModelEntity.isModelEntity(event.getEntity()) &&
                !RegisterModelEntity.isModelArmorStand(event.getEntity())) return;
        if (entityDamageBypass) {
            entityDamageBypass = false;
            return;
        }
        Player player;
        if (event.getDamager() instanceof Player)
            player = (Player) event.getDamager();
        else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter)
            player = shooter;
        else return;

        addCooldown(player);
        event.setCancelled(true);
        ModeledEntity modeledEntity = raytraceForModeledEntity(player);
        if (modeledEntity == null) return;
        damageCustomModelEntity(modeledEntity, player, event.getDamage());
    }
}
