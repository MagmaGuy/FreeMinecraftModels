package com.magmaguy.freeminecraftmodels.config;

import java.util.HashSet;
import java.util.Set;

public final class DisplayModelRegistry {
    private static final Set<String> displayModels = new HashSet<>();
    private DisplayModelRegistry() {}
    public static synchronized void register(String modelId) { displayModels.add(modelId); }
    public static synchronized void unregister(String modelId) { displayModels.remove(modelId); }
    public static synchronized boolean hasDisplayModel(String modelId) { return displayModels.contains(modelId); }

    public static synchronized Set<String> getRegisteredModels() {
        return Set.copyOf(displayModels);
    }
    public static synchronized void shutdown() { displayModels.clear(); }
}
