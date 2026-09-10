package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitByProjectileEvent;
import com.magmaguy.magmacore.projectiles.MagicProjectileMarker;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** FMM keeps modeled collisions and presentation; MagmaCore owns flight and impact arbitration. */
final class MagicProjectileEngine extends com.magmaguy.magmacore.projectiles.MagicProjectileEngine<MagicCast> {
    MagicProjectileEngine(Plugin plugin, ImpactHandler<MagicCast> impacts) { super(plugin, impacts); }

    @Override protected boolean isAbsorbed(MagicCast source, Location from, Location to) {
        if (com.magmaguy.freeminecraftmodels.api.magic.MagicProjectileTravelEvent.getHandlerList()
                .getRegisteredListeners().length == 0) return false;
        var query = new com.magmaguy.freeminecraftmodels.api.magic.MagicProjectileTravelEvent(
                source.owner(), source.definition().kind(), from, to);
        plugin.getServer().getPluginManager().callEvent(query);
        return query.isCancelled();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onModeledMarkerHit(ModeledEntityHitByProjectileEvent event) {
        if (!MagicProjectileMarker.isMarked(event.getProjectile())) return;
        event.setCancelled(true);
        Entity underlying = event.getEntity().getUnderlyingEntity();
        MagicProjectileMarker.resolve(event.getProjectile(), underlying instanceof LivingEntity living ? living : null);
    }

    @Override protected ItemDisplay spawnFireballVisual(Arrow marker) {
        try {
            World world = marker.getWorld();
            return world.spawn(marker.getLocation(), ItemDisplay.class, visual -> {
                visual.setItemStack(new ItemStack(Material.FIRE_CHARGE));
                visual.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                visual.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                visual.setPersistent(false);
                visual.setTeleportDuration(2);
                visual.setTransformation(new Transformation(
                        new Vector3f(),
                        new Quaternionf(),
                        new Vector3f(.85F, .85F, .85F),
                        new Quaternionf()));
            });
        } catch (RuntimeException visualFailure) {
            return null; // purely cosmetic: the flight works without its fireball model
        }
    }

}
