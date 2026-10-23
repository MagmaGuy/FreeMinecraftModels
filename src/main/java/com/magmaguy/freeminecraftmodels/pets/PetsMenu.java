package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The player's collection as a chest menu: one entry per pet, click to bring it along or send it home. */
public final class PetsMenu {
    private static final Map<Inventory, List<PetRecord>> OPEN = new HashMap<>();

    private PetsMenu() {
    }

    public static void open(Player player) {
        PetManager manager = PetManager.get();
        if (manager == null) return;
        PetCollection collection = manager.collection(player);
        if (collection.pets().isEmpty()) {
            player.sendMessage(ChatColorConverter.convert(PetsConfig.noPetsMessage));
            return;
        }
        List<PetRecord> records = new ArrayList<>(collection.pets());
        int rows = Math.min(6, (records.size() + 8) / 9);
        Inventory inventory = Bukkit.createInventory(player, rows * 9, ChatColorConverter.convert(PetsConfig.menuTitle));
        for (int slot = 0; slot < records.size() && slot < rows * 9; slot++) inventory.setItem(slot, icon(manager, records.get(slot)));
        OPEN.put(inventory, records);
        player.openInventory(inventory);
    }

    /** The species' icon, glinting while the pet is out; its item model when the species names one. */
    private static ItemStack icon(PetManager manager, PetRecord record) {
        PetSpecies pet = manager.species(record.species);
        ItemStack item = new ItemStack(pet == null ? Material.BARRIER : pet.icon());
        ItemMeta meta = item.getItemMeta();
        if (pet != null && pet.iconItemModel() != null) meta.setItemModel(pet.iconItemModel());
        meta.setEnchantmentGlintOverride(record.equipped);
        meta.setDisplayName(ChatColorConverter.convert((record.equipped ? "&a" : "&7")) + manager.displayName(record));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColorConverter.convert("&7Species: &f" + record.species));
        if (record.model != null && pet != null && !record.model.equals(pet.model()))
            lore.add(ChatColorConverter.convert("&7Variant: &f" + record.model));
        lore.add(ChatColorConverter.convert(record.baby ? "&7Age: &fbaby" : "&7Age: &fadult"));
        lore.add(ChatColorConverter.convert(record.equipped ? "&aWith you &7- click to send home" : "&7At home &7- click to bring along"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static void shutdown() {
        OPEN.clear();
    }

    public static final class Events implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent event) {
            List<PetRecord> records = OPEN.get(event.getInventory());
            if (records == null) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) return;
            int slot = event.getSlot();
            PetManager manager = PetManager.get();
            if (manager == null || slot < 0 || slot >= records.size()) return;
            PetRecord record = manager.collection(player).find(records.get(slot).uuid());
            if (record == null) return;
            if (record.equipped) manager.unequip(player, record);
            else manager.equip(player, record);
            event.getInventory().setItem(slot, icon(manager, record));
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (OPEN.containsKey(event.getInventory())) event.setCancelled(true);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            OPEN.remove(event.getInventory());
        }
    }
}
