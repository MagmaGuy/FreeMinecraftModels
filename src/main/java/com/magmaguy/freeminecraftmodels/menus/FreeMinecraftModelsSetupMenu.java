package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.FreeMinecraftModels;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.freeminecraftmodels.content.FMMPackageRefresher;
import com.magmaguy.magmacore.menus.MenuButton;
import com.magmaguy.magmacore.menus.SetupMenuBuilder;
import com.magmaguy.magmacore.nightbreak.DownloadAllContentPackage;
import com.magmaguy.magmacore.nightbreak.NightbreakAccount;
import com.magmaguy.magmacore.nightbreak.NightbreakSetupControls;
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
        if (!DefaultConfig.isSetupDone()) {
            DefaultConfig.toggleSetupDone(true);
        }

        List<FMMPackage> packages = new ArrayList<>(FMMPackage.getFmmPackages().values()).stream()
                .sorted(Comparator.comparing(pkg ->
                        ChatColor.stripColor(ChatColorConverter.convert(pkg.getContentPackageConfigFields().getName()))))
                .collect(Collectors.toList());
        FMMPackageRefresher.refreshContentAndAccess();

        MenuButton infoButton = NightbreakSetupControls.setupInfoButton(
                FreeMinecraftModels.NIGHTBREAK_PLUGIN_SPEC,
                "https://nightbreak.io/plugin/freeminecraftmodels/#setup");

        SetupMenuBuilder builder = new SetupMenuBuilder((JavaPlugin) MetadataHandler.PLUGIN, player)
                .title("Setup menu")
                .infoButton(infoButton)
                .packages(packages)
                .appendPackage(new DownloadAllContentPackage<>(() -> new ArrayList<>(FMMPackage.getFmmPackages().values()),
                        "FreeMinecraftModels",
                        "https://nightbreak.io/plugin/freeminecraftmodels/",
                        "fmm downloadall"));
        NightbreakSetupControls.prependStandardControls(builder, (JavaPlugin) MetadataHandler.PLUGIN, FreeMinecraftModels.NIGHTBREAK_PLUGIN_SPEC)
                .open();
    }
}
