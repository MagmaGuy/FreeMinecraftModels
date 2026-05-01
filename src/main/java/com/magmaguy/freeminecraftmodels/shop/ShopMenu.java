package com.magmaguy.freeminecraftmodels.shop;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry;
import com.magmaguy.freeminecraftmodels.config.ShopConfig;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeConfig;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeManager;
import com.magmaguy.freeminecraftmodels.menus.ModelMenuHelper;
import com.magmaguy.freeminecraftmodels.utils.ModelItemFactory;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Vault-backed furniture shop menu. Mirrors {@link com.magmaguy.freeminecraftmodels.menus.CraftableItemsMenu}
 * for layout/pagination/event-routing, but every clickable slot triggers a
 * purchase via {@link PurchaseHandler} instead of opening a recipe view.
 *
 * <p>Lazy by construction — purchasable recipes are computed only on menu open.
 */
public class ShopMenu {

    private static final HashMap<Inventory, ShopMenu> openMenus = new HashMap<>();
    private static final NamespacedKey MODEL_ID_KEY =
            new NamespacedKey(MetadataHandler.PLUGIN, "model_id");

    private final Player player;
    private final List<PropRecipeConfig> purchasableRecipes;
    private int page;

    public ShopMenu(Player player) {
        this.player = player;
        this.page = 0;
        this.purchasableRecipes = PropRecipeManager.getLoadedRecipes().values().stream()
                .filter(PropRecipeConfig::isEnabled)
                .filter(PropRecipeConfig::isShopEnabled)
                .sorted(Comparator.comparing(PropRecipeConfig::getModelId))
                .collect(Collectors.toList());
        open();
    }

    private void open() {
        Inventory inventory = Bukkit.createInventory(null, 54,
                ChatColorConverter.convert(ShopConfig.getMenuTitle()));
        populate(inventory);
        openMenus.put(inventory, this);
        player.openInventory(inventory);
    }

    private void populate(Inventory inventory) {
        inventory.clear();

        int start = page * ModelMenuHelper.ITEMS_PER_PAGE;
        int end = Math.min(start + ModelMenuHelper.ITEMS_PER_PAGE, purchasableRecipes.size());

        for (int i = start; i < end; i++) {
            int slotIndex = i - start;
            inventory.setItem(ModelMenuHelper.CONTENT_SLOTS[slotIndex],
                    buildShopDisplayItem(purchasableRecipes.get(i)));
        }

        if (page > 0) {
            inventory.setItem(ModelMenuHelper.PREV_SLOT,
                    ModelMenuHelper.buildPrevPageItem());
        }

        int totalPages = Math.max(1, (int) Math.ceil(
                (double) purchasableRecipes.size() / ModelMenuHelper.ITEMS_PER_PAGE));
        if (page < totalPages - 1) {
            inventory.setItem(ModelMenuHelper.NEXT_SLOT,
                    ModelMenuHelper.buildNextPageItem());
        }
    }

    private static ItemStack buildShopDisplayItem(PropRecipeConfig recipe) {
        String modelId = recipe.getModelId();
        String formattedName = ModelItemFactory.formatModelName(modelId);
        String displayName = "&e\u2726 &6" + formattedName + " &e\u2726";

        String formattedPrice = VaultEconomyHook.isEnabled()
                ? VaultEconomyHook.formatAmount(recipe.getShopPrice())
                : String.valueOf(recipe.getShopPrice());

        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(ShopConfig.getPriceLoreFormat().replace("{price}", formattedPrice));
        lore.add("");
        lore.add(ShopConfig.getClickToBuyLoreFormat());
        lore.add("");
        lore.add("&8ID: " + modelId);

        ItemStack item = ItemStackGenerator.generateItemStack(Material.PAPER, displayName, lore);

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Apply display-model item model on 1.21.4+ if the model is registered.
            if (!VersionChecker.serverVersionOlderThan(21, 4)
                    && DisplayModelRegistry.hasDisplayModel(modelId)) {
                meta.setItemModel(NamespacedKey.fromString("freeminecraftmodels:display/" + modelId));
            }
            meta.getPersistentDataContainer().set(MODEL_ID_KEY, PersistentDataType.STRING, modelId);
            item.setItemMeta(meta);
        }

