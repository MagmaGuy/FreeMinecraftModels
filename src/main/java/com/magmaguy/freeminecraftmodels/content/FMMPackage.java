package com.magmaguy.freeminecraftmodels.content;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.commands.ReloadCommand;
import com.magmaguy.freeminecraftmodels.config.contentpackages.ContentPackageConfigFields;
import com.magmaguy.magmacore.nightbreak.AbstractNightbreakContentPackage;
import com.magmaguy.magmacore.nightbreak.NightbreakFileUtils;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class FMMPackage extends AbstractNightbreakContentPackage {
    @Getter
    private static final Map<String, FMMPackage> fmmPackages = new HashMap<>();
    @Getter
    private final ContentPackageConfigFields contentPackageConfigFields;

    public FMMPackage(ContentPackageConfigFields contentPackageConfigFields) {
        this.contentPackageConfigFields = contentPackageConfigFields;
        fmmPackages.put(contentPackageConfigFields.getFilename(), this);
    }

    public static void shutdown() {
        fmmPackages.clear();
    }

    @Override
    protected void doInstall(Player player) {
        player.closeInventory();
        List<File> disabledEntries = collectManagedEntries(getDisabledModelsFolder());
        if (disabledEntries.isEmpty()) {
            Logger.sendMessage(player, "&cCould not find the disabled model pack folder for " + getDisplayName());
            return;
        }

        NightbreakFileUtils.moveEntriesFlat(disabledEntries, getInstalledModelsFolder());
        handleStateSave(player,
                contentPackageConfigFields.setEnabledAndSave(true),
                () -> {
                    if (player.isOnline()) {
                        Logger.sendSimpleMessage(player, "&aReloading FreeMinecraftModels so the model pack is enabled...");
                    }
                    ReloadCommand.reloadPlugin(player);
                },
                "&cFailed to update FreeMinecraftModels package state. Check the console.");
    }

    @Override
    protected void doUninstall(Player player) {
        player.closeInventory();
        List<File> installedEntries = collectManagedEntries(getInstalledModelsFolder());
        if (installedEntries.isEmpty()) {
            Logger.sendMessage(player, "&cCould not find the installed model pack folder for " + getDisplayName());
            return;
        }

        NightbreakFileUtils.moveEntriesFlat(installedEntries, getDisabledModelsFolder());
        handleStateSave(player,
                contentPackageConfigFields.setEnabledAndSave(false),
                () -> {
                    if (player.isOnline()) {
                        Logger.sendSimpleMessage(player, "&aReloading FreeMinecraftModels so the model pack is disabled...");
                    }
                    ReloadCommand.reloadPlugin(player);
                },
                "&cFailed to update FreeMinecraftModels package state. Check the console.");
    }

    private File getInstalledModelsFolder() {
        return new File(MetadataHandler.PLUGIN.getDataFolder(), "models");
    }

    private File getDisabledModelsFolder() {
        return new File(MetadataHandler.PLUGIN.getDataFolder(), "models_disabled");
    }

    private List<File> collectManagedEntries(File rootFolder) {
        return NightbreakFileUtils.collectRootEntries(rootFolder,
                contentPackageConfigFields.getFolderName(),
                contentPackageConfigFields.getContentFilePrefixes());
    }

    @Override
    protected JavaPlugin getOwnerPlugin() {
        return (JavaPlugin) MetadataHandler.PLUGIN;
    }

    @Override
    protected String getPluginDisplayName() {
        return "FreeMinecraftModels";
    }

    @Override
    protected String getContentPageUrl() {
        return "https://nightbreak.io/plugin/freeminecraftmodels/";
    }

    @Override
    protected List<String> getPackageDescription() {
        return contentPackageConfigFields.getDescription();
    }

    @Override
    protected String getManualImportsFolderName() {
        return "FreeMinecraftModels imports";
    }

    @Override
    protected String getManualReloadCommand() {
        return "/fmm reload";
    }

    @Override
    protected void onDownloadStateSaved(Player player) {
        if (player.isOnline()) {
            Logger.sendSimpleMessage(player, "&aReloading FreeMinecraftModels so the new content is picked up...");
        }
        ReloadCommand.reloadPlugin(player);
    }

    @Override
    public String getNightbreakSlug() {
        return contentPackageConfigFields.getNightbreakSlug();
    }

    @Override
    public String getDisplayName() {
        return contentPackageConfigFields.getName();
    }

    @Override
    public String getDownloadLink() {
        return contentPackageConfigFields.getDownloadLink();
    }

    @Override
    public int getLocalVersion() {
        return contentPackageConfigFields.getVersion();
    }

    @Override
    public CompletableFuture<Void> enableAfterDownload() {
        return contentPackageConfigFields.setEnabledAndSave(true);
    }

    @Override
    public boolean isInstalled() {
        return contentPackageConfigFields.isEnabled() && !collectManagedEntries(getInstalledModelsFolder()).isEmpty();
    }

    @Override
    public boolean isDownloaded() {
        return !collectManagedEntries(getInstalledModelsFolder()).isEmpty()
                || !collectManagedEntries(getDisabledModelsFolder()).isEmpty();
    }
}
