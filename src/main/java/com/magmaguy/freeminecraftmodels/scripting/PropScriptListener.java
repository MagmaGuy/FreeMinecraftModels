package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.magmacore.scripting.ScriptInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bukkit event listener that bridges player interactions and projectile hits on props
 * to the Lua scripting engine via {@link ScriptInstance#handleEvent}.
 */
public class PropScriptListener implements Listener {

    private final Map<PropEntity, ScriptInstance> scriptedProps = new ConcurrentHashMap<>();

    /**
     * Registers a prop with an active script instance so that events on its
     * backing entity are forwarded to the Lua script.
     */
    public void register(PropEntity prop, ScriptInstance instance) {
        scriptedProps.put(prop, instance);
    }

    /**
     * Unregisters a prop, stopping event forwarding.
     */
    public void unregister(PropEntity prop) {
        scriptedProps.remove(prop);
    }

    /**
     * Shuts down all tracked script instances and clears the map.
     */
    public void shutdownAll() {
        for (ScriptInstance instance : scriptedProps.values()) {
            instance.shutdown();
        }
        scriptedProps.clear();
    }

    // ── Interaction events ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof ArmorStand armorStand)) return;
        if (!PropEntity.isPropEntity(armorStand)) return;

        PropEntity prop = PropEntity.getPropEntities().get(armorStand.getUniqueId());
        if (prop == null) return;

        ScriptInstance instance = scriptedProps.get(prop);
        if (instance == null || instance.isClosed()) return;

        if (event.getHand() == EquipmentSlot.HAND) {
            instance.handleEvent(ScriptableProp.ON_RIGHT_CLICK, event, null, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        // PlayerInteractEntityEvent fires for right-click on entities;
        // we handle it here as a fallback when PlayerInteractAtEntityEvent is not fired.
        // Avoid double-firing: PlayerInteractAtEntityEvent extends PlayerInteractEntityEvent,
        // so only handle the base type here.
        if (event instanceof PlayerInteractAtEntityEvent) return;

        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof ArmorStand armorStand)) return;
        if (!PropEntity.isPropEntity(armorStand)) return;

        PropEntity prop = PropEntity.getPropEntities().get(armorStand.getUniqueId());
        if (prop == null) return;

        ScriptInstance instance = scriptedProps.get(prop);
        if (instance == null || instance.isClosed()) return;

        if (event.getHand() == EquipmentSlot.HAND) {
            instance.handleEvent(ScriptableProp.ON_RIGHT_CLICK, event, null, null);
        }
    }

    // ── Projectile hit event ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onProjectileHit(ProjectileHitEvent event) {
        Entity hitEntity = event.getHitEntity();
        if (hitEntity == null) return;
        if (!(hitEntity instanceof ArmorStand armorStand)) return;
        if (!PropEntity.isPropEntity(armorStand)) return;

        PropEntity prop = PropEntity.getPropEntities().get(armorStand.getUniqueId());
        if (prop == null) return;

        ScriptInstance instance = scriptedProps.get(prop);
        if (instance == null || instance.isClosed()) return;

        // Pass the shooter as eventActor if it is a living entity
        Projectile projectile = event.getEntity();
        instance.handleEvent(ScriptableProp.ON_PROJECTILE_HIT, event, null, null);
    }
}
