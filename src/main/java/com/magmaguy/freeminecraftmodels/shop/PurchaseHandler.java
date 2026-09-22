package com.magmaguy.freeminecraftmodels.shop;

import com.magmaguy.freeminecraftmodels.config.ShopConfig;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeConfig;
import com.magmaguy.freeminecraftmodels.utils.ModelItemFactory;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;

/**
 * Pure logic for attempting a purchase. The shop menu interprets the
 * {@link Result} and renders the matching configurable message.
 */
public final class PurchaseHandler {

    public enum Result {
        SUCCESS,
        SUCCESS_WITH_OVERFLOW,
        INSUFFICIENT_FUNDS,
        NOT_FOR_SALE,
        SHOP_DISABLED,
        DELIVERY_FAILED_REFUNDED,
        DELIVERY_FAILED_REFUND_PENDING
    }

    public static final class Outcome {
        public final Result result;
        public final double price;
        public final double balance;
        public final ItemStack item;

        public Outcome(Result result, double price, double balance, ItemStack item) {
            this.result = result;
            this.price = price;
            this.balance = balance;
            this.item = item;
        }
    }

    private PurchaseHandler() {
    }

    private static int countMatchingItems(Player player, ItemStack purchasedItem) {
        int count = 0;
        for (ItemStack stored : player.getInventory().getStorageContents())
            if (stored != null && stored.isSimilar(purchasedItem)) count += stored.getAmount();
        return count;
    }

    public static Outcome attempt(Player player, PropRecipeConfig recipe) {
        if (recipe == null || !recipe.isEnabled()) {
            return new Outcome(Result.NOT_FOR_SALE, 0, 0, null);
        }
        if (!ShopConfig.isEnabled() || !VaultEconomyHook.isEnabled()) {
            return new Outcome(Result.SHOP_DISABLED, 0, 0, null);
        }
        if (!recipe.isShopEnabled() || !com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter.containsModel(recipe.getModelId())) {
            return new Outcome(Result.NOT_FOR_SALE, 0, 0, null);
        }

        ItemStack output = ModelItemFactory.createModelItem(recipe.getModelId(), Material.PAPER);
        double price = recipe.getShopPrice();
        double balance = VaultEconomyHook.getBalance(player);
        if (balance < price) {
            return new Outcome(Result.INSUFFICIENT_FUNDS, price, balance, null);
        }

        int quantity = output.getAmount();
        int previousQuantity = countMatchingItems(player, output);
        if (!VaultEconomyHook.withdraw(player, price)) {
            // Withdrawal can fail if the underlying provider rejects it (race / negative-balance check).
            return new Outcome(Result.INSUFFICIENT_FUNDS, price, balance, null);
        }

        boolean hadOverflow;
        java.util.List<org.bukkit.entity.Item> droppedItems = new java.util.ArrayList<>();
        try {
            HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(output.clone());
            hadOverflow = !overflow.isEmpty();
            for (ItemStack stack : overflow.values()) {
                org.bukkit.entity.Item dropped = player.getWorld().dropItemNaturally(
                        player.getLocation(), stack, droppedItems::add);
                if (!dropped.isValid()) throw new IllegalStateException("The overflow item spawn was rejected");
            }
        } catch (RuntimeException deliveryFailure) {
            int delivered;
            try {
                delivered = Math.max(0, countMatchingItems(player, output) - previousQuantity);
                for (org.bukkit.entity.Item dropped : droppedItems)
                    if (dropped.isValid()) delivered += dropped.getItemStack().getAmount();
                delivered = Math.min(quantity, delivered);
            } catch (RuntimeException uncertainDelivery) {
                VaultEconomyHook.disableUnresolvedPurchase(player, price,
                        "Delivery failed and its completed amount could not be read: " + uncertainDelivery.getMessage());
                return new Outcome(Result.DELIVERY_FAILED_REFUND_PENDING, price, balance - price, output);
            }
            double refund = price * (quantity - delivered) / quantity;
            com.magmaguy.magmacore.util.Logger.warn("[FMM Shop] Item delivery failed for " + player.getUniqueId()
                    + ": delivered " + delivered + "/" + quantity + ", refund " + refund
                    + ". Cause: " + deliveryFailure.getMessage());
            if (refund == 0) return new Outcome(Result.SUCCESS, price, balance - price, output);
            boolean refunded = VaultEconomyHook.refund(player, refund);
            return new Outcome(refunded ? Result.DELIVERY_FAILED_REFUNDED : Result.DELIVERY_FAILED_REFUND_PENDING,
                    price - (refunded ? refund : 0), balance - price + (refunded ? refund : 0), output);
        }

        // Delivery has committed; a display-only balance lookup must not turn it into a failed purchase.
        double newBalance = balance - price;
        try { newBalance = VaultEconomyHook.getBalance(player); }
        catch (RuntimeException ignored) {}
        return new Outcome(
                hadOverflow ? Result.SUCCESS_WITH_OVERFLOW : Result.SUCCESS,
                price, newBalance, output);
    }
}
