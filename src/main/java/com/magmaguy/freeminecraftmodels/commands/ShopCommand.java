package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.config.ShopConfig;
import com.magmaguy.freeminecraftmodels.shop.ShopMenu;
import com.magmaguy.freeminecraftmodels.shop.VaultEconomyHook;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.entity.Player;

import java.util.List;

public class ShopCommand extends AdvancedCommand {

    public ShopCommand() {
        super(List.of("shop"));
        setPermission("freeminecraftmodels.shop");
        setDescription("Opens the furniture shop.");
        setUsage("/fmm shop");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        if (!ShopConfig.isEnabled() || !VaultEconomyHook.isEnabled()) {
            Logger.sendMessage(player, ShopConfig.getShopDisabledMessage());
            return;
        }
        new ShopMenu(player);
    }
}
