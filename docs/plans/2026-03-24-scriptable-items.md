# Scriptable Custom Items — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a Lua-scriptable custom item system where lone `.json` files in the models folder define custom items with configurable material, enchantments, lore, and 22 gameplay hooks that activate when players equip items and deactivate when they unequip them.

**Architecture:** Extends the existing MagmaCore Lua scripting engine. Items are detected during model folder scan (lone `.json` = custom item). Each item type gets a `ScriptableItem` bound to a per-player `ScriptInstance` that activates on equip and deactivates on unequip. An `ItemScriptListener` listens to Bukkit events and routes them to active scripts via hook dispatch. A new `LuaPlayerUITable` in MagmaCore adds boss bar, action bar, and title APIs with tick-based auto-dismiss.

**Tech Stack:** MagmaCore Lua engine (LuaJ), Spigot API events, PDC for item identification, `CustomConfigFields` for YML config.

---

### Task 1: LuaPlayerUITable in MagmaCore — boss bar, action bar, title

**Files:**
- Create: `Magmacore/core/src/main/java/com/magmaguy/magmacore/scripting/tables/LuaPlayerUITable.java`
- Modify: `Magmacore/core/src/main/java/com/magmaguy/magmacore/scripting/tables/LuaLivingEntityTable.java`

**Step 1: Create LuaPlayerUITable**

This class adds UI methods to a player's Lua table. Called from `LuaLivingEntityTable.build()` when entity is a Player.

```java
package com.magmaguy.magmacore.scripting.tables;

import com.magmaguy.magmacore.MagmaCore;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LuaPlayerUITable {

    // Track active boss bars per player so they can be hidden
    private static final Map<UUID, BossBar> activeBossBars = new ConcurrentHashMap<>();
    private static final Map<UUID, BukkitTask> activeActionBars = new ConcurrentHashMap<>();

    public static void addTo(LuaTable table, Player player) {
        // show_boss_bar(text, color, progress, [ticks])
        table.set("show_boss_bar", LuaTableSupport.tableMethod(table, args -> {
            String text = args.checkjstring(1);
            String colorName = args.checkjstring(2);
            double progress = args.checkdouble(3);
            int ticks = args.narg() >= 4 && !args.arg(4).isnil() ? args.checkint(4) : -1;

            Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                // Remove existing
                BossBar existing = activeBossBars.remove(player.getUniqueId());
                if (existing != null) { existing.removePlayer(player); existing.setVisible(false); }

                BarColor color;
                try { color = BarColor.valueOf(colorName.toUpperCase()); }
                catch (Exception e) { color = BarColor.WHITE; }

                BossBar bar = Bukkit.createBossBar(text, color, BarStyle.SOLID);
                bar.setProgress(Math.max(0, Math.min(1, progress)));
                bar.addPlayer(player);
                bar.setVisible(true);
                activeBossBars.put(player.getUniqueId(), bar);

                if (ticks > 0) {
                    Bukkit.getScheduler().runTaskLater(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                        BossBar b = activeBossBars.remove(player.getUniqueId());
                        if (b != null) { b.removePlayer(player); b.setVisible(false); }
                    }, ticks);
                }
            });
            return LuaValue.NIL;
        }));

        // hide_boss_bar()
        table.set("hide_boss_bar", LuaTableSupport.tableMethod(table, args -> {
            Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                BossBar bar = activeBossBars.remove(player.getUniqueId());
                if (bar != null) { bar.removePlayer(player); bar.setVisible(false); }
            });
            return LuaValue.NIL;
        }));

        // show_action_bar(text, [ticks])
        table.set("show_action_bar", LuaTableSupport.tableMethod(table, args -> {
            String text = args.checkjstring(1);
            int ticks = args.narg() >= 2 && !args.arg(2).isnil() ? args.checkint(2) : -1;

            Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                // Cancel existing repeating task
                BukkitTask existing = activeActionBars.remove(player.getUniqueId());
                if (existing != null) existing.cancel();

                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));

                if (ticks > 0) {
                    // Re-send every 40 ticks (2 sec) to keep it visible, cancel after total ticks
                    BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                            MagmaCore.getInstance().getRequestingPlugin(), () ->
                                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text)),
                            40L, 40L);
                    activeActionBars.put(player.getUniqueId(), task);
                    Bukkit.getScheduler().runTaskLater(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                        BukkitTask t = activeActionBars.remove(player.getUniqueId());
                        if (t != null) t.cancel();
                    }, ticks);
                }
            });
            return LuaValue.NIL;
        }));

        // show_title(title, subtitle, fade_in, stay, fade_out)
        table.set("show_title", LuaTableSupport.tableMethod(table, args -> {
            String title = args.checkjstring(1);
            String subtitle = args.optjstring(2, "");
            int fadeIn = args.optint(3, 10);
            int stay = args.optint(4, 70);
            int fadeOut = args.optint(5, 20);
            Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () ->
                    player.sendTitle(title, subtitle, fadeIn, stay, fadeOut));
            return LuaValue.NIL;
        }));
    }

    /**
     * Clean up all UI state for a player (call on quit/shutdown).
     */
    public static void cleanup(UUID playerId) {
        BossBar bar = activeBossBars.remove(playerId);
        if (bar != null) { bar.removeAll(); bar.setVisible(false); }
        BukkitTask task = activeActionBars.remove(playerId);
        if (task != null) task.cancel();
    }
}
```

