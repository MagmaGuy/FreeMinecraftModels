package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.MountPointManager;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.listeners.ArmorStandListener;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class MountCommand extends AdvancedCommand {

    public MountCommand() {
        super(List.of("mount"));
        List<String> entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(f -> entityIDs.add(f.getID()));
        addArgument("model", new ListStringCommandArgument(entityIDs, "<model>"));
        setDescription("Spawns a rideable model horse");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm mount <model>");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        String modelID = commandData.getStringArgument("model");

        if (!FileModelConverter.getConvertedFileModels().containsKey(modelID)) {
            Logger.sendMessage(player, "\u00a7cModel '" + modelID + "' not found!");
            return;
        }

        // Spawn a horse as the rideable base entity
        Horse horse = (Horse) player.getWorld().spawnEntity(player.getLocation(), EntityType.HORSE);
        horse.setTamed(true);
        horse.setOwner(player);
        horse.setAdult();
        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
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

        // Mount the player using entity stacking: horse → armor stand → player
        // The armor stand acts as a height adapter between the horse's default
        // seat position and the model's mount-point bone position.
        Bukkit.getScheduler().scheduleSyncDelayedTask(MetadataHandler.PLUGIN, () -> {
            if (!horse.isValid() || !player.isOnline()) return;

            MountPointManager mountManager = dynamicEntity.getMountPointManager();
            if (mountManager != null && mountManager.hasMountPoints()) {
                Bone mountBone = mountManager.getSeats().get(0).getBone();
                Vector3f modelCenter = mountBone.getBoneBlueprint().getModelCenter();

                // The horse's default passenger seat is at roughly Y+1.1 from
                // the horse position. The mount point is at modelCenter.y.
                // The armor stand offset adjusts the difference.
                // A small armor stand adds ~0.7 blocks of height, a normal one ~1.4.
                // We want the player's feet at: horse.y + modelCenter.y
                // Horse seat is at: horse.y + ~1.1
                // So we need the armor stand to offset by: modelCenter.y - 1.1
                // But the armor stand itself adds height as a passenger.
                // Using a marker armor stand (0 height) as intermediary:
                ArmorStandListener.bypass = true;
                ArmorStand seat = (ArmorStand) horse.getWorld().spawnEntity(
                        horse.getLocation(), EntityType.ARMOR_STAND);
                seat.setVisible(false);
                seat.setGravity(false);
                seat.setInvulnerable(true);
                seat.setPersistent(false);
                seat.setMarker(true);
                seat.setSmall(true);
                ArmorStandListener.bypass = false;

                // Stack: horse carries armor stand, armor stand carries player
                horse.addPassenger(seat);
                seat.addPassenger(player);
            } else {
                // No mount points — ride the horse directly
                horse.addPassenger(player);
            }
        }, 1);
    }
}
