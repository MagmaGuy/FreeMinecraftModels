package com.magmaguy.freeminecraftmodels;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.commands.*;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.config.ModelsFolder;
import com.magmaguy.freeminecraftmodels.config.OutputFolder;
import com.magmaguy.freeminecraftmodels.config.props.PropsConfig;
import com.magmaguy.freeminecraftmodels.customentity.*;
import com.magmaguy.freeminecraftmodels.customentity.core.OBBHitDetection;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.listeners.EntityTeleportEvent;
import com.magmaguy.freeminecraftmodels.utils.VersionChecker;
import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.command.CommandManager;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class FreeMinecraftModels extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        Bukkit.getLogger().info(" _______ __                               ___ __   _______           __         __        ");
        Bukkit.getLogger().info("|   |   |__|.-----.-----.----.----.---.-.'  _|  |_|   |   |.-----.--|  |.-----.|  |.-----.");
        Bukkit.getLogger().info("|       |  ||     |  -__|  __|   _|  _  |   _|   _|       ||  _  |  _  ||  -__||  ||__ --|");
        Bukkit.getLogger().info("|__|_|__|__||__|__|_____|____|__| |___._|__| |____|__|_|__||_____|_____||_____||__||_____|");
        Bukkit.getLogger().info("Version " + this.getDescription().getVersion());
        MetadataHandler.PLUGIN = this;
        MagmaCore.onEnable();
        //Initialize plugin configuration files
        new DefaultConfig();
        MagmaCore.initializeImporter();
        OutputFolder.initializeConfig();
        ModelsFolder.initializeConfig();
        Metrics metrics = new Metrics(this, 19337);
        Bukkit.getPluginManager().registerEvents(new ModeledEntityEvents(), this);
//        Bukkit.getPluginManager().registerEvents(new LegacyHitDetection(), this);
        Bukkit.getPluginManager().registerEvents(new OBBHitDetection(), this);
        Bukkit.getPluginManager().registerEvents(new PropEntity.PropEntityEvents(), this);

        Bukkit.getPluginManager().registerEvents(new VersionChecker.VersionCheckerEvents(), this);
        Bukkit.getPluginManager().registerEvents(new EntityTeleportEvent(), this);
        NMSManager.initializeAdapter(this);
        VersionChecker.checkPluginVersion();
        CommandManager manager = new CommandManager(this, "freeminecraftmodels");
        manager.registerCommand(new MountCommand());
        manager.registerCommand(new PropCommand());
        manager.registerCommand(new OBBDebugCommand());
        manager.registerCommand(new ReloadCommand());
        manager.registerCommand(new SpawnCommand());
        manager.registerCommand(new VersionCommand());
        new PropsConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        OutputFolder.zipResourcePack();

        ModeledEntitiesClock.start();

        PropEntity.onStartup();
    }

    @Override
    public void onLoad() {
        MagmaCore.createInstance(this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        MagmaCore.shutdown();
        FileModelConverter.shutdown();
        StaticEntity.shutdown();
        DynamicEntity.shutdown();
        ModeledEntity.shutdown();
        ModeledEntitiesClock.shutdown();
        PropEntity.onShutdown();
        Bukkit.getServer().getScheduler().cancelTasks(MetadataHandler.PLUGIN);
        HandlerList.unregisterAll(MetadataHandler.PLUGIN);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamagedByEntityEvent(EntityDamageByEntityEvent event) {
        if (!event.isCancelled()) return;
        if (!(event.getEntity() instanceof LivingEntity livingEntity)) return;
        if (DynamicEntity.isDynamicEntity(livingEntity))
            event.setCancelled(false);
    }
}
