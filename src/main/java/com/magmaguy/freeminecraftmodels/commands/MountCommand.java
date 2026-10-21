package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.MountPointManager;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.Bukkit;
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

    public MountCommand() {
        super(List.of("mount"));
        addArgument("model", new com.magmaguy.freeminecraftmodels.commands.arguments.LiveModelIDsArgument("<model>"));
        setDescription("Spawns a rideable model horse");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm mount <model>");
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

        // Spawn a horse as the rideable base entity
        Horse horse = (Horse) player.getWorld().spawnEntity(player.getLocation(), EntityType.HORSE);
        horse.setTamed(true);
        horse.setOwner(player);
        horse.setAdult();
        horse.getInventory().setSaddle(unseenSaddle());
        horse.setRemoveWhenFarAway(false);
        horse.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.3);
        horse.getAttribute(Attribute.JUMP_STRENGTH).setBaseValue(0.7);

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
