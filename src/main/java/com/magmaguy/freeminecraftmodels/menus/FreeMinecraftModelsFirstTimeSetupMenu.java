package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.FreeMinecraftModels;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.magmacore.menus.FirstTimeSetupMenu;
import com.magmaguy.magmacore.menus.MenuButton;
import com.magmaguy.magmacore.nightbreak.NightbreakSetupMenuHelper;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class FreeMinecraftModelsFirstTimeSetupMenu {
    private FreeMinecraftModelsFirstTimeSetupMenu() {
    }

    public static void createMenu(Player player) {
        new FirstTimeSetupMenu(
                (JavaPlugin) MetadataHandler.PLUGIN,
                player,
                "&2FreeMinecraftModels",
                "&6Nightbreak content setup",
                createInfoItem(),
                List.of(createRecommendedItem(), createManualItem(), createSkipItem()));
    }

    private static MenuButton createInfoItem() {
        return new MenuButton(ItemStackGenerator.generateSkullItemStack(
                "magmaguy",
                "&2Welcome to FreeMinecraftModels!",
                List.of("&7Link Nightbreak, install model packs, and let FMM rebuild the pack output."))) {
            @Override
            public void onClick(Player player) {
                player.closeInventory();
                NightbreakSetupMenuHelper.sendFirstTimeSetupResources(player, FreeMinecraftModels.FIRST_TIME_SETUP_SPEC);
            }
        };
    }

    private static MenuButton createRecommendedItem() {
        return new MenuButton(ItemStackGenerator.generateItemStack(
                Material.GREEN_STAINED_GLASS_PANE,
                "&2Recommended Setup",
                List.of("&aMarks setup complete and points you to Nightbreak-managed model packs."))) {
            @Override
            public void onClick(Player player) {
                player.closeInventory();
                DefaultConfig.toggleSetupDone(true);
                NightbreakSetupMenuHelper.sendRecommendedSetupInstructions(player, FreeMinecraftModels.FIRST_TIME_SETUP_SPEC);
            }
        };
    }

    private static MenuButton createManualItem() {
        return new MenuButton(ItemStackGenerator.generateItemStack(
                Material.YELLOW_STAINED_GLASS_PANE,
                "&6Manual Setup",
                List.of("&eMarks setup complete and leaves pack management up to you."))) {
            @Override
            public void onClick(Player player) {
                player.closeInventory();
                DefaultConfig.toggleSetupDone(true);
                Logger.sendSimpleMessage(player, "&8&m-----------------------------------------------------");
                Logger.sendSimpleMessage(player, "&6Setup complete. Use &a/fmm setup &6when you want to manage content.");
                Logger.sendSimpleMessage(player, "&8&m-----------------------------------------------------");
            }
        };
    }

    private static MenuButton createSkipItem() {
        return new MenuButton(ItemStackGenerator.generateItemStack(
                Material.RED_STAINED_GLASS_PANE,
                "&cUse Current Content",
                List.of("&cDismisses the setup prompt and keeps your current model folders as-is."))) {
            @Override
            public void onClick(Player player) {
                player.closeInventory();
                DefaultConfig.toggleSetupDone(true);
                Logger.sendSimpleMessage(player, "&8&m-----------------------------------------------------");
                Logger.sendSimpleMessage(player, "&aSetup complete. FreeMinecraftModels will keep using your current model folders.");
                Logger.sendSimpleMessage(player, "&7Run &a/fmm reload &7if you import new content later.");
                Logger.sendSimpleMessage(player, "&8&m-----------------------------------------------------");
            }
        };
    }

    private static void sendLink(Player player, String prefix, String display, String hover, String url) {
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage(prefix),
                SpigotMessage.hoverLinkMessage(display, hover, url));
    }

    private static void sendCommand(Player player, String prefix, String display, String hover, String command) {
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage(prefix),
                SpigotMessage.commandHoverMessage(display, hover, command));
    }
}
