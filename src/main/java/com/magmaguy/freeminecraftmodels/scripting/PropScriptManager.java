package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.magmacore.scripting.LuaEngine;
import com.magmaguy.magmacore.scripting.ScriptDefinition;
import com.magmaguy.magmacore.scripting.ScriptInstance;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;

import java.nio.file.Path;

/**
 * Manages the lifecycle of Lua scripts bound to props in FreeMinecraftModels.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Initializes the {@link PropScriptProvider} and registers it with {@link LuaEngine}</li>
 *   <li>Creates {@link ScriptInstance} and {@link ScriptableProp} when a prop spawns with an associated script</li>
 *   <li>Shuts down script instances when props are removed</li>
 * </ul>
 */
public final class PropScriptManager {

    private static final String NAMESPACE = "fmm";

    @Getter
    private static PropScriptListener listener;
    private static PropScriptProvider provider;
    private static boolean initialized = false;

    private PropScriptManager() {}

    /**
     * Called during plugin enable. Sets up the script provider and registers it
     * with the Lua engine so that scripts in the {@code scripts/} directory are discovered.
     */
    public static void initialize() {
        if (initialized) return;

        Path scriptDir = MetadataHandler.PLUGIN.getDataFolder().toPath().resolve("scripts");
        if (!scriptDir.toFile().exists()) {
            scriptDir.toFile().mkdirs();
        }

        provider = new PropScriptProvider(scriptDir);
        LuaEngine.registerScriptProvider(provider);

        listener = new PropScriptListener();

        initialized = true;
    }

    /**
     * Attempts to bind a Lua script to a prop. The script is resolved by filename convention:
     * the prop's entity ID with a {@code .lua} extension (e.g., {@code my_prop.lua}).
     *
     * @param prop the prop entity to bind a script to
     */
    public static void onPropSpawn(PropEntity prop) {
        if (!initialized) return;

        String scriptFileName = prop.getEntityID() + ".lua";
        ScriptDefinition definition = LuaEngine.getDefinition(NAMESPACE, scriptFileName);
        if (definition == null) return;

        ScriptableProp scriptable = new ScriptableProp(prop);
        ScriptInstance instance = new ScriptInstance(definition, scriptable);

        listener.register(prop, instance);

        // Fire ON_SPAWN hook
        instance.handleEvent(
                com.magmaguy.magmacore.scripting.ScriptHook.ON_SPAWN,
                null, null, null);

        Logger.info("[FMM Scripts] Bound script '" + scriptFileName + "' to prop '" + prop.getEntityID() + "'");
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
}
