package com.magmaguy.freeminecraftmodels;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.commands.*;
import com.magmaguy.freeminecraftmodels.config.contentpackages.ContentPackageConfig;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry;
import com.magmaguy.freeminecraftmodels.config.ModelsFolder;
import com.magmaguy.freeminecraftmodels.config.OutputFolder;
import com.magmaguy.freeminecraftmodels.config.props.PropScriptLuaConfig;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeManager;
import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.freeminecraftmodels.content.FMMPackageRefresher;
import com.magmaguy.freeminecraftmodels.customentity.*;
import com.magmaguy.freeminecraftmodels.customentity.core.OBBHitDetection;
import com.magmaguy.freeminecraftmodels.customentity.core.components.InteractionComponent;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.menus.ModelMenuHelper;
import com.magmaguy.freeminecraftmodels.menus.AdminContentMenu;
import com.magmaguy.freeminecraftmodels.menus.AdminModelListMenu;
import com.magmaguy.freeminecraftmodels.menus.CraftableItemsMenu;
import com.magmaguy.freeminecraftmodels.menus.RecipeDetailMenu;
import com.magmaguy.freeminecraftmodels.listeners.ArmorStandListener;
import com.magmaguy.freeminecraftmodels.listeners.EntityTeleportEvent;
import com.magmaguy.freeminecraftmodels.listeners.CraftifyListener;
import com.magmaguy.freeminecraftmodels.listeners.ModelItemListener;
import com.magmaguy.freeminecraftmodels.listeners.MountDismountListener;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import com.magmaguy.freeminecraftmodels.scripting.PropScriptManager;
import com.magmaguy.freeminecraftmodels.menus.FreeMinecraftModelsFirstTimeSetupMenu;
import com.magmaguy.freeminecraftmodels.menus.FreeMinecraftModelsSetupMenu;
import com.magmaguy.freeminecraftmodels.utils.ConfigurationLocation;
import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.magmacore.initialization.PluginInitializationConfig;
import com.magmaguy.magmacore.initialization.PluginInitializationContext;
import com.magmaguy.magmacore.initialization.PluginInitializationState;
import com.magmaguy.magmacore.nightbreak.NightbreakFirstTimeSetupSpec;
import com.magmaguy.magmacore.nightbreak.NightbreakFirstTimeSetupWarner;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginBootstrap;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginHooks;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginSpec;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

public final class FreeMinecraftModels extends JavaPlugin implements Listener {
    public static final NightbreakPluginSpec NIGHTBREAK_PLUGIN_SPEC = new NightbreakPluginSpec(
            "FreeMinecraftModels",
            "freeminecraftmodels",
            "freeminecraftmodels.*",
            "freeminecraftmodels.*",
            "freeminecraftmodels.*",
            "https://nightbreak.io/plugin/freeminecraftmodels/",
            "Reloaded!",
            true, false, true);
    public static final NightbreakFirstTimeSetupSpec FIRST_TIME_SETUP_SPEC = new NightbreakFirstTimeSetupSpec(
            "FreeMinecraftModels",
            "freeminecraftmodels.*",
            null,
            "/freeminecraftmodels setup",
            "/freeminecraftmodels downloadall",
            "https://nightbreak.io/plugin/freeminecraftmodels/",
            "",
            List.of(),
            List.of());

    @Override
    public void onEnable() {
        Bukkit.getLogger().info(" _______ __                               ___ __   _______           __         __        ");
        Bukkit.getLogger().info("|   |   |__|.-----.-----.----.----.---.-.'  _|  |_|   |   |.-----.--|  |.-----.|  |.-----.");
        Bukkit.getLogger().info("|       |  ||     |  -__|  __|   _|  _  |   _|   _|       ||  _  |  _  ||  -__||  ||__ --|");
        Bukkit.getLogger().info("|__|_|__|__||__|__|_____|____|__| |___._|__| |____|__|_|__||_____|_____||_____||__||_____|");
        Bukkit.getLogger().info("Version " + this.getDescription().getVersion());
        MetadataHandler.PLUGIN = this;
        MagmaCore.checkVersionUpdate("111660", "https://nightbreak.io/plugin/freeminecraftmodels/");
        NightbreakPluginBootstrap.startInitialization(this,
                new PluginInitializationConfig("FreeMinecraftModels", "freeminecraftmodels.*", 12),
                NIGHTBREAK_PLUGIN_SPEC,
                new NightbreakPluginHooks() {
                    @Override
                    public void asyncInitialization(PluginInitializationContext initializationContext) {
                        FreeMinecraftModels.this.asyncInitialization(initializationContext);
                    }

                    @Override
                    public void syncInitialization(PluginInitializationContext initializationContext) {
                        FreeMinecraftModels.this.syncInitialization(initializationContext);
                    }

                    @Override
                    public void onInitializationSuccess() {
                        Bukkit.getLogger().info("[FreeMinecraftModels] Fully initialized!");
                        notifyResourcePackManager();
                    }

                    @Override
                    public void onInitializationFailure(Throwable throwable) {
                        throwable.printStackTrace();
                    }
                });
    }

    @Override
    public void onLoad() {
        MagmaCore.createInstance(this);
    }

