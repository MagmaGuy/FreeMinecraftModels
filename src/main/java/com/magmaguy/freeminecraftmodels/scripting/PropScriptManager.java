package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.scripting.LuaEngine;
import com.magmaguy.magmacore.scripting.ScriptDefinition;
import com.magmaguy.magmacore.scripting.ScriptHook;
import com.magmaguy.magmacore.scripting.ScriptInstance;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Manages the lifecycle of Lua scripts bound to props in FreeMinecraftModels.
 * <p>
 * On prop spawn, checks for a sibling YML config next to the model file.
 * If absent, creates a default config asynchronously (no scripts this load).
 * If present, reads the config and binds the listed scripts from the {@code scripts/} folder.
 */
public final class PropScriptManager {

    private static final String NAMESPACE = "fmm";

    @Getter
    private static PropScriptListener listener;
    private static PropScriptProvider provider;
    private static boolean initialized = false;

    private PropScriptManager() {}

    /**
     * Called during plugin enable. Creates the {@code scripts/} directory,
     * registers {@link PropScriptProvider} with {@link LuaEngine}, and
     * creates the event listener.
     */
    public static void initialize() {
        if (initialized) return;

        File scriptsDir = new File(MetadataHandler.PLUGIN.getDataFolder(), "scripts");
        if (!scriptsDir.exists()) scriptsDir.mkdirs();

        Path scriptPath = scriptsDir.toPath();
        provider = new PropScriptProvider(scriptPath);
        LuaEngine.registerScriptProvider(provider);

        listener = new PropScriptListener();

        initialized = true;
    }

    /**
     * Called when a PropEntity is created. Determines the sibling YML path
     * from the model file, loads or creates the config, and binds scripts.
     *
     * @param prop the prop entity that just spawned
     */
    public static void onPropSpawn(PropEntity prop) {
        if (!initialized) return;

        // 1. Find the model file via FileModelConverter
        FileModelConverter converter = FileModelConverter.getConvertedFileModels().get(prop.getEntityID());
        if (converter == null || converter.getSourceFile() == null) return;

        // 2. Compute the sibling YML path (same directory, same base name, .yml extension)
        File modelFile = converter.getSourceFile();
        String baseName = modelFile.getName();
        // Strip model extension (.fmmodel or .bbmodel)
        if (baseName.endsWith(".fmmodel")) baseName = baseName.substring(0, baseName.length() - 8);
        else if (baseName.endsWith(".bbmodel")) baseName = baseName.substring(0, baseName.length() - 8);
        File ymlFile = new File(modelFile.getParentFile(), baseName + ".yml");

        // 3. If YML doesn't exist, create it async with defaults and return (no scripts this load)
        if (!ymlFile.exists()) {
            final File targetFile = ymlFile;
            Bukkit.getScheduler().runTaskAsynchronously(MetadataHandler.PLUGIN, () -> {
                try {
                    targetFile.getParentFile().mkdirs();
                    targetFile.createNewFile();
                    PropScriptConfigFields defaults = new PropScriptConfigFields(targetFile.getName(), true);
                    FileConfiguration config = new YamlConfiguration();
                    defaults.setFileConfiguration(config);
                    defaults.setFile(targetFile);
                    defaults.processConfigFields();
                    ConfigurationEngine.fileSaverCustomValues(config, targetFile);
                } catch (IOException e) {
                    Logger.warn("[FMM Scripts] Failed to create default config: " + targetFile.getName());
                }
            });
            return;
        }

        // 4. Load the existing YML as PropScriptConfigFields
        PropScriptConfigFields configFields = new PropScriptConfigFields(ymlFile.getName(), true);
        FileConfiguration fileConfig = YamlConfiguration.loadConfiguration(ymlFile);
        configFields.setFileConfiguration(fileConfig);
        configFields.setFile(ymlFile);
        configFields.processConfigFields();

        if (!configFields.isEnabled()) return;
        List<String> scripts = configFields.getScripts();
        if (scripts == null || scripts.isEmpty()) return;

        // 5. For each script filename, resolve from scripts/ folder and bind
        for (String scriptFileName : scripts) {
            ScriptDefinition definition = LuaEngine.getDefinition(NAMESPACE, scriptFileName);
            if (definition == null) {
                Logger.warn("[FMM Scripts] Script '" + scriptFileName + "' not found in scripts/ folder (referenced by " + ymlFile.getName() + ")");
                continue;
            }

            // 6. Create ScriptableProp + ScriptInstance, fire ON_SPAWN
            ScriptableProp scriptable = new ScriptableProp(prop);
            ScriptInstance instance = new ScriptInstance(definition, scriptable);

            listener.register(prop, instance);

            instance.handleEvent(ScriptHook.ON_SPAWN, null, null, null);

            //Logger.info("[FMM Scripts] Bound script '" + scriptFileName + "' to prop '" + prop.getEntityID() + "'");
        }
    }

    /**
     * Called by PropEntity's left-click callback. Fires ON_LEFT_CLICK on the
     * script and returns true if the script cancelled the damage.
     *
     * @param prop   the prop that was punched
     * @param player the player who punched it
     * @return true if the script handled and cancelled the damage
     */
    public static boolean onPropLeftClick(PropEntity prop, org.bukkit.entity.Player player) {
        if (!initialized || listener == null) return false;
        ScriptInstance instance = listener.getScriptedProps().get(prop);
        if (instance == null || instance.isClosed()) return false;
        // Create a simple cancellable event wrapper
        CancellableFlag flag = new CancellableFlag();
        instance.handleEvent(ScriptableProp.ON_LEFT_CLICK, flag, player, null);
        return flag.isCancelled();
    }

    /**
     * Shuts down the script instance associated with a prop when it is removed.
     *
     * @param prop the prop entity being removed
     */
    public static void onPropRemove(PropEntity prop) {
        if (!initialized || listener == null) return;
        listener.unregister(prop);
    }

    /**
     * Called during plugin disable. Shuts down all active script instances and
     * unregisters the script provider from the Lua engine.
     */
    public static void shutdown() {
        if (!initialized) return;

        if (listener != null) {
            listener.shutdownAll();
            listener = null;
        }

        LuaEngine.unregisterScriptProvider(NAMESPACE);
        provider = null;
        initialized = false;
    }

    /**
     * Lightweight Bukkit Event that implements Cancellable, used to pass
     * cancel state between Lua scripts and the prop damage callback.
     */
    static class CancellableFlag extends Event implements Cancellable {
        private static final HandlerList HANDLER_LIST = new HandlerList();
        private boolean cancelled = false;

        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
        @Override public HandlerList getHandlers() { return HANDLER_LIST; }
        public static HandlerList getHandlerList() { return HANDLER_LIST; }
    }
}