**Step 2: Wire into LuaLivingEntityTable**

In `LuaLivingEntityTable.java`, inside the `if (entity instanceof Player player)` block (after line 96), add:

```java
LuaPlayerUITable.addTo(table, player);
```

Add import: `import com.magmaguy.magmacore.scripting.tables.LuaPlayerUITable;`

**Step 3: Compile MagmaCore**

Run: `mvn compile -q -f Magmacore/pom.xml`
Expected: Only warnings, no errors.

**Step 4: Install MagmaCore to local maven**

Run: `mvn install -q -f Magmacore/pom.xml -DskipTests`

**Step 5: Commit (in MagmaCore repo)**

```bash
cd Magmacore && git add -A && git commit -m "feat: add LuaPlayerUITable — boss bar, action bar, title with tick auto-dismiss"
```

---

### Task 2: ItemScriptConfigFields — YML config for custom items

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/config/items/ItemScriptConfigFields.java`

**Step 1: Create the config class**

Follows the EliteMobs pattern for material + enchantments, plus scripts list from prop pattern.

```java
package com.magmaguy.freeminecraftmodels.config.items;

import com.magmaguy.magmacore.config.CustomConfigFields;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ItemScriptConfigFields extends CustomConfigFields {

    @Getter private String material = "PAPER";
    @Getter private String itemName = "";
    @Getter private List<String> lore = new ArrayList<>();
    @Getter private List<String> enchantments = new ArrayList<>();
    @Getter private List<String> scripts = new ArrayList<>();

    public ItemScriptConfigFields(String filename, boolean isEnabled) {
        super(filename, isEnabled);
    }

    @Override
    public void processConfigFields() {
        this.isEnabled = processBoolean("isEnabled", isEnabled, true, true);
        this.material = processString("material", material, "PAPER", true);
        this.itemName = processString("name", itemName, "", true);
        this.lore = processStringList("lore", lore, new ArrayList<>(), true);
        this.enchantments = processStringList("enchantments", enchantments, new ArrayList<>(), true);
        this.scripts = processStringList("scripts", scripts, new ArrayList<>(), true);
    }

    /**
     * Parses the enchantments list (format: "ENCHANTMENT_NAME,LEVEL") into a map.
     */
    public Map<Enchantment, Integer> getParsedEnchantments() {
        Map<Enchantment, Integer> result = new LinkedHashMap<>();
        for (String entry : enchantments) {
            String[] parts = entry.split(",", 2);
            if (parts.length != 2) continue;
            Enchantment ench = Enchantment.getByName(parts[0].trim().toUpperCase());
            if (ench == null) continue;
            try {
                result.put(ench, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    /**
     * Parses the material string into a Bukkit Material, defaulting to PAPER.
     */
    public Material getParsedMaterial() {
        try {
            return Material.valueOf(material.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Material.PAPER;
        }
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/config/items/ItemScriptConfigFields.java
git commit -m "feat: add ItemScriptConfigFields — YML config for custom items"
```

---

### Task 3: ScriptableItem + ItemScriptProvider + LuaItemTable

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/scripting/ScriptableItem.java`
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/scripting/ItemScriptProvider.java`
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/scripting/LuaItemTable.java`

**Step 1: Create ScriptableItem**

Extends `ScriptableEntity`. Context key is `"item"`. Holds the player and itemId. Note: items aren't entities, so `getBukkitEntity()` returns null and `getLocation()` returns the player's location.

```java
package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.magmacore.scripting.ScriptHook;
import com.magmaguy.magmacore.scripting.ScriptInstance;
import com.magmaguy.magmacore.scripting.ScriptableEntity;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;

import java.util.Set;

public class ScriptableItem extends ScriptableEntity {

    // All 22 item-specific hooks
    public static final ScriptHook ON_EQUIP = new ScriptHook("on_equip");
    public static final ScriptHook ON_UNEQUIP = new ScriptHook("on_unequip");
    public static final ScriptHook ON_ATTACK_ENTITY = new ScriptHook("on_attack_entity");
    public static final ScriptHook ON_KILL_ENTITY = new ScriptHook("on_kill_entity");
    public static final ScriptHook ON_TAKE_DAMAGE = new ScriptHook("on_take_damage");
    public static final ScriptHook ON_SHIELD_BLOCK = new ScriptHook("on_shield_block");
    public static final ScriptHook ON_SHOOT_BOW = new ScriptHook("on_shoot_bow");
    public static final ScriptHook ON_PROJECTILE_HIT = new ScriptHook("on_projectile_hit");
    public static final ScriptHook ON_PROJECTILE_LAUNCH = new ScriptHook("on_projectile_launch");
    public static final ScriptHook ON_RIGHT_CLICK = new ScriptHook("on_right_click");
    public static final ScriptHook ON_LEFT_CLICK = new ScriptHook("on_left_click");
    public static final ScriptHook ON_SHIFT_RIGHT_CLICK = new ScriptHook("on_shift_right_click");
    public static final ScriptHook ON_SHIFT_LEFT_CLICK = new ScriptHook("on_shift_left_click");
    public static final ScriptHook ON_INTERACT_ENTITY = new ScriptHook("on_interact_entity");
    public static final ScriptHook ON_SWAP_HANDS = new ScriptHook("on_swap_hands");
    public static final ScriptHook ON_DROP = new ScriptHook("on_drop");
    public static final ScriptHook ON_BREAK_BLOCK = new ScriptHook("on_break_block");
    public static final ScriptHook ON_CONSUME = new ScriptHook("on_consume");
    public static final ScriptHook ON_ITEM_DAMAGE = new ScriptHook("on_item_damage");
    public static final ScriptHook ON_FISH = new ScriptHook("on_fish");
    public static final ScriptHook ON_DEATH = new ScriptHook("on_death");

    private static final Set<ScriptHook> SUPPORTED_HOOKS = Set.of(
            ScriptHook.ON_TICK,
            ON_EQUIP, ON_UNEQUIP, ON_ATTACK_ENTITY, ON_KILL_ENTITY,
            ON_TAKE_DAMAGE, ON_SHIELD_BLOCK, ON_SHOOT_BOW, ON_PROJECTILE_HIT,
            ON_PROJECTILE_LAUNCH, ON_RIGHT_CLICK, ON_LEFT_CLICK,
            ON_SHIFT_RIGHT_CLICK, ON_SHIFT_LEFT_CLICK, ON_INTERACT_ENTITY,
            ON_SWAP_HANDS, ON_DROP, ON_BREAK_BLOCK, ON_CONSUME,
            ON_ITEM_DAMAGE, ON_FISH, ON_DEATH
    );

    private final Player player;
    private final String itemId;

    public ScriptableItem(Player player, String itemId) {
        this.player = player;
        this.itemId = itemId;
    }

    public Player getPlayer() { return player; }
    public String getItemId() { return itemId; }

    @Override
    public LuaTable buildContextTable(ScriptInstance instance) {
        return LuaItemTable.build(player, itemId);
    }

    @Override
    public String getContextKey() { return "item"; }

    @Override
    public Set<ScriptHook> getSupportedHooks() { return SUPPORTED_HOOKS; }

    @Override
    public Entity getBukkitEntity() { return null; }

    @Override
    public Location getLocation() { return player.getLocation(); }

    @Override
    public LuaValue resolveExtraContext(String key, ScriptInstance instance) {
        if ("player".equals(key)) return LuaLivingEntityTable.build(player);
        return LuaValue.NIL;
    }

    @Override
    public void onShutdown() {
        // Clean up player UI if needed
        com.magmaguy.magmacore.scripting.tables.LuaPlayerUITable.cleanup(player.getUniqueId());
    }
}
```

**Step 2: Create ItemScriptProvider**

```java
package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.magmacore.scripting.ScriptHook;
import com.magmaguy.magmacore.scripting.ScriptProvider;

import java.nio.file.Path;

public class ItemScriptProvider implements ScriptProvider {
    private final Path scriptDirectory;

    public ItemScriptProvider(Path scriptDirectory) {
        this.scriptDirectory = scriptDirectory;
    }

    @Override
    public String getNamespace() { return "fmm_items"; }

    @Override
    public Path getScriptDirectory() { return scriptDirectory; }

    @Override
    public ScriptHook resolveHook(String key) {
        return switch (key) {
            case "on_game_tick" -> ScriptHook.ON_TICK;
            case "on_equip" -> ScriptableItem.ON_EQUIP;
            case "on_unequip" -> ScriptableItem.ON_UNEQUIP;
            case "on_attack_entity" -> ScriptableItem.ON_ATTACK_ENTITY;
            case "on_kill_entity" -> ScriptableItem.ON_KILL_ENTITY;
            case "on_take_damage" -> ScriptableItem.ON_TAKE_DAMAGE;
            case "on_shield_block" -> ScriptableItem.ON_SHIELD_BLOCK;
            case "on_shoot_bow" -> ScriptableItem.ON_SHOOT_BOW;
            case "on_projectile_hit" -> ScriptableItem.ON_PROJECTILE_HIT;
            case "on_projectile_launch" -> ScriptableItem.ON_PROJECTILE_LAUNCH;
            case "on_right_click" -> ScriptableItem.ON_RIGHT_CLICK;
            case "on_left_click" -> ScriptableItem.ON_LEFT_CLICK;
            case "on_shift_right_click" -> ScriptableItem.ON_SHIFT_RIGHT_CLICK;
            case "on_shift_left_click" -> ScriptableItem.ON_SHIFT_LEFT_CLICK;
            case "on_interact_entity" -> ScriptableItem.ON_INTERACT_ENTITY;
            case "on_swap_hands" -> ScriptableItem.ON_SWAP_HANDS;
            case "on_drop" -> ScriptableItem.ON_DROP;
            case "on_break_block" -> ScriptableItem.ON_BREAK_BLOCK;
            case "on_consume" -> ScriptableItem.ON_CONSUME;
            case "on_item_damage" -> ScriptableItem.ON_ITEM_DAMAGE;
            case "on_fish" -> ScriptableItem.ON_FISH;
            case "on_death" -> ScriptableItem.ON_DEATH;
            default -> null;
        };
    }
}
```

**Step 3: Create LuaItemTable**

Exposes item manipulation API to Lua scripts. The item is found by scanning the player's equipment for the PDC-tagged item.

```java
package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.List;

public final class LuaItemTable {

    private static final NamespacedKey ITEM_ID_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "fmm_item_id");
    private static final NamespacedKey USES_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "fmm_item_uses");

    private LuaItemTable() {}

    /**
     * Finds the equipped ItemStack matching the given itemId by checking PDC.
     */
    private static ItemStack findEquippedItem(Player player, String itemId) {
        for (ItemStack item : getEquippedItems(player)) {
            if (item == null || !item.hasItemMeta()) continue;
            String id = item.getItemMeta().getPersistentDataContainer()
                    .get(ITEM_ID_KEY, PersistentDataType.STRING);
            if (itemId.equals(id)) return item;
        }
        return null;
    }

    private static ItemStack[] getEquippedItems(Player player) {
        return new ItemStack[]{
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand(),
                player.getInventory().getHelmet(),
                player.getInventory().getChestplate(),
                player.getInventory().getLeggings(),
                player.getInventory().getBoots()
        };
    }

    public static LuaTable build(Player player, String itemId) {
        LuaTable table = new LuaTable();
        table.set("id", itemId);

        // material
        table.set("material", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = findEquippedItem(player, itemId);
            return item != null ? LuaValue.valueOf(item.getType().name()) : LuaValue.NIL;
        }));

        // get_amount()
        table.set("get_amount", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = findEquippedItem(player, itemId);
            return item != null ? LuaValue.valueOf(item.getAmount()) : LuaValue.ZERO;
        }));

        // set_amount(n)
        table.set("set_amount", LuaTableSupport.tableMethod(table, args -> {
            int amount = args.checkint(1);
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item != null) {
                    if (amount <= 0) item.setAmount(0);
                    else item.setAmount(amount);
                }
            });
            return LuaValue.NIL;
        }));

        // consume(n) — decrement amount, remove if 0
        table.set("consume", LuaTableSupport.tableMethod(table, args -> {
            int amount = args.optint(1, 1);
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item != null) {
                    int newAmount = item.getAmount() - amount;
                    if (newAmount <= 0) item.setAmount(0);
                    else item.setAmount(newAmount);
                }
            });
            return LuaValue.NIL;
        }));

        // get_uses() / set_uses(n) — custom counter in PDC
        table.set("get_uses", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = findEquippedItem(player, itemId);
            if (item == null || !item.hasItemMeta()) return LuaValue.ZERO;
            Integer uses = item.getItemMeta().getPersistentDataContainer()
                    .get(USES_KEY, PersistentDataType.INTEGER);
            return LuaValue.valueOf(uses != null ? uses : 0);
        }));

        table.set("set_uses", LuaTableSupport.tableMethod(table, args -> {
            int uses = args.checkint(1);
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item != null) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(USES_KEY, PersistentDataType.INTEGER, uses);
                        item.setItemMeta(meta);
                    }
                }
            });
            return LuaValue.NIL;
        }));

        // get_name() / set_name(s)
        table.set("get_name", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = findEquippedItem(player, itemId);
            if (item == null || !item.hasItemMeta()) return LuaValue.NIL;
            return LuaValue.valueOf(item.getItemMeta().getDisplayName());
        }));

        table.set("set_name", LuaTableSupport.tableMethod(table, args -> {
            String name = args.checkjstring(1);
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item != null) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(ChatColorConverter.convert(name));
                        item.setItemMeta(meta);
                    }
                }
            });
            return LuaValue.NIL;
        }));

        // get_lore() / set_lore(table)
        table.set("get_lore", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = findEquippedItem(player, itemId);
            if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore())
                return new LuaTable();
            LuaTable loreTable = new LuaTable();
            List<String> lore = item.getItemMeta().getLore();
            for (int i = 0; i < lore.size(); i++) {
                loreTable.set(i + 1, lore.get(i));
            }
            return loreTable;
        }));

        table.set("set_lore", LuaTableSupport.tableMethod(table, args -> {
            LuaTable loreTable = args.checktable(1);
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item != null) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        List<String> lore = new ArrayList<>();
                        for (int i = 1; i <= loreTable.length(); i++) {
                            lore.add(ChatColorConverter.convert(loreTable.get(i).tojstring()));
                        }
                        meta.setLore(lore);
                        item.setItemMeta(meta);
                    }
                }
            });
            return LuaValue.NIL;
        }));

        return table;
    }
}
```

**Step 4: Compile FMM**

Run: `mvn compile -q`

**Step 5: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/scripting/ScriptableItem.java \
       src/main/java/com/magmaguy/freeminecraftmodels/scripting/ItemScriptProvider.java \
       src/main/java/com/magmaguy/freeminecraftmodels/scripting/LuaItemTable.java
git commit -m "feat: add ScriptableItem, ItemScriptProvider, LuaItemTable — item scripting core"
```

---

### Task 4: ItemScriptManager — detection, lifecycle, equip tracking

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/scripting/ItemScriptManager.java`

**Step 1: Create ItemScriptManager**

This is the central manager. Responsibilities:
1. During init: scan models folder for lone `.json` files, load their `.yml` configs
2. Track active scripts per player: `Map<UUID, Map<String, ScriptInstance>>`
3. On equip-change events: diff equipped item IDs vs active scripts, start/stop as needed
4. Create items with PDC tag for giving to players

Key data:
- `itemDefinitions: Map<String, ItemScriptConfigFields>` — all known custom items by ID
- `activeScripts: Map<UUID, Map<String, ScriptInstance>>` — active per-player scripts

```java
package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.items.ItemScriptConfigFields;
import com.magmaguy.magmacore.scripting.LuaEngine;
import com.magmaguy.magmacore.scripting.ScriptDefinition;
import com.magmaguy.magmacore.scripting.ScriptInstance;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ItemScriptManager {

    private static final String NAMESPACE = "fmm_items";
    public static final NamespacedKey ITEM_ID_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "fmm_item_id");

    @Getter
    private static final Map<String, ItemScriptConfigFields> itemDefinitions = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, ScriptInstance>> activeScripts = new ConcurrentHashMap<>();

    private static ItemScriptProvider provider;
    private static ItemScriptListener listener;
    private static boolean initialized = false;

    private ItemScriptManager() {}

    /**
     * Called during plugin enable. Registers the script provider and event listener.
     */
    public static void initialize() {
        if (initialized) return;

        File scriptsDir = new File(MetadataHandler.PLUGIN.getDataFolder(), "scripts");
        if (!scriptsDir.exists()) scriptsDir.mkdirs();

        provider = new ItemScriptProvider(scriptsDir.toPath());
        LuaEngine.registerScriptProvider(provider);

        listener = new ItemScriptListener();
        Bukkit.getPluginManager().registerEvents(listener, MetadataHandler.PLUGIN);

        initialized = true;
    }

    /**
     * Scans the models folder for lone .json files (no matching .bbmodel/.fmmodel)
     * and registers them as custom items. Call after model loading is complete.
     */
    public static void scanForCustomItems(File modelsFolder) {
        itemDefinitions.clear();
        if (!modelsFolder.exists()) return;
        scanFolder(modelsFolder);
        if (!itemDefinitions.isEmpty()) {
            Logger.info("Loaded " + itemDefinitions.size() + " custom item definition(s).");
        }
    }

    private static void scanFolder(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanFolder(file);
                continue;
            }
            if (!file.getName().endsWith(".json")) continue;

            // Check if a matching .bbmodel or .fmmodel exists — if so, it's a display model, not a custom item
            String baseName = file.getName().substring(0, file.getName().length() - 5);
            File bbmodel = new File(file.getParentFile(), baseName + ".bbmodel");
            File fmmodel = new File(file.getParentFile(), baseName + ".fmmodel");
            if (bbmodel.exists() || fmmodel.exists()) continue;

            // This is a custom item — load or create the YML config
            String itemId = baseName;
            File ymlFile = new File(file.getParentFile(), baseName + ".yml");

            if (!ymlFile.exists()) {
                // Create default config async
                Bukkit.getScheduler().runTaskAsynchronously(MetadataHandler.PLUGIN, () -> {
                    try {
                        ymlFile.createNewFile();
                        ItemScriptConfigFields defaults = new ItemScriptConfigFields(ymlFile.getName(), true);
                        org.bukkit.configuration.file.FileConfiguration config = new YamlConfiguration();
                        defaults.setFileConfiguration(config);
                        defaults.setFile(ymlFile);
                        defaults.processConfigFields();
                        com.magmaguy.magmacore.config.ConfigurationEngine.fileSaverCustomValues(config, ymlFile);
                    } catch (IOException e) {
                        Logger.warn("[FMM Items] Failed to create default config: " + ymlFile.getName());
                    }
                });
                continue; // Skip this load cycle; will be available next restart
            }

            // Load the YML
            ItemScriptConfigFields config = new ItemScriptConfigFields(ymlFile.getName(), true);
            org.bukkit.configuration.file.FileConfiguration fileConfig = YamlConfiguration.loadConfiguration(ymlFile);
            config.setFileConfiguration(fileConfig);
            config.setFile(ymlFile);
            config.processConfigFields();

            if (!config.isEnabled()) continue;
            itemDefinitions.put(itemId, config);
        }
    }

    /**
     * Creates an ItemStack for a custom item definition.
     */
    public static ItemStack createItemStack(String itemId) {
        ItemScriptConfigFields config = itemDefinitions.get(itemId);
        if (config == null) return null;

        Material material = config.getParsedMaterial();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // Name
        if (!config.getItemName().isEmpty()) {
            meta.setDisplayName(ChatColorConverter.convert(config.getItemName()));
        }

        // Lore
        if (!config.getLore().isEmpty()) {
            meta.setLore(ChatColorConverter.convert(config.getLore()));
        }

        // Enchantments
        for (Map.Entry<Enchantment, Integer> entry : config.getParsedEnchantments().entrySet()) {
            meta.addEnchant(entry.getKey(), entry.getValue(), true);
        }

        // PDC item ID
        meta.getPersistentDataContainer().set(ITEM_ID_KEY, PersistentDataType.STRING, itemId);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Called on equip-change events. Diffs current equipped items against active scripts.
     */
    public static void updateEquippedScripts(Player player) {
        if (!initialized) return;

        UUID uuid = player.getUniqueId();
        Map<String, ScriptInstance> current = activeScripts.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());

        // Collect all item IDs currently in active slots
        Set<String> equippedIds = new HashSet<>();
        for (ItemStack item : getEquippedItems(player)) {
            if (item == null || !item.hasItemMeta()) continue;
            String id = item.getItemMeta().getPersistentDataContainer()
                    .get(ITEM_ID_KEY, PersistentDataType.STRING);
            if (id != null && itemDefinitions.containsKey(id)) {
                equippedIds.add(id);
            }
        }

        // Stop scripts for items no longer equipped
        Iterator<Map.Entry<String, ScriptInstance>> it = current.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ScriptInstance> entry = it.next();
            if (!equippedIds.contains(entry.getKey())) {
                entry.getValue().handleEvent(ScriptableItem.ON_UNEQUIP, null, null, player);
                entry.getValue().shutdown();
                it.remove();
            }
        }

        // Start scripts for newly equipped items
        for (String itemId : equippedIds) {
            if (current.containsKey(itemId)) continue;
            ItemScriptConfigFields config = itemDefinitions.get(itemId);
            if (config == null) continue;

            List<String> scriptNames = config.getScripts();
            if (scriptNames == null || scriptNames.isEmpty()) continue;

            for (String scriptName : scriptNames) {
                ScriptDefinition def = LuaEngine.getDefinition(NAMESPACE, scriptName);
                if (def == null) {
                    Logger.warn("[FMM Items] Script '" + scriptName + "' not found for item " + itemId);
                    continue;
                }
                ScriptableItem scriptable = new ScriptableItem(player, itemId);
                ScriptInstance instance = new ScriptInstance(def, scriptable);
                current.put(itemId, instance);
                instance.handleEvent(ScriptableItem.ON_EQUIP, null, null, player);
            }
        }
    }

    private static ItemStack[] getEquippedItems(Player player) {
        return new ItemStack[]{
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand(),
                player.getInventory().getHelmet(),
                player.getInventory().getChestplate(),
                player.getInventory().getLeggings(),
                player.getInventory().getBoots()
        };
    }

    /**
     * Gets the active ScriptInstance for a player+itemId pair.
     */
    public static ScriptInstance getActiveScript(UUID playerId, String itemId) {
        Map<String, ScriptInstance> scripts = activeScripts.get(playerId);
        if (scripts == null) return null;
        return scripts.get(itemId);
    }

    /**
     * Gets all active ScriptInstances for a player.
     */
    public static Map<String, ScriptInstance> getActiveScripts(UUID playerId) {
        return activeScripts.getOrDefault(playerId, Collections.emptyMap());
    }

    /**
     * Shuts down all scripts for a player (call on quit).
     */
    public static void removePlayer(Player player) {
        Map<String, ScriptInstance> scripts = activeScripts.remove(player.getUniqueId());
        if (scripts != null) {
            for (ScriptInstance instance : scripts.values()) {
                instance.shutdown();
            }
        }
    }

    /**
     * Shuts down everything (plugin disable).
     */
    public static void shutdown() {
        for (Map.Entry<UUID, Map<String, ScriptInstance>> entry : activeScripts.entrySet()) {
            for (ScriptInstance instance : entry.getValue().values()) {
                instance.shutdown();
            }
        }
        activeScripts.clear();
        itemDefinitions.clear();
        if (provider != null) {
            LuaEngine.unregisterScriptProvider(NAMESPACE);
            provider = null;
        }
        listener = null;
        initialized = false;
    }
}
```

**Step 2: Compile and verify**

Run: `mvn compile -q`

**Step 3: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/scripting/ItemScriptManager.java
git commit -m "feat: add ItemScriptManager — detection, lifecycle, equip tracking for custom items"
```

---

### Task 5: ItemScriptListener — equip tracking + hook dispatch

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/scripting/ItemScriptListener.java`

**Step 1: Create ItemScriptListener**

This listener handles two responsibilities:
1. **Equip tracking** — calls `ItemScriptManager.updateEquippedScripts()` on slot change events
2. **Hook dispatch** — routes combat/interaction/utility events to active ScriptInstances

```java
package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.scripting.ScriptInstance;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;

public class ItemScriptListener implements Listener {

    private static final NamespacedKey ITEM_ID_KEY = ItemScriptManager.ITEM_ID_KEY;

    // ── Equip tracking ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent event) {
        ItemScriptManager.updateEquippedScripts(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        // Update equip state first
        ItemScriptManager.updateEquippedScripts(event.getPlayer());
        // Then fire swap hook for the swapped item
        fireForMainHand(event.getPlayer(), ScriptableItem.ON_SWAP_HANDS, event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            // Delay by 1 tick so the inventory state is updated
            org.bukkit.Bukkit.getScheduler().runTaskLater(MetadataHandler.PLUGIN,
                    () -> ItemScriptManager.updateEquippedScripts(player), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        ItemScriptManager.updateEquippedScripts(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ItemScriptManager.removePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        // Fire drop hook
        ItemStack dropped = event.getItemDrop().getItemStack();
        String itemId = getItemId(dropped);
        if (itemId != null) {
            ScriptInstance instance = ItemScriptManager.getActiveScript(
                    event.getPlayer().getUniqueId(), itemId);
            if (instance != null && !instance.isClosed()) {
                instance.handleEvent(ScriptableItem.ON_DROP, event, null, event.getPlayer());
            }
        }
        // Update equip state after drop
        org.bukkit.Bukkit.getScheduler().runTaskLater(MetadataHandler.PLUGIN,
                () -> ItemScriptManager.updateEquippedScripts(event.getPlayer()), 1L);
    }

    // ── Combat hooks ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW)
    public void onAttackEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        fireForMainHand(player, ScriptableItem.ON_ATTACK_ENTITY, event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        fireForMainHand(killer, ScriptableItem.ON_KILL_ENTITY, event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onTakeDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        // Fire for ALL equipped items
        fireForAllEquipped(player, ScriptableItem.ON_TAKE_DAMAGE, event);
        // Shield block check
        if (player.isBlocking()) {
            fireForAllEquipped(player, ScriptableItem.ON_SHIELD_BLOCK, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack bow = event.getBow();
        if (bow == null) return;
        String itemId = getItemId(bow);
        if (itemId != null) {
            ScriptInstance instance = ItemScriptManager.getActiveScript(player.getUniqueId(), itemId);
            if (instance != null && !instance.isClosed()) {
                instance.handleEvent(ScriptableItem.ON_SHOOT_BOW, event, null, player);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        fireForMainHand(player, ScriptableItem.ON_PROJECTILE_LAUNCH, event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile proj = event.getEntity();
        if (!(proj.getShooter() instanceof Player player)) return;
        // Try to get weapon from arrow
        if (proj instanceof AbstractArrow arrow) {
            ItemStack weapon = arrow.getWeapon();
            if (weapon != null) {
                String itemId = getItemId(weapon);
                if (itemId != null) {
                    ScriptInstance instance = ItemScriptManager.getActiveScript(player.getUniqueId(), itemId);
                    if (instance != null && !instance.isClosed()) {
                        instance.handleEvent(ScriptableItem.ON_PROJECTILE_HIT, event, null, player);
                        return;
                    }
                }
            }
        }
        // Fallback: fire for main hand
        fireForMainHand(player, ScriptableItem.ON_PROJECTILE_HIT, event);
    }

    // ── Interaction hooks ───────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();
        boolean shift = player.isSneaking();

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            fireForMainHand(player, shift ? ScriptableItem.ON_SHIFT_RIGHT_CLICK : ScriptableItem.ON_RIGHT_CLICK, event);
        } else if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            fireForMainHand(player, shift ? ScriptableItem.ON_SHIFT_LEFT_CLICK : ScriptableItem.ON_LEFT_CLICK, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        fireForMainHand(event.getPlayer(), ScriptableItem.ON_INTERACT_ENTITY, event);
    }

    // ── Utility hooks ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW)
    public void onBreakBlock(BlockBreakEvent event) {
        fireForMainHand(event.getPlayer(), ScriptableItem.ON_BREAK_BLOCK, event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onConsume(PlayerItemConsumeEvent event) {
        String itemId = getItemId(event.getItem());
        if (itemId != null) {
            ScriptInstance instance = ItemScriptManager.getActiveScript(
                    event.getPlayer().getUniqueId(), itemId);
            if (instance != null && !instance.isClosed()) {
                instance.handleEvent(ScriptableItem.ON_CONSUME, event, null, event.getPlayer());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onItemDamage(PlayerItemDamageEvent event) {
        String itemId = getItemId(event.getItem());
        if (itemId != null) {
            ScriptInstance instance = ItemScriptManager.getActiveScript(
                    event.getPlayer().getUniqueId(), itemId);
            if (instance != null && !instance.isClosed()) {
                instance.handleEvent(ScriptableItem.ON_ITEM_DAMAGE, event, null, event.getPlayer());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onFish(PlayerFishEvent event) {
        fireForMainHand(event.getPlayer(), ScriptableItem.ON_FISH, event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(PlayerDeathEvent event) {
        fireForAllEquipped(event.getEntity(), ScriptableItem.ON_DEATH, event);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private String getItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(ITEM_ID_KEY, PersistentDataType.STRING);
    }

    private void fireForMainHand(Player player, com.magmaguy.magmacore.scripting.ScriptHook hook,
                                 org.bukkit.event.Event event) {
        String itemId = getItemId(player.getInventory().getItemInMainHand());
        if (itemId == null) return;
        ScriptInstance instance = ItemScriptManager.getActiveScript(player.getUniqueId(), itemId);
        if (instance != null && !instance.isClosed()) {
            instance.handleEvent(hook, event, null, player);
        }
    }

    private void fireForAllEquipped(Player player, com.magmaguy.magmacore.scripting.ScriptHook hook,
                                    org.bukkit.event.Event event) {
        Map<String, ScriptInstance> scripts = ItemScriptManager.getActiveScripts(player.getUniqueId());
        for (ScriptInstance instance : scripts.values()) {
            if (!instance.isClosed()) {
                instance.handleEvent(hook, event, null, player);
            }
        }
    }
}
```

**Step 2: Compile and verify**

Run: `mvn compile -q`

**Step 3: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/scripting/ItemScriptListener.java
git commit -m "feat: add ItemScriptListener — equip tracking and 22-hook dispatch"
```

---

### Task 6: Wire into plugin lifecycle + model folder scan

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/config/ModelsFolder.java`

**Step 1: Initialize ItemScriptManager in plugin enable**

In `FreeMinecraftModels.java`, after `PropScriptManager.initialize()` (find with grep), add:
```java
ItemScriptManager.initialize();
```

Add import: `import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;`

**Step 2: Add ItemScriptManager.shutdown() to plugin disable**

Near the other `.shutdown()` calls, add:
```java
ItemScriptManager.shutdown();
```

**Step 3: Trigger custom item scan after models are loaded**

In `ModelsFolder.java`, at the end of `newModelGeneration()` (after the display model processing block), add:

```java
// Scan for lone .json files that define custom items
ItemScriptManager.scanForCustomItems(file);
```

Where `file` is the models root directory parameter already in scope.

Add import: `import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;`

Also add the same call at the end of `legacyHorseArmorGeneration()` for consistency.

**Step 4: Compile and verify**

Run: `mvn compile -q`

**Step 5: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java \
       src/main/java/com/magmaguy/freeminecraftmodels/config/ModelsFolder.java
git commit -m "feat: wire ItemScriptManager into plugin lifecycle and model folder scan"
```

---

### Summary

| # | Task | Files | Purpose |
|---|------|-------|---------|
| 1 | LuaPlayerUITable | MagmaCore: Create 1, Modify 1 | Boss bar, action bar, title with tick auto-dismiss |
| 2 | ItemScriptConfigFields | FMM: Create 1 | YML config: material, name, lore, enchantments, scripts |
| 3 | ScriptableItem + Provider + LuaItemTable | FMM: Create 3 | Scripting core: entity adapter, hook provider, item Lua API |
| 4 | ItemScriptManager | FMM: Create 1 | Detection, lifecycle, equip tracking, item creation |
| 5 | ItemScriptListener | FMM: Create 1 | Equip tracking events + 22-hook Bukkit event dispatch |
| 6 | Wire into plugin | FMM: Modify 2 | Init, shutdown, model folder scan integration |

**Dependency chain:** 1 (MagmaCore) → 2 → 3 → 4 → 5 → 6
