package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.magmacore.scripting.ScriptInstance;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the mapping between props and their script instances.
 * Event dispatch is handled through the OBB hit detection system via
 * {@link PropScriptManager#onPropLeftClick} and {@link PropScriptManager#onPropRightClick}.
 */
public class PropScriptListener implements Listener {

    @lombok.Getter
    private final Map<PropEntity, List<ScriptInstance>> scriptedProps = new ConcurrentHashMap<>();

    /**
     * Registers a prop with an active script instance.
     */
    public void register(PropEntity prop, ScriptInstance instance) {
        scriptedProps.computeIfAbsent(prop, k -> new ArrayList<>()).add(instance);
    }

    /**
     * Unregisters a prop and shuts down all its script instances.
     */
    public void unregister(PropEntity prop) {
        List<ScriptInstance> instances = scriptedProps.remove(prop);
        if (instances == null) return;
        for (ScriptInstance instance : instances) {
            if (instance != null && !instance.isClosed()) {
                instance.shutdown();
                Bukkit.getScheduler().runTaskLater(MetadataHandler.PLUGIN, () -> {
                    if (!instance.isClosed()) instance.shutdown();
                }, 200L);
            }
        }
    }

    /**
     * Shuts down all tracked script instances and clears the map.
     */
    public void shutdownAll() {
        for (List<ScriptInstance> instances : scriptedProps.values()) {
            for (ScriptInstance instance : instances) {
                instance.shutdown();
            }
        }
        scriptedProps.clear();
    }
}
