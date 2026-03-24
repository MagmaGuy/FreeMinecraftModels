package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.utils.ModelItemFactory;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class ItemifyCommand extends AdvancedCommand {

    private final List<String> entityIDs;

    public ItemifyCommand() {
        super(List.of("itemify"));
        entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(fileModelConverter -> entityIDs.add(fileModelConverter.getID()));
        addArgument("model", new ListStringCommandArgument(entityIDs, "<model>"));
        addArgument("material", new ListStringCommandArgument(
                List.of(Material.values()).stream().map(Material::name).toList(),
                "<material>"));
        setDescription("Creates an item that can be used to place models");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm itemify <model> <material>");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        String modelID = commandData.getStringArgument("model");
        String materialName = commandData.getStringArgument("material");

        if (!entityIDs.contains(modelID)) {
            Logger.sendMessage(commandData.getCommandSender(), "Invalid entity ID!");
            return;
        }

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            Logger.sendMessage(commandData.getCommandSender(), "Invalid material: " + materialName);
            return;
        }

        ItemStack item = ModelItemFactory.createModelItem(modelID, material);
        player.getInventory().addItem(item);
        Logger.sendMessage(player, "You received a model placement item for: " + modelID);
    }

    /**
     * Formats a model ID into a human-readable name.
     * Delegates to {@link ModelItemFactory#formatModelName(String)}.
     */
    public static String formatModelName(String modelID) {
        return ModelItemFactory.formatModelName(modelID);
    }
}
