package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.freeminecraftmodels.content.FMMPackageRefresher;
import com.magmaguy.magmacore.menus.MenuButton;
import com.magmaguy.magmacore.menus.SetupMenu;
import com.magmaguy.magmacore.nightbreak.DownloadAllContentPackage;
import com.magmaguy.magmacore.nightbreak.NightbreakAccount;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class FreeMinecraftModelsSetupMenu {
    private FreeMinecraftModelsSetupMenu() {
    }

    public static void createMenu(Player player) {
        List<FMMPackage> packages = new ArrayList<>(FMMPackage.getFmmPackages().values()).stream()
                .sorted(Comparator.comparing(pkg ->
                        ChatColor.stripColor(ChatColorConverter.convert(pkg.getContentPackageConfigFields().getName()))))
                .collect(Collectors.toList());
        FMMPackageRefresher.refreshContentAndAccess();

        MenuButton infoButton = new MenuButton(ItemStackGenerator.generateSkullItemStack("magmaguy",
                "&2Installation instructions:",
                List.of(
                        "&61) &fLink your Nightbreak account: &a/nightbreaklogin",
                        "&62) &fDownload all model packs: &a/fmm downloadall",
                        "&63) &fOr browse and manage them here: &a/fmm setup"))) {
            @Override
            public void onClick(Player p) {
                p.closeInventory();
                Logger.sendSimpleMessage(p, "<g:#8B0000:#CC4400:#DAA520>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬</g>");
                Logger.sendSimpleMessage(p, "&6&lFreeMinecraftModels installation resources:");
                p.spigot().sendMessage(
                        SpigotMessage.simpleMessage("&2&lNightbreak account: "),
                        SpigotMessage.hoverLinkMessage("&ahttps://nightbreak.io/account/",
                                "&7Click to open the Nightbreak account page.",
                                "https://nightbreak.io/account/"));
                p.spigot().sendMessage(
                        SpigotMessage.simpleMessage("&2&lContent: "),
                        SpigotMessage.hoverLinkMessage("&ahttps://nightbreak.io/plugin/freeminecraftmodels/",
                                "&7Click to browse FreeMinecraftModels content.",
                                "https://nightbreak.io/plugin/freeminecraftmodels/"));
                p.spigot().sendMessage(
                        SpigotMessage.commandHoverMessage("&2&lBulk download: &a/fmm downloadall",
                                "&7Click to download all available FreeMinecraftModels content.",
                                "/fmm downloadall"));
                if (NightbreakAccount.hasToken()) {
                    p.spigot().sendMessage(
                            SpigotMessage.commandHoverMessage("&2&lBulk update: &a/fmm updatecontent",
                                    "&7Click to update all outdated FreeMinecraftModels content.",
                                    "/fmm updatecontent"));
                }
                Logger.sendSimpleMessage(p, "<g:#8B0000:#CC4400:#DAA520>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬</g>");
            }
        };

        List<com.magmaguy.magmacore.menus.ContentPackage> allPackages = new ArrayList<>(packages);
        allPackages.add(new DownloadAllContentPackage<>(() -> new ArrayList<>(FMMPackage.getFmmPackages().values()),
                "FreeMinecraftModels",
                "https://nightbreak.io/plugin/freeminecraftmodels/",
                "fmm downloadall"));

        new SetupMenu((JavaPlugin) MetadataHandler.PLUGIN, player, infoButton, allPackages, List.of(), "Setup menu");
    }
}
