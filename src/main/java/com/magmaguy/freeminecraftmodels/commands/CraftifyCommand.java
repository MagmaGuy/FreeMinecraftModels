package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.listeners.CraftifyListener;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class CraftifyCommand extends AdvancedCommand {

    // Inventory layout (54-slot chest = 6 rows x 9 cols):
    // Row 0: all glass border
    // Row 1: glass | slot | slot | slot | glass | glass | glass | glass | glass
    // Row 2: glass | slot | slot | slot | glass | glass | OUTPUT | glass | glass
    // Row 3: glass | slot | slot | slot | glass | glass | glass | glass | glass
    // Row 4: all glass border
    // Row 5: glass | glass | glass | glass | INSTRUCTIONS item | glass | glass | glass | glass

    public static final int[] GRID_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    public static final int OUTPUT_SLOT = 24;

    public CraftifyCommand() {
        super(List.of("craftify"));
        List<String> entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(f -> entityIDs.add(f.getID()));
        addArgument("model", new ListStringCommandArgument(entityIDs, "<model>"));
        setDescription("Opens an interactive recipe builder for a model prop");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm craftify <model>");
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

        String title = "\u00a78\u2726 Recipe Builder: \u00a76" + ItemifyCommand.formatModelName(modelID);
        Inventory inv = Bukkit.createInventory(null, 54, title);

        // Fill with glass panes
        ItemStack border = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta borderMeta = border.getItemMeta();
        if (borderMeta != null) {
            borderMeta.setDisplayName(" ");
            border.setItemMeta(borderMeta);
        }
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, border);
        }

        // Clear the 3x3 grid slots
        for (int slot : GRID_SLOTS) {
            inv.setItem(slot, null);
        }

        // Place the output item
        ItemStack output = new ItemStack(Material.PAPER);
        ItemMeta outputMeta = output.getItemMeta();
        if (outputMeta != null) {
            outputMeta.setDisplayName("\u00a7e\u2726 \u00a76" + ItemifyCommand.formatModelName(modelID) + " \u00a7e\u2726");
            outputMeta.setLore(List.of(
                    "",
                    "\u00a7a\u25b6 Click to save recipe",
                    "",
                    "\u00a77Place ingredients in the grid,",
                    "\u00a77then click here to confirm.",
                    "",
                    "\u00a78Model: " + modelID
            ));
            NamespacedKey modelKey = new NamespacedKey(MetadataHandler.PLUGIN, "model_id");
            outputMeta.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, modelID);
            NamespacedKey craftifyKey = new NamespacedKey(MetadataHandler.PLUGIN, "craftify_output");
            outputMeta.getPersistentDataContainer().set(craftifyKey, PersistentDataType.BYTE, (byte) 1);
            // Set custom display model if available (1.21.4+)
            if (!com.magmaguy.magmacore.util.VersionChecker.serverVersionOlderThan(21, 4)
                    && com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry.hasDisplayModel(modelID)) {
                outputMeta.setItemModel(NamespacedKey.fromString("freeminecraftmodels:display/" + modelID));
            }
            output.setItemMeta(outputMeta);
        }
        inv.setItem(OUTPUT_SLOT, output);

        // Place instruction item
        ItemStack instructions = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta instrMeta = instructions.getItemMeta();
        if (instrMeta != null) {
            instrMeta.setDisplayName("\u00a7e\u00a7lRecipe Builder");
            instrMeta.setLore(List.of(
                    "",
                    "\u00a7f1. \u00a77Place ingredients in the 3\u00d73 grid",
                    "\u00a7f2. \u00a77Arrange them in the pattern you want",
                    "\u00a7f3. \u00a77Click the \u00a7aoutput item \u00a77to save",
                    "",
                    "\u00a77Press \u00a7fEsc \u00a77to cancel without saving"
            ));
            instructions.setItemMeta(instrMeta);
        }
        inv.setItem(49, instructions);

        CraftifyListener.startSession(player, modelID);
        player.openInventory(inv);
    }
}
