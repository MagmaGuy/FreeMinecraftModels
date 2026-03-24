package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.items.ItemScriptConfigFields;
import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class AdminContentMenu {

    private static final HashMap<Inventory, AdminContentMenu> openMenus = new HashMap<>();

    private final Player player;
    private final List<ContentEntry> entries;
    private final Inventory inventory;
    private int page;

    public AdminContentMenu(Player player) {
        this.player = player;
        this.page = 0;
        this.entries = buildEntries();
        this.inventory = Bukkit.createInventory(null, 54,
                ChatColorConverter.convert("&8FMM Admin - Content"));
        populate();
        openMenus.put(inventory, this);
        player.openInventory(inventory);
    }

    /**
     * Builds a unified list of entries: packages, unpackaged folders, and root-level models.
     */
    private static List<ContentEntry> buildEntries() {
        List<ContentEntry> result = new ArrayList<>();

        // 1. Collect all models claimed by packages
        Set<String> claimedModelIds = new HashSet<>();
        List<FMMPackage> enabledPacks = FMMPackage.getFmmPackages().values().stream()
                .filter(pack -> pack.getContentPackageConfigFields().isEnabled())
                .sorted(Comparator.comparing(pack ->
                        ChatColor.stripColor(ChatColorConverter.convert(pack.getDisplayName()))))
                .collect(Collectors.toList());

        for (FMMPackage pack : enabledPacks) {
            List<FileModelConverter> packModels = ModelMenuHelper.getModelsForPack(pack);
            for (FileModelConverter model : packModels) {
                claimedModelIds.add(model.getID());
            }
            result.add(new PackageEntry(pack));
        }

        // 2. Find unclaimed models and group by parent folder
        File modelsRoot = new File(MetadataHandler.PLUGIN.getDataFolder(), "models");
        Map<String, List<FileModelConverter>> folderGroups = new LinkedHashMap<>();
        List<FileModelConverter> rootModels = new ArrayList<>();

        for (FileModelConverter converter : FileModelConverter.getConvertedFileModels().values()) {
            if (claimedModelIds.contains(converter.getID())) continue;
            if (converter.getSourceFile() == null) continue;

            File parent = converter.getSourceFile().getParentFile();
            if (parent != null && parent.equals(modelsRoot)) {
                // Root-level model
                rootModels.add(converter);
            } else if (parent != null) {
                // In a subfolder — group by immediate subfolder name relative to models root
                File folder = parent;
                while (folder.getParentFile() != null && !folder.getParentFile().equals(modelsRoot)) {
                    folder = folder.getParentFile();
                }
                folderGroups.computeIfAbsent(folder.getName(), k -> new ArrayList<>()).add(converter);
            }
        }

        // 3. Include custom items (lone JSONs) in folder groups and root
        Map<String, List<String>> itemFolderGroups = new LinkedHashMap<>();
        List<String> rootItems = new ArrayList<>();

        for (Map.Entry<String, File> entry : ItemScriptManager.getItemSourceFiles().entrySet()) {
            String itemId = entry.getKey();
            File sourceFile = entry.getValue();
            File parent = sourceFile.getParentFile();

            if (parent != null && parent.equals(modelsRoot)) {
                rootItems.add(itemId);
            } else if (parent != null) {
                File folder = parent;
                while (folder.getParentFile() != null && !folder.getParentFile().equals(modelsRoot)) {
                    folder = folder.getParentFile();
                }
                itemFolderGroups.computeIfAbsent(folder.getName(), k -> new ArrayList<>()).add(itemId);
            }
        }

        // Merge model folder groups and item folder groups into combined entries
        Set<String> allFolderNames = new TreeSet<>();
        allFolderNames.addAll(folderGroups.keySet());
        allFolderNames.addAll(itemFolderGroups.keySet());

        for (String folderName : allFolderNames) {
            List<FileModelConverter> models = folderGroups.getOrDefault(folderName, Collections.emptyList());
            models.sort(Comparator.comparing(FileModelConverter::getID));
            List<String> items = itemFolderGroups.getOrDefault(folderName, Collections.emptyList());
            Collections.sort(items);
            int totalCount = models.size() + items.size();
            result.add(new FolderEntry(folderName, models, items, totalCount));
        }

        // Add root-level models individually (sorted)
        rootModels.stream()
                .sorted(Comparator.comparing(FileModelConverter::getID))
                .forEach(model -> result.add(new SingleModelEntry(model)));

        // Add root-level custom items individually (sorted)
        rootItems.stream()
                .sorted()
                .forEach(itemId -> result.add(new SingleCustomItemEntry(itemId)));

        return result;
    }

    private void populate() {
        inventory.clear();

        int start = page * ModelMenuHelper.ITEMS_PER_PAGE;
        int end = Math.min(start + ModelMenuHelper.ITEMS_PER_PAGE, entries.size());

        for (int i = start; i < end; i++) {
            int slotIndex = i - start;
            inventory.setItem(ModelMenuHelper.CONTENT_SLOTS[slotIndex], entries.get(i).buildDisplayItem());
        }

        if (page > 0) {
            inventory.setItem(ModelMenuHelper.PREV_SLOT, ModelMenuHelper.buildPrevPageItem());
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) entries.size() / ModelMenuHelper.ITEMS_PER_PAGE));
        if (page < totalPages - 1) {
            inventory.setItem(ModelMenuHelper.NEXT_SLOT, ModelMenuHelper.buildNextPageItem());
        }
    }

    public static void registerEvents(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new Events(), plugin);
    }

    public static class Events implements Listener {

        @EventHandler
        public void onInventoryClick(InventoryClickEvent event) {
            Inventory clicked = event.getInventory();
            AdminContentMenu menu = openMenus.get(clicked);
            if (menu == null) return;

            event.setCancelled(true);
            if (event.getClickedInventory() != clicked) return;

            ItemStack item = event.getCurrentItem();
            if (item == null) return;

            int slot = event.getRawSlot();
            Player player = (Player) event.getWhoClicked();

            if (slot == ModelMenuHelper.PREV_SLOT && menu.page > 0) {
                menu.page--;
                menu.populate();
                return;
            }

            int totalPages = Math.max(1, (int) Math.ceil((double) menu.entries.size() / ModelMenuHelper.ITEMS_PER_PAGE));
            if (slot == ModelMenuHelper.NEXT_SLOT && menu.page < totalPages - 1) {
                menu.page++;
                menu.populate();
                return;
            }

            int start = menu.page * ModelMenuHelper.ITEMS_PER_PAGE;
            for (int i = 0; i < ModelMenuHelper.CONTENT_SLOTS.length; i++) {
                if (ModelMenuHelper.CONTENT_SLOTS[i] == slot) {
                    int entryIndex = start + i;
                    if (entryIndex < menu.entries.size()) {
                        openMenus.remove(clicked);
                        menu.entries.get(entryIndex).onClick(player);
                    }
                    return;
                }
            }
        }

        @EventHandler
        public void onInventoryClose(InventoryCloseEvent event) {
            openMenus.remove(event.getInventory());
        }
    }

    // ---------------------------------------------------------------
    // Entry types
    // ---------------------------------------------------------------

    private interface ContentEntry {
        ItemStack buildDisplayItem();
        void onClick(Player player);
    }

    /** An FMMPackage entry — shows pack info, opens pack model list. */
    private static class PackageEntry implements ContentEntry {
        private final FMMPackage pack;

        PackageEntry(FMMPackage pack) {
            this.pack = pack;
        }

        @Override
        public ItemStack buildDisplayItem() {
            return ModelMenuHelper.buildPackItem(pack);
        }

        @Override
        public void onClick(Player player) {
            new AdminModelListMenu(player, pack);
        }
    }

    /** A folder of unclaimed models + custom items — shows folder name, opens model list. */
    private static class FolderEntry implements ContentEntry {
        private final String folderName;
        private final List<FileModelConverter> models;
        private final List<String> customItemIds;
        private final int totalCount;

        FolderEntry(String folderName, List<FileModelConverter> models, List<String> customItemIds, int totalCount) {
            this.folderName = folderName;
            this.models = models;
            this.customItemIds = customItemIds;
            this.totalCount = totalCount;
        }

        @Override
        public ItemStack buildDisplayItem() {
            String name = "&e" + folderName;
            List<String> lore = new ArrayList<>();
            lore.add("&8Folder (no package)");
            lore.add("");
            if (!models.isEmpty()) lore.add("&7Props: &f" + models.size());
            if (!customItemIds.isEmpty()) lore.add("&7Items: &f" + customItemIds.size());
            lore.add("");
            lore.add("&eClick to browse");
            return ItemStackGenerator.generateItemStack(Material.BARREL, name, lore);
        }

        @Override
        public void onClick(Player player) {
            new AdminModelListMenu(player, folderName, models, customItemIds);
        }
    }

    /** A single custom item at root — gives item directly on click. */
    private static class SingleCustomItemEntry implements ContentEntry {
        private final String itemId;

        SingleCustomItemEntry(String itemId) {
            this.itemId = itemId;
        }

        @Override
        public ItemStack buildDisplayItem() {
            return ModelMenuHelper.buildCustomItemDisplayItem(itemId);
        }

        @Override
        public void onClick(Player player) {
            ItemStack item = ItemScriptManager.createItemStack(itemId);
            if (item != null) player.getInventory().addItem(item);
        }
    }

    /** A single model at the root of the models folder. Opens model list with just this one. */
    private static class SingleModelEntry implements ContentEntry {
        private final FileModelConverter model;

        SingleModelEntry(FileModelConverter model) {
            this.model = model;
        }

        @Override
        public ItemStack buildDisplayItem() {
            return ModelMenuHelper.buildModelItem(model, true);
        }

        @Override
        public void onClick(Player player) {
            // Give item directly — no need for a sub-menu for a single model
            player.getInventory().addItem(
                    com.magmaguy.freeminecraftmodels.utils.ModelItemFactory.createModelItem(
                            model.getID(), Material.PAPER));
        }
    }
}
