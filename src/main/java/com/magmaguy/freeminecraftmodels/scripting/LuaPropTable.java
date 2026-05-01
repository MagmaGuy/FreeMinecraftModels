package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.commands.ItemifyCommand;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.MountPointManager;
import com.magmaguy.freeminecraftmodels.customentity.core.MountSeat;
import com.magmaguy.freeminecraftmodels.thirdparty.EliteMobsBossSpawner;
import com.magmaguy.magmacore.scripting.tables.LuaEntityTable;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;

import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Builds the {@code context.prop} Lua table exposed to scripts bound to a {@link PropEntity}.
 * Uses {@link LuaEntityTable#build(Entity)} as the base when the prop has a backing armor stand.
 */
public final class LuaPropTable {

    static final NamespacedKey INVENTORY_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "fmm_prop_inventory");
    private static final NamespacedKey INVENTORY_SIZE_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "fmm_prop_inventory_size");
    private static final NamespacedKey BOOK_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "fmm_prop_book");

    private LuaPropTable() {}

    public static LuaTable build(PropEntity prop) {
        // Start from the entity table if we have a backing armor stand
        Entity underlying = prop.getUnderlyingEntity();
        LuaTable table = (underlying != null) ? LuaEntityTable.build(underlying) : new LuaTable();

        // model_id — the blueprint model name
        if (prop.getSkeletonBlueprint() != null) {
            table.set("model_id", prop.getSkeletonBlueprint().getModelName());
        }

        // current_location — always up-to-date
        Location loc = prop.getLocation();
        if (loc != null) {
            table.set("current_location", LuaTableSupport.locationToTable(loc));
        }

        // play_animation(name) — plays the named animation blended and looped
        table.set("play_animation", LuaTableSupport.tableMethod(table, args -> {
            String name = args.checkjstring(1);
            boolean blend = args.optboolean(2, true);
            boolean loop = args.optboolean(3, true);
            boolean success = prop.playAnimation(name, blend, loop);
            return LuaValue.valueOf(success);
        }));

        // stop_animation(name) — stops all current animations
        table.set("stop_animation", LuaTableSupport.tableMethod(table, args -> {
            prop.stopCurrentAnimations();
            return LuaValue.NIL;
        }));

        // hurt_visual() — plays the visual hurt animation (red tint flash), no actual damage
        table.set("hurt_visual", LuaTableSupport.tableMethod(table, args -> {
            if (prop.getSkeleton() != null) {
                prop.getSkeleton().tint();
            }
            return LuaValue.NIL;
        }));

        // pickup() — removes the prop and drops a placement item at its location
        table.set("pickup", LuaTableSupport.tableMethod(table, args -> {
            Location propLoc = prop.getLocation();
            String modelId = prop.getEntityID();
            // Remove the prop entity on the main thread
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                prop.permanentlyRemove();
                // Drop a placement item via the centralized factory
                if (propLoc != null && propLoc.getWorld() != null) {
                    ItemStack item = com.magmaguy.freeminecraftmodels.utils.ModelItemFactory
                            .createModelItem(modelId, Material.PAPER);
                    propLoc.getWorld().dropItemNaturally(propLoc, item);
                }
            });
            return LuaValue.NIL;
        }));

        // has_mount_points() — returns whether this prop has mount point bones
        table.set("has_mount_points", LuaTableSupport.tableMethod(table, args -> {
            MountPointManager mpm = prop.getMountPointManager();
            return LuaValue.valueOf(mpm != null && mpm.hasMountPoints());
        }));

        // mount(player_entity) — mounts a player onto the first available mount point seat
        table.set("mount", LuaTableSupport.tableMethod(table, args -> {
            LuaTable playerTable = args.arg1().checktable();
            UUID uuid = UUID.fromString(playerTable.get("uuid").tojstring());
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) return LuaValue.FALSE;
            MountPointManager mpm = prop.getMountPointManager();
            if (mpm == null || !mpm.hasMountPoints()) return LuaValue.FALSE;
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> mpm.tryMount(player));
            return LuaValue.TRUE;
        }));

        // dismount(player_entity) — dismounts a player from their mount point seat
        table.set("dismount", LuaTableSupport.tableMethod(table, args -> {
            LuaTable playerTable = args.arg1().checktable();
            UUID uuid = UUID.fromString(playerTable.get("uuid").tojstring());
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) return LuaValue.FALSE;
            MountPointManager mpm = prop.getMountPointManager();
            if (mpm == null) return LuaValue.FALSE;
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                for (MountSeat seat : mpm.getSeats()) {
                    if (player.equals(seat.getOccupant())) {
                        if (seat.getVehicle() != null) {
                            mpm.handleDismount(player, seat.getVehicle());
                        }
                        break;
                    }
                }
            });
            return LuaValue.TRUE;
        }));

        // get_passengers() — returns a Lua array of entity tables for mounted players
        table.set("get_passengers", LuaTableSupport.tableMethod(table, args -> {
            LuaTable passengers = new LuaTable();
            MountPointManager mpm = prop.getMountPointManager();
            if (mpm != null) {
                int index = 1;
                for (MountSeat seat : mpm.getSeats()) {
                    if (seat.isOccupied() && seat.getOccupant() != null) {
                        passengers.set(index++, LuaEntityTable.build(seat.getOccupant()));
                    }
                }
            }
            return passengers;
        }));

        // spawn_elitemobs_boss(filename, x, y, z) — spawns an EliteMobs custom boss if EliteMobs is installed
        table.set("spawn_elitemobs_boss", LuaTableSupport.tableMethod(table, args -> {
            String filename = args.checkjstring(1);
            double x = args.checkdouble(2);
            double y = args.checkdouble(3);
            double z = args.checkdouble(4);

            if (!Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) {
                return LuaValue.NIL;
            }

            Location propLoc = prop.getLocation();
            org.bukkit.World world = propLoc != null ? propLoc.getWorld() : null;
            if (world == null) return LuaValue.NIL;

            try {
                LivingEntity spawned = EliteMobsBossSpawner.spawn(filename, new Location(world, x, y, z));
                if (spawned != null) return LuaLivingEntityTable.build(spawned);
            } catch (NoClassDefFoundError ignored) {
                // EliteMobs not on the classpath — install check above should prevent this, but stay safe.
            }
            return LuaValue.NIL;
        }));

        // open_inventory(player, title, rows) — opens a persistent chest inventory
        table.set("open_inventory", LuaTableSupport.tableMethod(table, args -> {
            LuaTable playerTable = args.arg1().checktable();
            String title = args.checkjstring(2);
            int rows = args.optint(3, 3);
            rows = Math.max(1, Math.min(6, rows));

            UUID uuid = UUID.fromString(playerTable.get("uuid").tojstring());
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !(underlying instanceof ArmorStand armorStand)) return LuaValue.FALSE;

            int size = rows * 9;
            final int finalSize = size;
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                Inventory inv = Bukkit.createInventory(null, finalSize, com.magmaguy.magmacore.util.ChatColorConverter.convert(title));

                // Load existing contents from PDC
                String savedData = armorStand.getPersistentDataContainer()
                        .get(INVENTORY_KEY, PersistentDataType.STRING);
                if (savedData != null) {
                    ItemStack[] items = deserializeInventory(savedData);
                    if (items != null) {
                        for (int i = 0; i < Math.min(items.length, finalSize); i++) {
                            if (items[i] != null) inv.setItem(i, items[i]);
                        }
                    }
                }

                // Track this inventory so we can save on close
                PropInventoryListener.initialize();
                PropInventoryListener.trackInventory(player.getUniqueId(), inv, armorStand);
                player.openInventory(inv);
            });
            return LuaValue.TRUE;
        }));

        // place_book(player) — takes the held written book and stores it on the prop
        table.set("place_book", LuaTableSupport.tableMethod(table, args -> {
            LuaTable playerTable = args.arg1().checktable();
            UUID uuid = UUID.fromString(playerTable.get("uuid").tojstring());
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !(underlying instanceof ArmorStand armorStand)) return LuaValue.FALSE;

            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getType() != Material.WRITTEN_BOOK && held.getType() != Material.WRITABLE_BOOK) return;

                // Check if prop already has a book
                if (armorStand.getPersistentDataContainer().has(BOOK_KEY, PersistentDataType.STRING)) return;

                // Serialize and store
                String bookData = serializeItem(held);
                if (bookData == null) return;
                armorStand.getPersistentDataContainer().set(BOOK_KEY, PersistentDataType.STRING, bookData);

                // Remove from player's hand
                held.setAmount(held.getAmount() - 1);
            });
            return LuaValue.TRUE;
        }));

        // read_book(player) — opens the stored book for reading
        table.set("read_book", LuaTableSupport.tableMethod(table, args -> {
            LuaTable playerTable = args.arg1().checktable();
            UUID uuid = UUID.fromString(playerTable.get("uuid").tojstring());
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !(underlying instanceof ArmorStand armorStand)) return LuaValue.FALSE;

            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                String bookData = armorStand.getPersistentDataContainer()
                        .get(BOOK_KEY, PersistentDataType.STRING);
                if (bookData == null) return;

                ItemStack book = deserializeItem(bookData);
                if (book == null || book.getType() != Material.WRITTEN_BOOK) return;

                player.openBook(book);
            });
            return LuaValue.TRUE;
        }));

        // take_book(player) — returns the stored book to the player
        table.set("take_book", LuaTableSupport.tableMethod(table, args -> {
            LuaTable playerTable = args.arg1().checktable();
            UUID uuid = UUID.fromString(playerTable.get("uuid").tojstring());
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !(underlying instanceof ArmorStand armorStand)) return LuaValue.FALSE;

            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                String bookData = armorStand.getPersistentDataContainer()
                        .get(BOOK_KEY, PersistentDataType.STRING);
                if (bookData == null) return;

                ItemStack book = deserializeItem(bookData);
                if (book == null) return;

                armorStand.getPersistentDataContainer().remove(BOOK_KEY);
                player.getInventory().addItem(book);
            });
            return LuaValue.TRUE;
        }));

        // has_book() — returns whether a book is stored on this prop
        table.set("has_book", LuaTableSupport.tableMethod(table, args -> {
            if (!(underlying instanceof ArmorStand armorStand)) return LuaValue.FALSE;
            return LuaValue.valueOf(armorStand.getPersistentDataContainer().has(BOOK_KEY, PersistentDataType.STRING));
        }));

        // drop_inventory() — drops all stored inventory contents at the prop location and clears storage
        // Closes any viewers first to prevent duplication
        table.set("drop_inventory", LuaTableSupport.tableMethod(table, args -> {
            if (!(underlying instanceof ArmorStand armorStand)) return LuaValue.FALSE;

            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                // 1. Force-close all viewers and untrack them so InventoryCloseEvent doesn't re-save
                PropInventoryListener.closeAndUntrackAll(armorStand);

                // 2. Load stored contents from PDC
                String savedData = armorStand.getPersistentDataContainer()
                        .get(INVENTORY_KEY, PersistentDataType.STRING);
                if (savedData == null) return;

                ItemStack[] items = deserializeInventory(savedData);

                // 3. Clear PDC data BEFORE dropping to prevent duplication on double-destroy
                armorStand.getPersistentDataContainer().remove(INVENTORY_KEY);

                // 4. Drop items at prop location
                if (items != null) {
                    Location dropLoc = armorStand.getLocation();
                    if (dropLoc.getWorld() != null) {
                        for (ItemStack item : items) {
                            if (item != null && item.getType() != Material.AIR) {
                                dropLoc.getWorld().dropItemNaturally(dropLoc, item);
                            }
                        }
                    }
                }
            });
            return LuaValue.TRUE;
        }));

        // drop_book() — drops the stored book at the prop location and clears storage
        table.set("drop_book", LuaTableSupport.tableMethod(table, args -> {
            if (!(underlying instanceof ArmorStand armorStand)) return LuaValue.FALSE;

            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                String bookData = armorStand.getPersistentDataContainer()
                        .get(BOOK_KEY, PersistentDataType.STRING);
                if (bookData == null) return;

                armorStand.getPersistentDataContainer().remove(BOOK_KEY);

                ItemStack book = deserializeItem(bookData);
                if (book != null) {
                    Location dropLoc = armorStand.getLocation();
                    if (dropLoc.getWorld() != null) {
                        dropLoc.getWorld().dropItemNaturally(dropLoc, book);
                    }
                }
            });
            return LuaValue.TRUE;
        }));

        // is_viewing_inventory(player) — returns whether the player has this prop's inventory open
        table.set("is_viewing_inventory", LuaTableSupport.tableMethod(table, args -> {
            LuaTable playerTable = args.arg1().checktable();
            UUID uuid = UUID.fromString(playerTable.get("uuid").tojstring());
            if (!(underlying instanceof ArmorStand armorStand)) return LuaValue.FALSE;
            return LuaValue.valueOf(PropInventoryListener.isViewingInventory(uuid, armorStand));
        }));

        // set_persistent_data(key, value) — stores a string value on the prop's armor stand PDC
        table.set("set_persistent_data", LuaTableSupport.tableMethod(table, args -> {
            String key = args.checkjstring(1);
            String value = args.checkjstring(2);
            if (!(underlying instanceof ArmorStand armorStand)) return LuaValue.FALSE;
            NamespacedKey nsKey = new NamespacedKey(MetadataHandler.PLUGIN, "fmm_lua_" + key);
            armorStand.getPersistentDataContainer().set(nsKey, PersistentDataType.STRING, value);
            return LuaValue.TRUE;
        }));

        // get_persistent_data(key) — retrieves a string value from the prop's armor stand PDC
        table.set("get_persistent_data", LuaTableSupport.tableMethod(table, args -> {
            String key = args.checkjstring(1);
            if (!(underlying instanceof ArmorStand armorStand)) return LuaValue.NIL;
            NamespacedKey nsKey = new NamespacedKey(MetadataHandler.PLUGIN, "fmm_lua_" + key);
            String value = armorStand.getPersistentDataContainer().get(nsKey, PersistentDataType.STRING);
            return value != null ? LuaValue.valueOf(value) : LuaValue.NIL;
        }));

        return table;
    }

    static String serializeInventory(Inventory inventory) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeInt(inventory.getSize());
            for (int i = 0; i < inventory.getSize(); i++) {
                dataOutput.writeObject(inventory.getItem(i));
            }
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private static ItemStack[] deserializeInventory(String data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            int size = dataInput.readInt();
            ItemStack[] items = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }
            dataInput.close();
            return items;
        } catch (Exception e) {
            return null;
        }
    }

    private static String serializeItem(ItemStack item) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeObject(item);
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private static ItemStack deserializeItem(String data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            return item;
        } catch (Exception e) {
            return null;
        }
    }
}
