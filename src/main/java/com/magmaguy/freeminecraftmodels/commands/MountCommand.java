package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.MountPointManager;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.arguments.DoubleCommandArgument;

import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.Bukkit;
import java.util.Collection;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.entity.Entity;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;

import java.util.ArrayList;
import java.util.List;

public class MountCommand extends AdvancedCommand {

    /** Marks a horse this command spawned: it exists for its model and goes with it. */
    public static final NamespacedKey MOUNT_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "mount_horse");

    public MountCommand() {
        super(List.of("mount"));
        addArgument("model", new com.magmaguy.freeminecraftmodels.commands.arguments.LiveModelIDsArgument("<model>"));
        addOptionalArgument("scale", new DoubleCommandArgument(List.of(1.0, 2.0, 3.0), "[scale]"));
        setDescription("Spawns a rideable model horse, at the model's size or scaled");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm mount <model> [scale]");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        String modelID = commandData.getStringArgument("model");

        if (!FileModelConverter.containsModel(modelID)) {
            Logger.sendMessage(player, "\u00a7cModel '" + modelID + "' not found!");
            return;
        }
        Double scale = commandData.getDoubleArgument("scale");
        if (scale != null && (!(scale > 0) || scale > 16)) {
            Logger.sendMessage(player, ChatColor.RED + "Scale must be between 0 and 16.");
            return;
        }

        // Spawn a horse as the rideable base entity
        Horse horse = (Horse) player.getWorld().spawnEntity(player.getLocation(), EntityType.HORSE);
        horse.setTamed(true);
        horse.setOwner(player);
        horse.setAdult();
        horse.getInventory().setSaddle(unseenSaddle());
        horse.setRemoveWhenFarAway(false);
        horse.getPersistentDataContainer().set(MOUNT_KEY, PersistentDataType.BYTE, (byte) 1);
        horse.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.3);
        horse.getAttribute(Attribute.JUMP_STRENGTH).setBaseValue(0.7);
        // The model takes the mob's scale; the saddle lift below then adds the part that only moves the seat.
        if (scale != null) horse.getAttribute(Attribute.SCALE).setBaseValue(scale);

        // Wrap horse with the custom model
        DynamicEntity dynamicEntity = DynamicEntity.createWithInvisibility(modelID, horse);
        if (dynamicEntity == null) {
            horse.remove();
            Logger.sendMessage(player, "\u00a7cFailed to create model for '" + modelID + "'!");
            return;
        }

        // The player rides the horse directly: a horse only takes steering from a player that is its first
        // passenger, so a seat entity in between would leave the rider unable to move it. The horse's saddle is
        // lifted onto the model's first m_ bone instead, so the rider sits on the model's seat.
        Bukkit.getScheduler().scheduleSyncDelayedTask(MetadataHandler.PLUGIN, () -> {
            if (!horse.isValid() || !player.isOnline()) return;
            MountPointManager mounts = dynamicEntity.getMountPointManager();
            if (mounts != null && mounts.hasMountPoints()) dynamicEntity.liftSaddleTo(mounts.getSeats().get(0).getBone());
            horse.addPassenger(player);
        }, 1);
    }

    public static boolean isMountHorse(Entity entity) {
        return entity instanceof Horse && entity.getPersistentDataContainer().has(MOUNT_KEY, PersistentDataType.BYTE);
    }

    /**
     * Removes mount horses that have no model: the plugin does not rebuild a model on a mob loaded from disk, so a
     * horse that outlived its model (a crash, a world saved mid-ride) would stay an invisible saddled horse.
     */
    public static void removeOrphans(Collection<? extends Entity> entities) {
        for (Entity entity : entities)
            if (isMountHorse(entity) && DynamicEntity.getDynamicEntity(entity) == null) entity.remove();
    }

    public static void removeLoadedOrphans() {
        for (World world : Bukkit.getWorlds()) removeOrphans(world.getEntitiesByClass(Horse.class));
    }

    public static class MountHorseEvents implements Listener {
        @EventHandler
        public void onEntitiesLoad(EntitiesLoadEvent event) {
            removeOrphans(event.getEntities());
        }
    }

    /**
     * A saddle that steers the horse but draws nothing. Since 1.21.5 a saddle renders as equipment, which the
     * invisibility effect does not hide, so the horse's saddle and reins would show around the model. A horse counts
     * as saddled while the item can be equipped in its saddle slot, which needs no equipment model.
     */
    private static ItemStack unseenSaddle() {
        ItemStack saddle = new ItemStack(Material.SADDLE);
        if (VersionChecker.serverVersionOlderThan(21, 5)) return saddle;
        ItemMeta meta = saddle.getItemMeta();
        EquippableComponent equippable = meta.getEquippable();
        equippable.setSlot(EquipmentSlot.SADDLE);
        equippable.setModel(null);
        meta.setEquippable(equippable);
        saddle.setItemMeta(meta);
        return saddle;
    }
}
