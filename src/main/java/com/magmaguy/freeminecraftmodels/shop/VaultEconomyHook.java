package com.magmaguy.freeminecraftmodels.shop;

import com.magmaguy.freeminecraftmodels.config.ShopConfig;
import com.magmaguy.magmacore.util.Logger;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Optional Vault integration for the furniture shop. The hook is initialized
 * once during plugin sync init; if anything required is missing the hook
 * silently disables itself and the rest of the shop refuses to register.
 */
public final class VaultEconomyHook {

    private static Economy economy = null;
    private static boolean enabled = false;

    private VaultEconomyHook() {
    }

    /**
     * Attempts to wire up the Vault economy provider. Safe to call repeatedly —
     * each call resets state from scratch.
     */
    public static void initialize() {
        enabled = false;
        economy = null;

        if (!ShopConfig.isEnabled()) return;

        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            Logger.warn("[FMM Shop] Shop is enabled but Vault is not installed; shop disabled.");
            return;
        }

        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            Logger.warn("[FMM Shop] Shop is enabled but no economy provider is registered with Vault; shop disabled.");
            return;
        }

        economy = rsp.getProvider();
        enabled = true;
        Logger.info("[FMM Shop] Initialized with economy provider: " + economy.getName());
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static double getBalance(Player player) {
        if (!enabled) return 0;
        return economy.getBalance(player);
    }

    public static boolean withdraw(Player player, double amount) {
        if (!enabled) return false;
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    public static String formatAmount(double amount) {
        if (!enabled) return String.valueOf(amount);
        return economy.format(amount);
    }
}
