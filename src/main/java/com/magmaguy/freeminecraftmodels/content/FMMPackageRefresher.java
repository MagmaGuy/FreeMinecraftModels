package com.magmaguy.freeminecraftmodels.content;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.nightbreak.NightbreakContentRefresher;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

public class FMMPackageRefresher {
    private static final Duration REFRESH_COOLDOWN = Duration.ofMinutes(5);
    private static final String CATALOG_KEY = "nightbreak-packages";

    private FMMPackageRefresher() {
    }

    public static void refreshContentAndAccess() {
        NightbreakContentRefresher.refreshAsyncIfDue(
                (JavaPlugin) MetadataHandler.PLUGIN,
                CATALOG_KEY,
                REFRESH_COOLDOWN,
                () -> FMMPackage.getFmmPackages().values(),
                fmmPackage -> true,
                outdated -> {
                });
    }

    public static void reset() {
        NightbreakContentRefresher.resetRefreshCooldown(
                (JavaPlugin) MetadataHandler.PLUGIN, CATALOG_KEY);
    }
}
