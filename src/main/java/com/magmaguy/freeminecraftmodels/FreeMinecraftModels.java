package com.magmaguy.freeminecraftmodels;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.commands.*;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.config.ModelsFolder;
import com.magmaguy.freeminecraftmodels.config.OutputFolder;
import com.magmaguy.freeminecraftmodels.config.props.PropsConfig;
import com.magmaguy.freeminecraftmodels.customentity.*;
import com.magmaguy.freeminecraftmodels.customentity.core.OBBHitDetection;
import com.magmaguy.freeminecraftmodels.customentity.core.components.InteractionComponent;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.listeners.EntityTeleportEvent;
import com.magmaguy.freeminecraftmodels.listeners.ModelItemListener;
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
        MagmaCore.checkVersionUpdate("111660", "https://nightbreak.io/plugin/freeminecraftmodels/");
        //Initialize plugin configuration files
        new DefaultConfig();
        MagmaCore.initializeImporter();
        OutputFolder.initializeConfig();
        ModelsFolder.initializeConfig();
        Metrics metrics = new Metrics(this, 19337);
        Bukkit.getPluginManager().registerEvents(new ModeledEntityEvents(), this);
        Bukkit.getPluginManager().registerEvents(new OBBHitDetection(), this);
        Bukkit.getPluginManager().registerEvents(new PropEntity.PropEntityEvents(), this);
        Bukkit.getPluginManager().registerEvents(new EntityTeleportEvent(), this);
        Bukkit.getPluginManager().registerEvents(new DynamicEntity.ModeledEntityEvents(), this);
        Bukkit.getPluginManager().registerEvents(new InteractionComponent.InteractionComponentEvents(), this);
        Bukkit.getPluginManager().registerEvents(new ModelItemListener(), this);
        NMSManager.initializeAdapter(this);
        CommandManager manager = new CommandManager(this, "freeminecraftmodels");
        manager.registerCommand(new MountCommand());
        manager.registerCommand(new HitboxDebugCommand());
        manager.registerCommand(new DeleteAllCommand());
        manager.registerCommand(new ReloadCommand());
        manager.registerCommand(new SpawnCommand());
        manager.registerCommand(new StatsCommand());
        manager.registerCommand(new VersionCommand());
        manager.registerCommand(new FreeMinecraftModelsCommand());
        manager.registerCommand(new DisguiseCommand());
        manager.registerCommand(new UndisguiseCommand());
        manager.registerCommand(new ItemifyCommand());
        new PropsConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        OutputFolder.zipResourcePack();

        ModeledEntitiesClock.start();

        PropEntity.onStartup();
        OBBHitDetection.startProjectileDetection();
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
        ModeledEntity.shutdown();
        ModeledEntitiesClock.shutdown();
        OBBHitDetection.shutdown();
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
