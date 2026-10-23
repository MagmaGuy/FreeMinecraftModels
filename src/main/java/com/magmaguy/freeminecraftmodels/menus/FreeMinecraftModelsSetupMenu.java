package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.FreeMinecraftModels;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.config.PetsConfig;
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
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class FreeMinecraftModelsSetupMenu {
    private FreeMinecraftModelsSetupMenu() {
    }

    /** Switches pets between "more creatures" (wild spawns and taming) and "cosmetic pets" (owned pets only). */
    private static MenuButton petModeButton() {
        boolean creatures = PetsConfig.mode == PetsConfig.Mode.CREATURES;
        return new MenuButton(creatures ? Material.GLOW_BERRIES : Material.NAME_TAG,
                ChatColorConverter.convert(creatures ? "&aPets: more creatures" : "&bPets: cosmetic pets"),
                List.of(ChatColorConverter.convert(creatures
                                ? "&fPet species also live wild and are tamed by feeding them."
                                : "&fOnly pets players own exist in the world."),
                        ChatColorConverter.convert("&7Click to switch to " + (creatures ? "cosmetic pets." : "more creatures.")))) {
            @Override
            public void onClick(Player player) {
                PetsConfig.setModeAndSave(creatures ? PetsConfig.Mode.COSMETIC : PetsConfig.Mode.CREATURES);
                player.closeInventory();
                createMenu(player);
            }
        };
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
        builder.addToolbarButton(petModeButton());
        NightbreakSetupControls.prependStandardControls(builder, (JavaPlugin) MetadataHandler.PLUGIN, FreeMinecraftModels.NIGHTBREAK_PLUGIN_SPEC)
                .open();
    }
}
