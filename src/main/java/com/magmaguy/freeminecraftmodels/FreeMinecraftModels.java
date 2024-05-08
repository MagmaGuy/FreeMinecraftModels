package com.magmaguy.freeminecraftmodels;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.commands.CommandManager;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.config.ImportsFolder;
import com.magmaguy.freeminecraftmodels.config.ModelsFolder;
import com.magmaguy.freeminecraftmodels.config.OutputFolder;
import com.magmaguy.freeminecraftmodels.config.props.PropsConfig;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntityEvents;
import com.magmaguy.freeminecraftmodels.customentity.StaticEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.LegacyHitDetection;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.utils.VersionChecker;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class FreeMinecraftModels extends JavaPlugin {

    @Override
    public void onEnable() {

        Bukkit.getLogger().info(" _______ __                               ___ __   _______           __         __        ");
        Bukkit.getLogger().info("|   |   |__|.-----.-----.----.----.---.-.'  _|  |_|   |   |.-----.--|  |.-----.|  |.-----.");
        Bukkit.getLogger().info("|       |  ||     |  -__|  __|   _|  _  |   _|   _|       ||  _  |  _  ||  -__||  ||__ --|");
        Bukkit.getLogger().info("|__|_|__|__||__|__|_____|____|__| |___._|__| |____|__|_|__||_____|_____||_____||__||_____|");
        Bukkit.getLogger().info("Version " + this.getDescription().getVersion());
        MetadataHandler.PLUGIN = this;
        //Initialize plugin configuration files
        DefaultConfig.initializeConfig();
        ImportsFolder.initializeConfig();
        ModelsFolder.initializeConfig();
        OutputFolder.initializeConfig();
        Metrics metrics = new Metrics(this, 19337);
        Bukkit.getPluginManager().registerEvents(new ModeledEntityEvents(), this);
        Bukkit.getPluginManager().registerEvents(new LegacyHitDetection(), this);
        Bukkit.getPluginManager().registerEvents(new VersionChecker.VersionCheckerEvents(), this);
        NMSManager.initializeAdapter(this);
        VersionChecker.checkPluginVersion();
        new CommandManager(this);
        new PropsConfig();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        FileModelConverter.shutdown();
        StaticEntity.shutdown();
        DynamicEntity.shutdown();
        LegacyHitDetection.shutdown();
        Bukkit.getServer().getScheduler().cancelTasks(MetadataHandler.PLUGIN);
        HandlerList.unregisterAll(MetadataHandler.PLUGIN);
    }
}
