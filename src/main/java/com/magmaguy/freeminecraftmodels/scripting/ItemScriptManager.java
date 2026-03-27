package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.BowStateDetector;
import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import com.magmaguy.magmacore.scripting.LuaEngine;
import com.magmaguy.magmacore.scripting.ScriptDefinition;
import com.magmaguy.magmacore.scripting.ScriptInstance;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for the custom item scripting system.
 * <p>
 * Scans model YML configs for those with a {@code material} field set (custom items).
 * Manages per-player script lifecycles based on equipped items,
 * and creates configured ItemStacks.
 */
public final class ItemScriptManager {

    private static final String NAMESPACE = "fmm";
    public static final NamespacedKey ITEM_ID_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "fmm_item_id");

    private static final EquipmentSlot[] TRACKED_SLOTS = {
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** item id -> config fields (only models with material set) */
    @Getter
    private static final Map<String, PropScriptConfigFields> itemDefinitions = new ConcurrentHashMap<>();

    /** item id -> source .json file location (for menu folder grouping) */
    @Getter
    private static final Map<String, File> itemSourceFiles = new ConcurrentHashMap<>();

    /** player UUID -> (item id -> script instance) */
    private static final Map<UUID, Map<String, ScriptInstance>> activeScripts = new ConcurrentHashMap<>();

    @Getter
    private static ItemScriptListener listener;
    private static ItemScriptProvider provider;
    private static boolean initialized = false;

    private ItemScriptManager() {}

    // ── 1. Initialization ────────────────────────────────────────────────

    /**
     * Called during plugin enable. Creates the {@code scripts/} directory,
     * registers {@link ItemScriptProvider} with {@link LuaEngine}, and
     * creates and registers the {@link ItemScriptListener} with Bukkit.
     */
    public static void initialize() {
        if (initialized) return;

        // No separate provider registration — uses the "fmm" namespace
        // already registered by PropScriptManager, sharing the scripts/ directory.
        // Item hooks are resolved by PropScriptProvider.

        listener = new ItemScriptListener();
        Bukkit.getPluginManager().registerEvents(listener, MetadataHandler.PLUGIN);

        initialized = true;
    }

    // ── 2. Custom item detection ─────────────────────────────────────────

    /**
     * Scans the models folder for YML configs that define custom items
     * (have a material field set). Uses the unified PropScriptConfigFields.
     *
     * @param modelsFolder the root models directory to scan
     */
    public static void scanForCustomItems(File modelsFolder) {
        itemDefinitions.clear();
        itemSourceFiles.clear();
        if (modelsFolder == null || !modelsFolder.isDirectory()) return;
        scanDirectory(modelsFolder);
        if (!itemDefinitions.isEmpty()) {
            Logger.info("Loaded " + itemDefinitions.size() + " custom item definition(s).");
        }
    }

    private static void scanDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file);
                continue;
            }

            if (!file.getName().endsWith(".yml")) continue;

            // Skip draw state YMLs — bow/crossbow states are handled by the base model's YML
            String nameWithoutExt = file.getName().substring(0, file.getName().length() - 4);
            if (BowStateDetector.isDrawStateSuffix(nameWithoutExt)) continue;

            // Load the YML and check if it defines a custom item (has material set)
            PropScriptConfigFields configFields = new PropScriptConfigFields(file.getName(), true);
            FileConfiguration fileConfig = YamlConfiguration.loadConfiguration(file);
            configFields.setFileConfiguration(fileConfig);
            configFields.setFile(file);
            configFields.processConfigFields();

            if (!configFields.isEnabled() || !configFields.isCustomItem()) continue;

            // Item ID is the base filename without .yml
            String baseName = file.getName();
            if (baseName.endsWith(".yml")) baseName = baseName.substring(0, baseName.length() - 4);
            String itemId = baseName;

            // Find the corresponding model file for source tracking
            File bbmodel = new File(file.getParentFile(), baseName + ".bbmodel");
            File fmmodel = new File(file.getParentFile(), baseName + ".fmmodel");
            File sourceFile = bbmodel.exists() ? bbmodel : fmmodel.exists() ? fmmodel : file;

            itemDefinitions.put(itemId, configFields);
            itemSourceFiles.put(itemId, sourceFile);
        }
    }

    // ── 3. Per-player script lifecycle ───────────────────────────────────

    /**
     * Diffs the player's currently equipped items against active scripts.
     * Fires ON_UNEQUIP for items no longer equipped and ON_EQUIP for newly equipped items.
     *
     * @param player the player to update
     */
    public static void updateEquippedScripts(Player player) {
        if (!initialized) return;

        UUID uuid = player.getUniqueId();
        Map<String, ScriptInstance> playerScripts = activeScripts.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());

        // Collect currently equipped item IDs
        Set<String> equippedIds = new HashSet<>();
        PlayerInventory inventory = player.getInventory();

        for (EquipmentSlot slot : TRACKED_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() == Material.AIR) continue;

            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            String itemId = pdc.get(ITEM_ID_KEY, PersistentDataType.STRING);
            if (itemId != null && itemDefinitions.containsKey(itemId)) {
                equippedIds.add(itemId);
            }
        }

        // Unequip: items that were active but are no longer equipped
        Iterator<Map.Entry<String, ScriptInstance>> it = playerScripts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ScriptInstance> entry = it.next();
            if (!equippedIds.contains(entry.getKey())) {
                ScriptInstance instance = entry.getValue();
                if (!instance.isClosed()) {
                    instance.handleEvent(ScriptableItem.ON_UNEQUIP, null, null, player);
                    instance.shutdown();
                }
                it.remove();
            }
        }

        // Equip: items that are equipped but don't have an active script
        for (String itemId : equippedIds) {
            if (playerScripts.containsKey(itemId)) continue;

            PropScriptConfigFields config = itemDefinitions.get(itemId);
            if (config == null) continue;

            List<String> scripts = config.getScripts();
            if (scripts == null || scripts.isEmpty()) continue;

            // Use the first script for the primary instance stored in the map
            // (matching the single-instance-per-item pattern)
            for (String scriptFileName : scripts) {
                // Append .lua if not already present — YML configs may omit the extension
                if (!scriptFileName.toLowerCase(java.util.Locale.ROOT).endsWith(".lua"))
                    scriptFileName = scriptFileName + ".lua";
                ScriptDefinition definition = LuaEngine.getDefinition(NAMESPACE, scriptFileName);
                if (definition == null) {
                    Logger.warn("[FMM Items] Script '" + scriptFileName + "' not found in scripts/ folder (referenced by item '" + itemId + "')");
                    continue;
                }
                ScriptableItem scriptable = new ScriptableItem(player, itemId);
                ScriptInstance instance = new ScriptInstance(definition, scriptable);

                playerScripts.put(itemId, instance);
                instance.handleEvent(ScriptableItem.ON_EQUIP, null, null, player);
                break; // one instance per item id per player
            }
        }

        // Clean up empty maps
        if (playerScripts.isEmpty()) {
            activeScripts.remove(uuid);
        }
    }

    /**
     * Shuts down all script instances for a player (e.g. on quit).
     *
     * @param player the player leaving
     */
    public static void removePlayer(Player player) {
        Map<String, ScriptInstance> playerScripts = activeScripts.remove(player.getUniqueId());
        if (playerScripts == null) return;

        for (ScriptInstance instance : playerScripts.values()) {
            if (!instance.isClosed()) {
                instance.handleEvent(ScriptableItem.ON_UNEQUIP, null, null, player);
                instance.shutdown();
            }
        }
    }

    /**
     * Gets the active script instance for a specific player and item ID.
     *
     * @param playerUUID the player's UUID
     * @param itemId     the item identifier
     * @return the script instance, or null if none is active
     */
    public static ScriptInstance getActiveScript(UUID playerUUID, String itemId) {
        Map<String, ScriptInstance> playerScripts = activeScripts.get(playerUUID);
        if (playerScripts == null) return null;
        return playerScripts.get(itemId);
    }

    /**
     * Gets all active script instances for a player.
     *
     * @param playerUUID the player's UUID
     * @return map of item ID to script instance, or an empty map if none
     */
    public static Map<String, ScriptInstance> getActiveScripts(UUID playerUUID) {
        return activeScripts.getOrDefault(playerUUID, Collections.emptyMap());
    }

    // ── 4. Shutdown ──────────────────────────────────────────────────────

    /**
     * Shuts down all active script instances, clears all maps,
     * and unregisters the script provider from the Lua engine.
     */
    public static void shutdown() {
        if (!initialized) return;

        // Shutdown all player script instances
        for (Map.Entry<UUID, Map<String, ScriptInstance>> playerEntry : activeScripts.entrySet()) {
            for (ScriptInstance instance : playerEntry.getValue().values()) {
                if (!instance.isClosed()) {
                    instance.shutdown();
                }
            }
        }
        activeScripts.clear();
        itemDefinitions.clear();
        itemSourceFiles.clear();

        // Don't unregister script provider — shared "fmm" namespace managed by PropScriptManager
        listener = null;
        initialized = false;
    }
}
