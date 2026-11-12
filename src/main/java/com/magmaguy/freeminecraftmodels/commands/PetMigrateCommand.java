package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.pets.PetManager;
import com.magmaguy.freeminecraftmodels.pets.PetSpecies;
import com.magmaguy.freeminecraftmodels.pets.interactions.PetInteractionRuntime;
import com.magmaguy.freeminecraftmodels.pets.migration.PetMigrations;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.DynamicListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /fmm petmigrate [species]} sends a night migration flock over you now, whatever the time, the sky or the
 * other flocks: for trying a species' migration look. Without a species it picks one by weight, as the night
 * schedule does.
 */
public class PetMigrateCommand extends AdvancedCommand {
    public PetMigrateCommand() {
        super(List.of("petmigrate"));
        addOptionalArgument("species", new DynamicListStringCommandArgument(() -> {
            List<String> values = new ArrayList<>();
            PetManager manager = PetManager.get();
            if (manager != null) manager.species().forEach(species -> values.add(species.id()));
            return values;
        }, "[species]"));
        setUsage("/fmm petmigrate [species]");
        setPermission("freeminecraftmodels.pets.admin");
        setSenderType(SenderType.PLAYER);
        setDescription("Sends a pet migration flock over you now.");
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        PetManager manager = PetManager.get();
        PetMigrations migrations = PetInteractionRuntime.migrations();
        if (manager == null || migrations == null) {
            Logger.sendMessage(player, "&cPets are not available on this server; see the console at startup.");
            return;
        }
        String id = commandData.getStringArgument("species");
        PetSpecies species = id == null ? null : manager.species(id);
        if (id != null && species == null) {
            Logger.sendMessage(player, "&cUnknown pet species. /fmm petadmin list shows them.");
            return;
        }
        if (migrations.start(player, species, true) == null)
            Logger.sendMessage(player, species == null
                    ? "&cNo species can migrate: none has a migration: section in its pet file, or their behaviors"
                    + " don't run migrate; see the console."
                    : "&cThe flock could not start; see the console.");
        else Logger.sendMessage(player, "&aA flock is on its way. Look up.");
    }
}
