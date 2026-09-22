package com.magmaguy.freeminecraftmodels.listeners;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.commands.CraftifyCommand;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeConfig;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeManager;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class CraftifyListener implements Listener {

    private static final NamespacedKey CRAFTIFY_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "craftify_output");
    private static final Map<UUID, Session> activeSessions = new HashMap<>();

    private record Session(String modelId, Inventory inventory) {}

    public static void startSession(Player player, String modelId, Inventory inventory) {
        var view = player.openInventory(inventory);
        if (view != null && player.getOpenInventory().getTopInventory() == inventory)
            activeSessions.put(player.getUniqueId(), new Session(modelId, inventory));
    }

    private static boolean isInSession(Player player, Inventory inventory) {
        Session session = activeSessions.get(player.getUniqueId());
        return session != null && session.inventory() == inventory;
    }

    public static void shutdown() {
        for (UUID playerId : new ArrayList<>(activeSessions.keySet())) {
            Player player = org.bukkit.Bukkit.getPlayer(playerId);
            if (player != null && isInSession(player, player.getOpenInventory().getTopInventory()))
                player.closeInventory();
        }
        activeSessions.clear();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isInSession(player, event.getInventory())) return;

        int slot = event.getRawSlot();

        // Click is in the craftify inventory (top inventory, slots 0-53)
        if (slot >= 0 && slot < 54) {
            // Allow interaction with grid slots
            boolean isGridSlot = false;
            for (int gridSlot : CraftifyCommand.GRID_SLOTS) {
                if (slot == gridSlot) {
                    isGridSlot = true;
                    break;
                }
            }

            if (isGridSlot) {
                return; // Allow normal item placement/pickup in grid
            }

            // Click on output slot — save the recipe
            if (slot == CraftifyCommand.OUTPUT_SLOT) {
                event.setCancelled(true);
                handleSave(player, event.getInventory());
                return;
            }

            // All other top-inventory slots are borders — cancel
            event.setCancelled(true);
            return;
        }
        // Clicks in player inventory are fine
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isInSession(player, event.getInventory())) return;

        Set<Integer> gridSet = new HashSet<>();
        for (int s : CraftifyCommand.GRID_SLOTS) gridSet.add(s);

        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < 54 && !gridSet.contains(slot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!isInSession(player, event.getInventory())) return;

        activeSessions.remove(player.getUniqueId());
        // Return any items left in the grid to the player
        Inventory inv = event.getInventory();
        for (int slot : CraftifyCommand.GRID_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
                overflow.values().forEach(leftover ->
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                inv.setItem(slot, null);
            }
        }

    }

    private void handleSave(Player player, Inventory inv) {
        Session session = activeSessions.get(player.getUniqueId());
        if (session == null || session.inventory() != inv) return;
        String modelId = session.modelId();

        // Read the 3x3 grid
        ItemStack[] grid = new ItemStack[9];
        boolean hasAnyIngredient = false;
        for (int i = 0; i < 9; i++) {
            grid[i] = inv.getItem(CraftifyCommand.GRID_SLOTS[i]);
            if (grid[i] != null && grid[i].getType() != Material.AIR) {
                hasAnyIngredient = true;
            }
        }

        if (!hasAnyIngredient) {
            Logger.sendMessage(player, "\u00a7cPlace at least one ingredient in the grid first!");
            return;
        }

        // Build the shape and ingredient map
        Map<Material, Character> materialToChar = new LinkedHashMap<>();
        char nextChar = 'A';
        char[][] charGrid = new char[3][3];

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                ItemStack item = grid[row * 3 + col];
                if (item == null || item.getType() == Material.AIR) {
                    charGrid[row][col] = ' ';
                } else {
                    Material mat = item.getType();
                    if (!materialToChar.containsKey(mat)) {
                        materialToChar.put(mat, nextChar++);
                    }
                    charGrid[row][col] = materialToChar.get(mat);
                }
            }
        }

        // Trim the shape to the minimal bounding box
        int minRow = 3, maxRow = -1, minCol = 3, maxCol = -1;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if (charGrid[row][col] != ' ') {
                    minRow = Math.min(minRow, row);
                    maxRow = Math.max(maxRow, row);
                    minCol = Math.min(minCol, col);
                    maxCol = Math.max(maxCol, col);
                }
            }
        }

        List<String> shape = new ArrayList<>();
        for (int row = minRow; row <= maxRow; row++) {
            StringBuilder sb = new StringBuilder();
            for (int col = minCol; col <= maxCol; col++) {
                sb.append(charGrid[row][col]);
            }
            shape.add(sb.toString());
        }

        Map<Character, Material> ingredients = new LinkedHashMap<>();
        for (Map.Entry<Material, Character> entry : materialToChar.entrySet()) {
            ingredients.put(entry.getValue(), entry.getKey());
        }

        // Save and register
        PropRecipeConfig config = PropRecipeConfig.create(modelId, shape, ingredients, PropRecipeManager.getRecipesFolder());
        if (!PropRecipeManager.addRecipe(config)) {
            Logger.sendMessage(player, "\u00a7cThe recipe could not be saved. Your ingredients are still in the grid; check the console.");
            return;
        }

        // Visual feedback
        player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 30, 0.5, 0.5, 0.5);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        Logger.sendMessage(player, "\u00a7a\u2714 Recipe saved for \u00a7e" + modelId + "\u00a7a!");

        // Clear grid items (consumed as recipe cost)
        for (int slot : CraftifyCommand.GRID_SLOTS) {
            inv.setItem(slot, null);
        }

        player.closeInventory();
    }
}
