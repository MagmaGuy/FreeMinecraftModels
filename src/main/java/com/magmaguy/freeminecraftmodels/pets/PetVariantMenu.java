package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * The admin's colour picker for {@code /fmm petadmin give <species>}: one entry per colour with its name, rarity and
 * biomes, and a last entry for a pick by rarity. A click adds that pet to the admin's own collection; the menu stays
 * open for more.
 */
public final class PetVariantMenu {
    private record Open(PetSpecies species, List<String> models) {
    }

    private static final Map<Inventory, Open> OPEN = new HashMap<>();
    /** The entry that picks by rarity, as the command without the picker did. */
    private static final String RANDOM = "";

    private PetVariantMenu() {
    }

    public static void open(Player player, PetSpecies species) {
        List<String> models = new ArrayList<>();
        if (species.variants().isEmpty()) models.add(species.model());
        else for (PetSpecies.Variant variant : species.variants()) models.add(variant.model());
        models.add(RANDOM);
        int rows = Math.min(6, (models.size() + 8) / 9);
        Inventory inventory = Bukkit.createInventory(player, rows * 9,
                ChatColorConverter.convert("&2Give a ") + ChatColorConverter.convert(species.name()));
        for (int slot = 0; slot < models.size() && slot < rows * 9; slot++) inventory.setItem(slot, icon(species, models.get(slot)));
        OPEN.put(inventory, new Open(species, models));
        player.openInventory(inventory);
    }

    private static ItemStack icon(PetSpecies species, String model) {
        ItemStack item = new ItemStack(model.equals(RANDOM) ? Material.ENDER_EYE : species.icon());
        ItemMeta meta = item.getItemMeta();
        NamespacedKey itemModel = species.iconItemModel();
        if (itemModel != null && !model.equals(RANDOM)) meta.setItemModel(itemModel);
        List<String> lore = new ArrayList<>();
        if (model.equals(RANDOM)) {
            meta.setDisplayName(ChatColorConverter.convert("&fAny colour"));
            lore.add(ChatColorConverter.convert("&7Picked by rarity, as a wild one would be."));
        } else {
            meta.setDisplayName(ChatColorConverter.convert("&f") + ChatColorConverter.convert(species.name(model)));
            lore.add(ChatColorConverter.convert("&7Model: &f" + model));
            if (!species.variants().isEmpty()) lore.add(ChatColorConverter.convert("&7Rarity: &f" + PetsMenu.rarity(species.wildShare(model))));
            PetSpecies.Variant variant = species.variant(model);
            if (variant != null && !variant.biomes().isEmpty())
                lore.add(ChatColorConverter.convert("&7Biomes: &f" + variant.biomes().stream().map(NamespacedKey::getKey).sorted()
                        .limit(6).collect(Collectors.joining(", ")) + (variant.biomes().size() > 6 ? ", ..." : "")));
            if (variant != null && variant.inheritChance() < 1D)
                lore.add(ChatColorConverter.convert("&7Breeds true: &f" + Math.round(variant.inheritChance() * 100D) + "%"));
        }
        lore.add(ChatColorConverter.convert("&aClick to add one to your pets"));
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
            Open open = OPEN.get(event.getInventory());
            if (open == null) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) return;
            int slot = event.getSlot();
            PetManager manager = PetManager.get();
            if (manager == null || slot < 0 || slot >= open.models().size()) return;
            // A reload may have replaced the species since the menu opened.
            PetSpecies species = manager.species(open.species().id());
            if (species == null) return;
            String model = open.models().get(slot);
            if (model.equals(RANDOM)) model = species.pickVariant(ThreadLocalRandom.current());
            PetRecord record = manager.give(player, species, model);
            player.sendMessage(ChatColorConverter.convert("&aAdded a ") + manager.displayName(record)
                    + ChatColorConverter.convert(" &ato your pets."));
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