    @Override
    public void onDisable() {
        MagmaCore.requestInitializationShutdown(this);
        if (MagmaCore.getInitializationState(this.getName()) == PluginInitializationState.INITIALIZING) {
            Bukkit.getServer().getScheduler().cancelTasks(MetadataHandler.PLUGIN);
            MagmaCore.shutdown(this);
            return;
        }
        // Plugin shutdown logic
        MagmaCore.shutdown(this);
        FileModelConverter.shutdown();
        DisplayModelRegistry.shutdown();
        ModelMenuHelper.shutdown();
        FMMPackage.shutdown();
        FMMPackageRefresher.reset();
        PropScriptManager.shutdown();
        ItemScriptManager.shutdown();
        PropRecipeManager.shutdown();
        ModeledEntity.shutdown();
        ModeledEntitiesClock.shutdown();
        OBBHitDetection.shutdown();
        PropEntity.shutdown();
        DynamicEntity.shutdown();
        ConfigurationLocation.shutdown();
        Bukkit.getServer().getScheduler().cancelTasks(MetadataHandler.PLUGIN);
        HandlerList.unregisterAll(MetadataHandler.PLUGIN);
    }

    private void asyncInitialization(PluginInitializationContext initializationContext) {
        initializationContext.step("Default Config");
        new DefaultConfig();
        initializationContext.step("Content Importer");
        MagmaCore.initializeImporter(this);
        initializationContext.step("Output Folder");
        OutputFolder.initializeConfig();
        initializationContext.step("Models Folder");
        ModelsFolder.initializeConfig();
        initializationContext.step("Content Packages");
        new ContentPackageConfig();
        initializationContext.step("Resource Pack Zip");
        OutputFolder.zipResourcePack();
    }

    private void syncInitialization(PluginInitializationContext initializationContext) {
        initializationContext.step("Event Listeners");
        Bukkit.getPluginManager().registerEvents(new ModeledEntityEvents(), this);
        Bukkit.getPluginManager().registerEvents(new OBBHitDetection(), this);
        Bukkit.getPluginManager().registerEvents(new PropEntity.PropEntityEvents(), this);
        Bukkit.getPluginManager().registerEvents(new EntityTeleportEvent(), this);
        Bukkit.getPluginManager().registerEvents(new ArmorStandListener(), this);
        Bukkit.getPluginManager().registerEvents(new DynamicEntity.ModeledEntityEvents(), this);
        Bukkit.getPluginManager().registerEvents(new InteractionComponent.InteractionComponentEvents(), this);
        Bukkit.getPluginManager().registerEvents(new ModelItemListener(), this);
        Bukkit.getPluginManager().registerEvents(new CraftifyListener(), this);
        ModelMenuHelper.initialize();
        AdminContentMenu.registerEvents(this);
        AdminModelListMenu.registerEvents(this);
        CraftableItemsMenu.registerEvents(this);
        RecipeDetailMenu.registerEvents(this);
        Bukkit.getPluginManager().registerEvents(new MountDismountListener(), this);
        Bukkit.getPluginManager().registerEvents(new NightbreakFirstTimeSetupWarner(this, FIRST_TIME_SETUP_SPEC, DefaultConfig::isSetupDone), this);
        Bukkit.getPluginManager().registerEvents(this, this);

        initializationContext.step("NMS Adapter");
        NMSManager.initializeAdapter(this);

        initializationContext.step("Commands");
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
        manager.registerCommand(new CraftifyCommand());
        manager.registerCommand(new AdminCommand());
        NightbreakPluginBootstrap.registerStandardCommands(this,
                manager,
                NIGHTBREAK_PLUGIN_SPEC,
                FreeMinecraftModelsSetupMenu::createMenu,
                FreeMinecraftModelsFirstTimeSetupMenu::createMenu,
                () -> new java.util.ArrayList<>(FMMPackage.getFmmPackages().values()),
                ReloadCommand::reloadPlugin);

        initializationContext.step("Runtime Tasks");
        ModeledEntitiesClock.start();
        PropEntity.onStartup();
        OBBHitDetection.startProjectileDetection();

        initializationContext.step("Prop Scripting");
        new PropScriptLuaConfig(new File(getDataFolder(), "scripts"));
        PropScriptManager.initialize();
        ItemScriptManager.initialize();
        if (PropScriptManager.getListener() != null) {
            Bukkit.getPluginManager().registerEvents(PropScriptManager.getListener(), this);
        }

        initializationContext.step("Prop Recipes");
        PropRecipeManager.initialize();

        initializationContext.step("Metrics");
        new Metrics(this, 19337);
    }

    public void reloadImportedContent(CommandSender sender) {
        ModeledEntity.shutdown();
        PropEntity.shutdown();
        DynamicEntity.shutdown();
        FileModelConverter.shutdown();
        FMMPackage.shutdown();
        ConfigurationLocation.shutdown();

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                MagmaCore.initializeImporter(this);
                OutputFolder.initializeConfig();
                ModelsFolder.initializeConfig();
                new ContentPackageConfig();
                OutputFolder.zipResourcePack();

                Bukkit.getScheduler().runTask(this, () -> {
                    PropEntity.onStartup();
                    notifyResourcePackManager();
                    if (sender != null) {
                        com.magmaguy.magmacore.util.Logger.sendMessage(sender, "Reloaded!");
                    }
                });
            } catch (Exception exception) {
                exception.printStackTrace();
                Bukkit.getScheduler().runTask(this, () -> {
                    if (sender != null) {
                        com.magmaguy.magmacore.util.Logger.sendMessage(sender, "&cFailed to reload FreeMinecraftModels. Check the console.");
                    }
                });
            }
        });
    }

    private static void notifyResourcePackManager() {
        if (!Bukkit.getPluginManager().isPluginEnabled("ResourcePackManager")) return;
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "resourcepackmanager reload");
        } catch (Exception e) {
            Bukkit.getLogger().warning("[FreeMinecraftModels] Failed to notify ResourcePackManager to reload: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamagedByEntityEvent(EntityDamageByEntityEvent event) {
        if (!event.isCancelled()) return;
        if (!(event.getEntity() instanceof LivingEntity livingEntity)) return;
        if (DynamicEntity.isDynamicEntity(livingEntity))
            event.setCancelled(false);
    }
}
