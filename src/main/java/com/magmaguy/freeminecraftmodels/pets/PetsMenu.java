package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
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
import java.util.UUID;

/**
 * The player's collection as a chest menu: one entry per pet, click to bring it along or send it home, shift-right-click
 * twice to set it free.
 */
public final class PetsMenu {
    private static final Map<Inventory, List<PetRecord>> OPEN = new HashMap<>();
    private static final long RELEASE_CONFIRM_MILLIS = 5000L;
    /** A shift-right-click waiting for its second, by player: which pet, and until when. */
    private static final Map<UUID, PendingRelease> RELEASING = new HashMap<>();

    private record PendingRelease(String record, long until) {
    }

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

    /**
     * The species' icon, glinting while the pet is out; its item model when the species names one. Its lore is the
     * pet's passport: species, colour, how rare that colour is in the wild, generation, parents and age.
     */
    private static ItemStack icon(PetManager manager, PetRecord record) {
        PetSpecies pet = manager.species(record.species);
        ItemStack item = new ItemStack(pet == null ? Material.BARRIER : pet.icon());
        ItemMeta meta = item.getItemMeta();
        if (pet != null && pet.iconItemModel() != null) meta.setItemModel(pet.iconItemModel());
        meta.setEnchantmentGlintOverride(record.equipped);
        meta.setDisplayName(ChatColorConverter.convert((record.equipped ? "&a" : "&7")) + manager.displayName(record));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColorConverter.convert("&7Species: &f" + (pet == null ? record.species : pet.name())));
        PetSpecies.Variant variant = pet == null ? null : pet.variant(record.model);
        if (variant != null && variant.name() != null) lore.add(ChatColorConverter.convert("&7Colour: &f" + variant.name()));
        else if (record.model != null && pet != null && !record.model.equals(pet.model()))
            lore.add(ChatColorConverter.convert("&7Colour: &f" + record.model));
        if (pet != null && !pet.variants().isEmpty())
            lore.add(ChatColorConverter.convert("&7Rarity: &f" + rarity(pet.wildShare(record.model))));
        lore.add(ChatColorConverter.convert("&7Generation: &f" + record.generationOrFirst()));
        if (record.parents != null && record.parents.size() == 2)
            lore.add(ChatColorConverter.convert("&7Parents: &f") + record.parents.get(0) + ChatColorConverter.convert(" &7& &f")
                    + record.parents.get(1));
        lore.add(ChatColorConverter.convert(record.baby ? "&7Age: &fbaby" : "&7Age: &fadult"));
        lore.add(ChatColorConverter.convert(record.equipped ? "&aWith you &7- click to send home" : "&7At home &7- click to bring along"));
        lore.add(ChatColorConverter.convert("&8Shift-right-click to set it free"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** A colour's wild share as a passport line: "1 in 21 wild ones", "common" past a half, or never wild. */
    static String rarity(double share) {
        if (share <= 0D) return "never found wild";
        if (share >= 0.5D) return "common";
        return "1 in " + Math.round(1D / share) + " wild ones";
    }

    public static void shutdown() {
        OPEN.clear();
        RELEASING.clear();
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
            if (event.getClick() == ClickType.SHIFT_RIGHT) {
                // Setting free cannot be undone: the first shift-right-click asks, a second within 5 seconds does it.
                long now = System.currentTimeMillis();
                PendingRelease pending = RELEASING.get(player.getUniqueId());
                if (pending == null || !pending.record().equals(record.id) || now > pending.until()) {
                    RELEASING.put(player.getUniqueId(), new PendingRelease(record.id, now + RELEASE_CONFIRM_MILLIS));
                    player.sendMessage(ChatColorConverter.convert(PetsConfig.releaseConfirmMessage.replace("$pet", manager.displayName(record))));
                    return;
                }
                RELEASING.remove(player.getUniqueId());
                manager.release(player, record);
                // The menu shrinks by one: open it afresh next tick, outside this click, or close it when no pet is left.
                Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                    if (!player.isOnline()) return;
                    if (manager.collection(player).pets().isEmpty()) player.closeInventory();
                    else open(player);
                });
                return;
            }
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
