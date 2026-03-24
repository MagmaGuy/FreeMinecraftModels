package com.magmaguy.freeminecraftmodels.config;

import java.util.HashSet;
import java.util.Set;

public final class DisplayModelRegistry {
    private static final Set<String> displayModels = new HashSet<>();
    private DisplayModelRegistry() {}
    public static void register(String modelId) { displayModels.add(modelId); }
    public static boolean hasDisplayModel(String modelId) { return displayModels.contains(modelId); }
    public static void shutdown() { displayModels.clear(); }
}
