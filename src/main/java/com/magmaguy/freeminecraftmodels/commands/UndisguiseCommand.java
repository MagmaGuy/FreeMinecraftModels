package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

public class UndisguiseCommand extends AdvancedCommand {
    List<String> entityIDs = new ArrayList<>();

    public UndisguiseCommand() {
        super(List.of("undisguise"));
        setDescription("Undisguises a player currently disguised as a model");
        setPermission("freeminecraftmodels.*");
        setDescription("/fmm undisguise");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        for (ModeledEntity modeledEntity : ModeledEntityManager.getAllEntities()) {
            if (modeledEntity.getUnderlyingEntity() != null && modeledEntity.getUnderlyingEntity().getUniqueId().equals(commandData.getPlayerSender().getUniqueId())) {
                modeledEntity.remove();
                break;
            }
        }
        commandData.getPlayerSender().setVisibleByDefault(true);
        commandData.getPlayerSender().removePotionEffect(PotionEffectType.INVISIBILITY);
    }
}