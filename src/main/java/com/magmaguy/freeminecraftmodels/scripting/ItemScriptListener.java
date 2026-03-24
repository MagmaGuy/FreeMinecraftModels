package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.scripting.ScriptInstance;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;

/**
 * Bukkit event listener for the item scripting system.
 * <p>
 * Handles two responsibilities:
 * <ol>
 *   <li><b>Equip tracking</b> — monitors events that change equipment and calls
 *       {@link ItemScriptManager#updateEquippedScripts(Player)} so script instances
 *       are started/stopped as items are equipped/removed.</li>
 *   <li><b>Hook dispatch</b> — routes Bukkit events to the appropriate
 *       {@link ScriptInstance} via the 22 item-specific hooks defined in
 *       {@link ScriptableItem}.</li>
 * </ol>
 */
public class ItemScriptListener implements Listener {

    private static final NamespacedKey ITEM_ID_KEY = ItemScriptManager.ITEM_ID_KEY;

    // ── Helper methods ──────────────────────────────────────────────────

    private String getItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(ITEM_ID_KEY, PersistentDataType.STRING);
    }

    private void fireForMainHand(Player player, com.magmaguy.magmacore.scripting.ScriptHook hook, org.bukkit.event.Event event) {
        String itemId = getItemId(player.getInventory().getItemInMainHand());
        if (itemId == null) return;
        ScriptInstance instance = ItemScriptManager.getActiveScript(player.getUniqueId(), itemId);
        if (instance != null && !instance.isClosed())
            instance.handleEvent(hook, event, null, player);
    }

    private void fireForAllEquipped(Player player, com.magmaguy.magmacore.scripting.ScriptHook hook, org.bukkit.event.Event event) {
        Map<String, ScriptInstance> scripts = ItemScriptManager.getActiveScripts(player.getUniqueId());
        for (ScriptInstance instance : scripts.values())
            if (!instance.isClosed())
                instance.handleEvent(hook, event, null, player);
    }

    // ── Equip tracking events (MONITOR priority) ────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        ItemScriptManager.updateEquippedScripts(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemScriptManager.updateEquippedScripts(player);
        fireForAllEquipped(player, ScriptableItem.ON_SWAP_HANDS, event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Bukkit.getScheduler().runTaskLater(MetadataHandler.PLUGIN,
                () -> ItemScriptManager.updateEquippedScripts(player), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        ItemScriptManager.updateEquippedScripts(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        ItemScriptManager.removePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        // Fire ON_DROP for the dropped item
        String droppedId = getItemId(event.getItemDrop().getItemStack());
        if (droppedId != null) {
            ScriptInstance instance = ItemScriptManager.getActiveScript(player.getUniqueId(), droppedId);
            if (instance != null && !instance.isClosed())
                instance.handleEvent(ScriptableItem.ON_DROP, event, null, player);
        }

        // Delay 1 tick then update equipped
        Bukkit.getScheduler().runTaskLater(MetadataHandler.PLUGIN,
                () -> ItemScriptManager.updateEquippedScripts(player), 1L);
    }

    // ── Hook dispatch events (LOW priority) ─────────────────────────────

    // ── Combat ──

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        fireForMainHand(player, ScriptableItem.ON_ATTACK_ENTITY, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        fireForMainHand(killer, ScriptableItem.ON_KILL_ENTITY, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        fireForAllEquipped(player, ScriptableItem.ON_TAKE_DAMAGE, event);
        if (player.isBlocking())
            fireForAllEquipped(player, ScriptableItem.ON_SHIELD_BLOCK, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        String bowId = getItemId(event.getBow());
        if (bowId == null) return;
        ScriptInstance instance = ItemScriptManager.getActiveScript(player.getUniqueId(), bowId);
        if (instance != null && !instance.isClosed())
            instance.handleEvent(ScriptableItem.ON_SHOOT_BOW, event, null, player);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        fireForMainHand(player, ScriptableItem.ON_PROJECTILE_LAUNCH, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) return;

        // Try AbstractArrow.getWeapon() PDC first
        String itemId = null;
        if (projectile instanceof AbstractArrow arrow) {
            itemId = getItemId(arrow.getWeapon());
        }

        // Fall back to main hand
        if (itemId == null) {
            itemId = getItemId(player.getInventory().getItemInMainHand());
        }

        if (itemId == null) return;
        ScriptInstance instance = ItemScriptManager.getActiveScript(player.getUniqueId(), itemId);
        if (instance != null && !instance.isClosed())
            instance.handleEvent(ScriptableItem.ON_PROJECTILE_HIT, event, null, player);
    }

    // ── Interaction ──

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();

        switch (action) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                if (player.isSneaking())
                    fireForMainHand(player, ScriptableItem.ON_SHIFT_RIGHT_CLICK, event);
                else
                    fireForMainHand(player, ScriptableItem.ON_RIGHT_CLICK, event);
            }
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> {
                if (player.isSneaking())
                    fireForMainHand(player, ScriptableItem.ON_SHIFT_LEFT_CLICK, event);
                else
                    fireForMainHand(player, ScriptableItem.ON_LEFT_CLICK, event);
            }
            default -> { /* PHYSICAL — ignore */ }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        fireForMainHand(event.getPlayer(), ScriptableItem.ON_INTERACT_ENTITY, event);
    }

    // ── Utility ──

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        fireForMainHand(event.getPlayer(), ScriptableItem.ON_BREAK_BLOCK, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        String itemId = getItemId(event.getItem());
        if (itemId == null) return;
        ScriptInstance instance = ItemScriptManager.getActiveScript(player.getUniqueId(), itemId);
        if (instance != null && !instance.isClosed())
            instance.handleEvent(ScriptableItem.ON_CONSUME, event, null, player);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onPlayerItemDamage(PlayerItemDamageEvent event) {
        Player player = event.getPlayer();
        String itemId = getItemId(event.getItem());
        if (itemId == null) return;
        ScriptInstance instance = ItemScriptManager.getActiveScript(player.getUniqueId(), itemId);
        if (instance != null && !instance.isClosed())
            instance.handleEvent(ScriptableItem.ON_ITEM_DAMAGE, event, null, player);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onPlayerFish(PlayerFishEvent event) {
        fireForMainHand(event.getPlayer(), ScriptableItem.ON_FISH, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        fireForAllEquipped(player, ScriptableItem.ON_DEATH, event);
    }
}
