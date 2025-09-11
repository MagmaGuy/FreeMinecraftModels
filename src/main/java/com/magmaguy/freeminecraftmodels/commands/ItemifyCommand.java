package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

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

        ItemStack item = createModelItem(modelID, material);
        player.getInventory().addItem(item);
        Logger.sendMessage(player, "You received a model placement item for: " + modelID);
    }

    private ItemStack createModelItem(String modelID, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§6Model Placer: " + modelID);
            meta.setLore(List.of(
                    "§7Right-click to place the model",
                    "§7Model: " + modelID,
                    "§7Material: " + material.name()
            ));

            // Store the model ID in persistent data
            NamespacedKey key = new NamespacedKey(com.magmaguy.freeminecraftmodels.MetadataHandler.PLUGIN, "model_id");
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, modelID);

            item.setItemMeta(meta);
        }

        return item;
    }
}
