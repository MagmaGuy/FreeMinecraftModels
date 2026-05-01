package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;
import lombok.Getter;

import java.util.List;

/**
 * Singleton config for the optional Vault-backed furniture shop.
 * Generates {@code plugins/FreeMinecraftModels/shop_config.yml} on first run.
 *
 * <p>The shop is disabled by default. When enabled it requires Vault and an
 * economy provider; absence of either causes the shop to gracefully refuse to
 * register.
 */
public class ShopConfig extends ConfigurationFile {

    @Getter
    public static boolean enabled;
    @Getter
    public static double defaultPrice;

    @Getter
    public static String menuTitle;
    @Getter
    public static String priceLoreFormat;
    @Getter
    public static String clickToBuyLoreFormat;

    @Getter
    public static String purchaseSuccessMessage;
    @Getter
    public static String insufficientFundsMessage;
    @Getter
    public static String shopDisabledMessage;
    @Getter
    public static String itemNotForSaleMessage;
    @Getter
    public static String inventoryFullMessage;

    public ShopConfig() {
        super("shop_config.yml");
    }

    @Override
    public void initializeValues() {
        enabled = ConfigurationEngine.setBoolean(
                List.of("Master toggle for the optional furniture shop.",
                        "Defaults to false; set to true to allow players to buy craftable items via /fmm shop.",
                        "Requires Vault and a registered economy provider to be installed; the shop disables itself silently if either is missing."),
                fileConfiguration, "enabled", false);

        defaultPrice = ConfigurationEngine.setDouble(
                List.of("Default price applied to recipes that do not explicitly set a shopPrice.",
                        "Existing recipe files without shopPrice will have this value written into them on next load."),
                fileConfiguration, "defaultPrice", 100.0);

        menuTitle = ConfigurationEngine.setString(
                List.of("Title shown at the top of the shop inventory."),
                fileConfiguration, "menuTitle", "&8FMM - Furniture Shop");

        priceLoreFormat = ConfigurationEngine.setString(
                List.of("Lore line appended to each shop item showing its price.",
                        "Placeholders: {price}"),
                fileConfiguration, "priceLoreFormat", "&7Price: &e{price}");

        clickToBuyLoreFormat = ConfigurationEngine.setString(
                List.of("Lore line appended to each shop item prompting purchase."),
                fileConfiguration, "clickToBuyLoreFormat", "&aClick to purchase");

        purchaseSuccessMessage = ConfigurationEngine.setString(
                List.of("Sent to the player after a successful purchase.",
                        "Placeholders: {item}, {price}, {balance}"),
                fileConfiguration, "messages.purchaseSuccess",
                "&aPurchased &f{item} &afor &e{price}&a. Balance: &e{balance}&a.");

        insufficientFundsMessage = ConfigurationEngine.setString(
                List.of("Sent when the player cannot afford the clicked item.",
                        "Placeholders: {item}, {price}, {balance}"),
                fileConfiguration, "messages.insufficientFunds",
                "&cYou need &e{price} &cto buy &f{item}&c. Balance: &e{balance}&c.");

        shopDisabledMessage = ConfigurationEngine.setString(
                List.of("Sent when the shop is disabled (master toggle off, Vault missing, or no economy provider).",
                        "Players normally never see this — the command is unregistered when the shop is off — but it is shown if the shop is force-disabled at runtime."),
                fileConfiguration, "messages.shopDisabled",
                "&cThe furniture shop is currently unavailable.");

        itemNotForSaleMessage = ConfigurationEngine.setString(
                List.of("Sent when a player clicks an item that is not for sale (recipe shopEnabled is false).",
                        "Placeholders: {item}"),
                fileConfiguration, "messages.itemNotForSale",
                "&cThat item is not currently for sale.");

        inventoryFullMessage = ConfigurationEngine.setString(
                List.of("Sent when the purchased item could not fit in the player's inventory and was dropped at their feet.",
                        "Placeholders: {item}"),
                fileConfiguration, "messages.inventoryFull",
                "&eYour inventory was full; &f{item} &ehas been dropped at your feet.");
    }
}