        return item;
    }

    public static void registerEvents(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new Events(), plugin);
    }

    public static class Events implements Listener {

        @EventHandler
        public void onInventoryClick(InventoryClickEvent event) {
            Inventory inventory = event.getInventory();
            ShopMenu menu = openMenus.get(inventory);
            if (menu == null) return;

            event.setCancelled(true);

            // Ignore clicks in the player's own inventory area.
            if (event.getClickedInventory() != inventory) return;

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;

            int slot = event.getRawSlot();

            if (slot == ModelMenuHelper.PREV_SLOT && menu.page > 0) {
                menu.page--;
                menu.populate(inventory);
                return;
            }
            if (slot == ModelMenuHelper.NEXT_SLOT) {
                int totalPages = Math.max(1, (int) Math.ceil(
                        (double) menu.purchasableRecipes.size() / ModelMenuHelper.ITEMS_PER_PAGE));
                if (menu.page < totalPages - 1) {
                    menu.page++;
                    menu.populate(inventory);
                }
                return;
            }

            // Resolve the recipe via PDC tag (display state is untrusted).
            ItemMeta meta = clicked.getItemMeta();
            if (meta == null) return;
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (!pdc.has(MODEL_ID_KEY, PersistentDataType.STRING)) return;
            String modelId = pdc.get(MODEL_ID_KEY, PersistentDataType.STRING);
            PropRecipeConfig recipe = PropRecipeManager.getLoadedRecipes().get(modelId);
            if (recipe == null) return;

            Player player = (Player) event.getWhoClicked();
            PurchaseHandler.Outcome outcome = PurchaseHandler.attempt(player, recipe);
            sendOutcomeMessage(player, recipe, outcome);

            if (outcome.result == PurchaseHandler.Result.SHOP_DISABLED) {
                player.closeInventory();
            }
        }

        @EventHandler
        public void onInventoryClose(InventoryCloseEvent event) {
            openMenus.remove(event.getInventory());
        }

        private static void sendOutcomeMessage(Player player, PropRecipeConfig recipe,
                                               PurchaseHandler.Outcome outcome) {
            String itemName = ModelItemFactory.formatModelName(recipe.getModelId());
            String priceText = VaultEconomyHook.isEnabled()
                    ? VaultEconomyHook.formatAmount(outcome.price)
                    : String.valueOf(outcome.price);
            String balanceText = VaultEconomyHook.isEnabled()
                    ? VaultEconomyHook.formatAmount(outcome.balance)
                    : String.valueOf(outcome.balance);

            switch (outcome.result) {
                case SUCCESS -> Logger.sendMessage(player, applyPlaceholders(
                        ShopConfig.getPurchaseSuccessMessage(), itemName, priceText, balanceText));
                case SUCCESS_WITH_OVERFLOW -> {
                    Logger.sendMessage(player, applyPlaceholders(
                            ShopConfig.getPurchaseSuccessMessage(), itemName, priceText, balanceText));
                    Logger.sendMessage(player, applyPlaceholders(
                            ShopConfig.getInventoryFullMessage(), itemName, priceText, balanceText));
                }
                case INSUFFICIENT_FUNDS -> Logger.sendMessage(player, applyPlaceholders(
                        ShopConfig.getInsufficientFundsMessage(), itemName, priceText, balanceText));
                case NOT_FOR_SALE -> Logger.sendMessage(player, applyPlaceholders(
                        ShopConfig.getItemNotForSaleMessage(), itemName, priceText, balanceText));
                case SHOP_DISABLED -> Logger.sendMessage(player,
                        ShopConfig.getShopDisabledMessage());
            }
        }

        private static String applyPlaceholders(String template, String item, String price, String balance) {
            return template
                    .replace("{item}", item)
                    .replace("{price}", price)
                    .replace("{balance}", balance);
        }
    }
}
