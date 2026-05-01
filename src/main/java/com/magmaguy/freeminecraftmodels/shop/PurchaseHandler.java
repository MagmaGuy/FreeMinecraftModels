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
        SHOP_DISABLED
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

    public static Outcome attempt(Player player, PropRecipeConfig recipe) {
        if (recipe == null || !recipe.isEnabled()) {
            return new Outcome(Result.NOT_FOR_SALE, 0, 0, null);
        }
        if (!ShopConfig.isEnabled() || !VaultEconomyHook.isEnabled()) {
            return new Outcome(Result.SHOP_DISABLED, 0, 0, null);
        }
        if (!recipe.isShopEnabled()) {
            return new Outcome(Result.NOT_FOR_SALE, 0, 0, null);
        }

        double price = recipe.getShopPrice();
        double balance = VaultEconomyHook.getBalance(player);
        if (balance < price) {
            return new Outcome(Result.INSUFFICIENT_FUNDS, price, balance, null);
        }

        if (!VaultEconomyHook.withdraw(player, price)) {
            // Withdrawal can fail if the underlying provider rejects it (race / negative-balance check).
            return new Outcome(Result.INSUFFICIENT_FUNDS, price, balance, null);
        }

        ItemStack output = ModelItemFactory.createModelItem(recipe.getModelId(), Material.PAPER);
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(output);
        boolean hadOverflow = !overflow.isEmpty();
        if (hadOverflow) {
            for (ItemStack stack : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            }
        }

        double newBalance = VaultEconomyHook.getBalance(player);
        return new Outcome(
                hadOverflow ? Result.SUCCESS_WITH_OVERFLOW : Result.SUCCESS,
                price, newBalance, output);
    }
}
