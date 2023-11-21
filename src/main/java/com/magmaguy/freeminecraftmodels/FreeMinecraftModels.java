package com.magmaguy.freeminecraftmodels;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.commands.CommandHandler;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.config.ImportsFolder;
import com.magmaguy.freeminecraftmodels.config.ModelsFolder;
import com.magmaguy.freeminecraftmodels.config.OutputFolder;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntityEvents;
import com.magmaguy.freeminecraftmodels.customentity.StaticEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class FreeMinecraftModels extends JavaPlugin {

    @Override
    public void onEnable() {

        Bukkit.getLogger().info(" _______ __                               ___ __   _______           __         __        ");
        Bukkit.getLogger().info("|   |   |__|.-----.-----.----.----.---.-.'  _|  |_|   |   |.-----.--|  |.-----.|  |.-----.");
        Bukkit.getLogger().info("|       |  ||     |  -__|  __|   _|  _  |   _|   _|       ||  _  |  _  ||  -__||  ||__ --|");
        Bukkit.getLogger().info("|__|_|__|__||__|__|_____|____|__| |___._|__| |____|__|_|__||_____|_____||_____||__||_____|");
        MetadataHandler.PLUGIN = this;
        //Initialize plugin configuration files
        DefaultConfig.initializeConfig();
        ImportsFolder.initializeConfig();
        ModelsFolder.initializeConfig();
        OutputFolder.initializeConfig();
        Metrics metrics = new Metrics(this, 19337);
        Bukkit.getPluginManager().registerEvents(new ModeledEntityEvents(), this);
        Bukkit.getPluginManager().registerEvents(new ModeledEntityEvents(), this);
        NMSManager.initializeAdapter(this);
        new CommandHandler();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        FileModelConverter.shutdown();
        StaticEntity.shutdown();
        DynamicEntity.shutdown();
    }
}
