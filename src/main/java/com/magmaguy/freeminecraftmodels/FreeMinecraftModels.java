package com.magmaguy.freeminecraftmodels;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.commands.*;
import com.magmaguy.freeminecraftmodels.config.contentpackages.ContentPackageConfig;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry;
import com.magmaguy.freeminecraftmodels.config.ModelsFolder;
import com.magmaguy.freeminecraftmodels.config.OutputFolder;
import com.magmaguy.freeminecraftmodels.config.ShopConfig;
import com.magmaguy.freeminecraftmodels.config.props.PropScriptLuaConfig;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeManager;
import com.magmaguy.freeminecraftmodels.shop.ShopMenu;
import com.magmaguy.freeminecraftmodels.shop.VaultEconomyHook;
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
import com.magmaguy.freeminecraftmodels.listeners.DisguiseListeners;
import com.magmaguy.freeminecraftmodels.listeners.FreeMinecraftModelsFirstTimeSetupWarner;
import com.magmaguy.freeminecraftmodels.listeners.ModelItemListener;
import com.magmaguy.freeminecraftmodels.listeners.MountDismountListener;
import com.magmaguy.freeminecraftmodels.magic.BundledMagicContent;
import com.magmaguy.freeminecraftmodels.magic.MagicEnchantmentCatalog;
import com.magmaguy.freeminecraftmodels.magic.MagicWeaponRuntime;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import com.magmaguy.freeminecraftmodels.scripting.PropInventoryListener;
import com.magmaguy.freeminecraftmodels.scripting.PropScriptManager;
import com.magmaguy.freeminecraftmodels.menus.FreeMinecraftModelsFirstTimeSetupMenu;
import com.magmaguy.freeminecraftmodels.menus.FreeMinecraftModelsSetupMenu;
import com.magmaguy.freeminecraftmodels.utils.ConfigurationLocation;
import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.magmacore.enchantments.EnchantmentCatalog;
import com.magmaguy.magmacore.initialization.PluginInitializationConfig;
import com.magmaguy.magmacore.initialization.PluginInitializationContext;
import com.magmaguy.magmacore.initialization.PluginInitializationState;
import com.magmaguy.magmacore.nightbreak.NightbreakFirstTimeSetupSpec;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginBootstrap;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginHooks;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginSpec;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FreeMinecraftModels extends JavaPlugin {
    private final AtomicBoolean importedContentReloadInProgress =
            new AtomicBoolean(false);
    private MagicWeaponRuntime magicWeaponRuntime;
    private MagicEnchantmentCatalog magicEnchantmentCatalog;
    private volatile ItemScriptManager.ItemCatalog pendingItemCatalog;
    private volatile EnchantmentCatalog pendingEnchantmentCatalog;
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
            "/fmm initialize",
            "/fmm setup",
            "/fmm downloadall",
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
        MagmaCore.exportSharedAssets(this);
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
                        importedContentReloadInProgress.set(false);
                        Bukkit.getLogger().info("[FreeMinecraftModels] Fully initialized!");
                        notifyResourcePackManager();
                        // Notify consumers (EliteMobs, BetterStructures, etc.) that FMM finished
                        // initializing. On reload, they'll need to re-attach custom models to
                        // their surviving underlying entities (NPCs/bosses/etc.); on first
                        // startup the consumers' entity registries are still empty so the event
                        // is a harmless no-op for them.
                        Bukkit.getScheduler().runTaskLater(FreeMinecraftModels.this, () ->
                                Bukkit.getPluginManager().callEvent(new com.magmaguy.freeminecraftmodels.api.FmmReloadedEvent()), 40L);
                    }

                    @Override
                    public void onInitializationFailure(Throwable throwable) {
                        pendingItemCatalog = null;
                        pendingEnchantmentCatalog = null;
                        if (magicEnchantmentCatalog != null) magicEnchantmentCatalog.close();
                        magicEnchantmentCatalog = null;
                        importedContentReloadInProgress.set(false);
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
        boolean shutdownDuringInitialization =
                MagmaCore.getInitializationState(this.getName())
                        == PluginInitializationState.INITIALIZING;
        MagmaCore.requestInitializationShutdown(this);
        if (magicWeaponRuntime != null) magicWeaponRuntime.close();
        magicWeaponRuntime = null;
        if (magicEnchantmentCatalog != null) magicEnchantmentCatalog.close();
        magicEnchantmentCatalog = null;
        ModeledEntitiesClock.shutdown();
        OBBHitDetection.shutdown();
        Bukkit.getServer().getScheduler().cancelTasks(MetadataHandler.PLUGIN);
        if (shutdownDuringInitialization) {
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
        PropInventoryListener.shutdown();
        ModelItemListener.shutdown();
        PropScriptManager.shutdown();
        ItemScriptManager.shutdown();
        PropRecipeManager.shutdown();
        DisguiseManager.shutdown();
        ModeledEntity.shutdown();
        PropEntity.shutdown();
        DynamicEntity.shutdown();
        ConfigurationLocation.shutdown();
        HandlerList.unregisterAll(MetadataHandler.PLUGIN);
    }

    private void asyncInitialization(PluginInitializationContext initializationContext) {
        initializationContext.step("Default Config");
        new DefaultConfig();
        initializationContext.step("Shop Config");
        new ShopConfig();
        initializationContext.step("Content Importer");
        MagmaCore.initializeImporter(this);
        initializationContext.step("Output Folder");
        OutputFolder.initializeConfig();
        initializationContext.step("Bundled Magic Content");
        BundledMagicContent.installDefaults(this);
        if (pendingEnchantmentCatalog == null) {
            try {
                pendingEnchantmentCatalog = MagicEnchantmentCatalog.prepare(this);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Invalid FMM enchantment catalog", failure);
            }
        }
        initializationContext.step("Models Folder");
        ItemScriptManager.ItemCatalog candidate = pendingItemCatalog;
        pendingItemCatalog = null;
        if (candidate == null) ModelsFolder.initializeConfig();
        else ModelsFolder.initializeConfig(candidate);
        initializationContext.step("Content Packages");
        new ContentPackageConfig();
        initializationContext.step("Resource Pack Zip");
        OutputFolder.zipResourcePack();
    }

    private void syncInitialization(PluginInitializationContext initializationContext) {
        initializationContext.step("Authored Items and Enchantments");
        magicEnchantmentCatalog = new MagicEnchantmentCatalog(this,
                java.util.Objects.requireNonNull(pendingEnchantmentCatalog, "prepared enchantment catalog"),
                request -> magicWeaponRuntime != null && magicWeaponRuntime.applyEnchantmentDamage(request));
        pendingEnchantmentCatalog = null;
        initializationContext.step("Event Listeners");
        Bukkit.getPluginManager().registerEvents(new OBBHitDetection(), this);
        Bukkit.getPluginManager().registerEvents(new PropEntity.PropEntityEvents(), this);
        Bukkit.getPluginManager().registerEvents(new EntityTeleportEvent(), this);
        Bukkit.getPluginManager().registerEvents(new ArmorStandListener(), this);
        Bukkit.getPluginManager().registerEvents(new DynamicEntity.ModeledEntityEvents(), this);
        Bukkit.getPluginManager().registerEvents(new InteractionComponent.InteractionComponentEvents(), this);
        Bukkit.getPluginManager().registerEvents(new ModelItemListener(), this);
        Bukkit.getPluginManager().registerEvents(new CraftifyListener(), this);
        Bukkit.getPluginManager().registerEvents(new com.magmaguy.freeminecraftmodels.customentity.DisguiseEffectListener(), this);
        ModelMenuHelper.initialize();
        AdminContentMenu.registerEvents(this);
        AdminModelListMenu.registerEvents(this);
        CraftableItemsMenu.registerEvents(this);
        RecipeDetailMenu.registerEvents(this);
        Bukkit.getPluginManager().registerEvents(new MountDismountListener(), this);
        Bukkit.getPluginManager().registerEvents(new DisguiseListeners(), this);
        Bukkit.getPluginManager().registerEvents(new FreeMinecraftModelsFirstTimeSetupWarner(this), this);

        initializationContext.step("Magic Weapons");
        magicWeaponRuntime = new MagicWeaponRuntime(this);
        magicWeaponRuntime.start();

        initializationContext.step("NMS Adapter");
        NMSManager.initializeAdapter(this);

        initializationContext.step("Commands");
        CommandManager manager = new CommandManager(this, "freeminecraftmodels");
        manager.registerCommand(new MountCommand());
        manager.registerCommand(new HitboxDebugCommand());
        manager.registerCommand(new BedrockDebugCommand());
        manager.registerCommand(new com.magmaguy.freeminecraftmodels.commands.PacketDebugCommand());
        manager.registerCommand(new com.magmaguy.freeminecraftmodels.commands.LocationDebugCommand());
        manager.registerCommand(new DeleteAllCommand());
        manager.registerCommand(new ReloadCommand());
        manager.registerCommand(new SpawnCommand());
        manager.registerCommand(new StatsCommand());
        manager.registerCommand(new VersionCommand());
        manager.registerCommand(new FreeMinecraftModelsCommand());
        manager.registerCommand(new DisguiseCommand());
        manager.registerCommand(new UndisguiseCommand());
        manager.registerCommand(new com.magmaguy.freeminecraftmodels.commands.DisguiseListCommand());
        manager.registerCommand(new ItemifyCommand());
        manager.registerCommand(new CraftifyCommand());
        manager.registerCommand(new AdminCommand());
        manager.registerCommand(new GiveItemCommand());

        VaultEconomyHook.initialize();
        if (ShopConfig.isEnabled() && VaultEconomyHook.isEnabled()) {
            manager.registerCommand(new ShopCommand());
            ShopMenu.registerEvents(this);
        }

        NightbreakPluginBootstrap.registerStandardCommands(this,
                manager,
                NIGHTBREAK_PLUGIN_SPEC,
                FreeMinecraftModelsSetupMenu::createMenu,
                FreeMinecraftModelsFirstTimeSetupMenu::createMenu,
                () -> new java.util.ArrayList<>(FMMPackage.getFmmPackages().values()),
                ReloadCommand::reloadPlugin);

        initializationContext.step("Runtime Tasks");
        ModeledEntitiesClock.start();
        OBBHitDetection.startProjectileDetection();

        initializationContext.step("Prop Scripting");
        new PropScriptLuaConfig(new File(getDataFolder(), "scripts"));
        PropScriptManager.initialize();
        com.magmaguy.freeminecraftmodels.scripting.LuaEntityEnricher.register();
        com.magmaguy.freeminecraftmodels.scripting.LuaWorldEnricher.register();
        com.magmaguy.magmacore.location.LocationQueryRegistry.initializeBuiltInProtectionProviders();

        // Scan existing props AFTER script manager is initialized so scripts bind correctly
        PropEntity.onStartup();

        initializationContext.step("Prop Recipes");
        PropRecipeManager.initialize();

        initializationContext.step("Metrics");
        new Metrics(this, 19337);
    }

    public void reloadImportedContent(CommandSender sender) {
        prepareContentReload(sender, false);
    }

    /** Validates item content before the public full plugin reload clears its live state. */
    public void reloadPlugin(CommandSender sender) {
        prepareContentReload(sender, true);
    }

    private void prepareContentReload(CommandSender sender, boolean fullReload) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(
                    this,
                    () -> prepareContentReload(sender, fullReload));
            return;
        }
        if (!importedContentReloadInProgress.compareAndSet(false, true)) {
            if (sender != null) {
                com.magmaguy.magmacore.util.Logger.sendMessage(
                        sender,
                        "&eA FreeMinecraftModels content reload is already running.");
            }
            return;
        }

        // Import and validate authored items while the current catalog remains usable.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                MagmaCore.initializeImporter(this);
                BundledMagicContent.installDefaults(this);
                ItemScriptManager.ItemCatalog candidate = ItemScriptManager.prepareCatalog(ModelsFolder.resolveModelsFolder());
                EnchantmentCatalog enchantments = MagicEnchantmentCatalog.prepare(this);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (fullReload) {
                        pendingItemCatalog = candidate;
                        pendingEnchantmentCatalog = enchantments;
                        NightbreakPluginBootstrap.reloadPlugin(this, sender);
                    } else {
                        reloadValidatedContent(sender, candidate, enchantments);
                    }
                });
            } catch (Exception failure) {
                Bukkit.getScheduler().runTask(this, () -> {
                    importedContentReloadInProgress.set(false);
                    getLogger().warning("Content reload rejected; the current catalog remains active: " + failure.getMessage());
                    if (sender != null) com.magmaguy.magmacore.util.Logger.sendMessage(sender,
                            "&cContent reload rejected; the current catalog remains active. " + failure.getMessage());
                });
            }
        });
    }

    private void reloadValidatedContent(CommandSender sender, ItemScriptManager.ItemCatalog candidate,
                                        EnchantmentCatalog enchantments) {
        // Stop every task that can observe the live entity/model registries
        // before clearing them. In particular, the one-tick model clock is
        // asynchronous and otherwise races this teardown.
        if (magicWeaponRuntime != null) magicWeaponRuntime.pauseForContentReload();
        ModeledEntitiesClock.shutdown();
        OBBHitDetection.pauseProjectileDetection();
        ModelItemListener.shutdown();
        DisguiseManager.shutdown();
        PropInventoryListener.shutdown();
        ModeledEntity.shutdown();
        PropEntity.shutdown();
        DynamicEntity.shutdown();
        PropScriptManager.shutdown();
        ItemScriptManager.shutdown();
        FileModelConverter.shutdown();
        FMMPackage.shutdown();
        ConfigurationLocation.shutdown();

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                OutputFolder.initializeConfig();
                ModelsFolder.initializeConfig(candidate);
                new ContentPackageConfig();
                FMMPackageRefresher.reset();
                OutputFolder.zipResourcePack();

                Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        // ModelsFolder populated the item-definition registry on
                        // the worker. Reinitialize listeners/providers without
                        // clearing that newly built registry.
                        magicEnchantmentCatalog.reload(enchantments);
                        PropScriptManager.initialize();
                        if (magicWeaponRuntime != null) magicWeaponRuntime.resumeAfterContentReload();
                        PropEntity.onStartup();
                        ModeledEntitiesClock.start();
                        OBBHitDetection.startProjectileDetection();
                        notifyResourcePackManager();
                        Bukkit.getPluginManager().callEvent(
                                new com.magmaguy.freeminecraftmodels.api.FmmReloadedEvent());
                        importedContentReloadInProgress.set(false);
                        if (sender != null) {
                            com.magmaguy.magmacore.util.Logger.sendMessage(
                                    sender,
                                    "Reloaded!");
                        }
                    } catch (Throwable throwable) {
                        failImportedContentReload(sender, throwable);
                    }
                });
            } catch (Throwable throwable) {
                Bukkit.getScheduler().runTask(
                        this,
                        () -> failImportedContentReload(sender, throwable));
            }
        });
    }

    private void failImportedContentReload(
            CommandSender sender,
            Throwable throwable) {
        importedContentReloadInProgress.set(false);
        Bukkit.getLogger().severe(
                "[FreeMinecraftModels] Imported-content reload failed; "
                        + "disabling the plugin rather than running with empty "
                        + "or partial model registries.");
        throwable.printStackTrace();
        if (sender != null) {
            com.magmaguy.magmacore.util.Logger.sendMessage(
                    sender,
                    "&cFailed to reload FreeMinecraftModels. Check the console.");
        }
        Bukkit.getPluginManager().disablePlugin(this);
    }

    private static void notifyResourcePackManager() {
        if (!Bukkit.getPluginManager().isPluginEnabled("ResourcePackManager")) return;
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "resourcepackmanager reload");
        } catch (Exception e) {
            Bukkit.getLogger().warning("[FreeMinecraftModels] Failed to notify ResourcePackManager to reload: " + e.getMessage());
        }
    }

}
