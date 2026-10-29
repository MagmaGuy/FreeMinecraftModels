package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import com.magmaguy.freeminecraftmodels.pets.PetManager;
import com.magmaguy.freeminecraftmodels.pets.PetRecord;
import com.magmaguy.freeminecraftmodels.pets.PetSpecies;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.DynamicListStringCommandArgument;
import com.magmaguy.magmacore.command.arguments.PlayerCommandArgument;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gives a player a pet: {@code /fmm givepet <player> <species> [variant]}. It works from the console, so a server's
 * own shop, crate or reward plugin can hand pets out. Without a variant the colour is picked by rarity, as a wild one's
 * would be. A player already at pets.yml's maxOwnedPets gets none, and both they and the sender are told.
 */
public class GivePetCommand extends AdvancedCommand {
    public GivePetCommand() {
        super(List.of("givepet"));
        addArgument("player", new PlayerCommandArgument());
        addArgument("species", new DynamicListStringCommandArgument(() -> {
            List<String> values = new ArrayList<>();
            PetManager manager = PetManager.get();
            if (manager != null) manager.species().forEach(species -> values.add(species.id()));
            return values;
        }, "<species>"));
        addOptionalArgument("variant", new DynamicListStringCommandArgument(() -> {
            List<String> values = new ArrayList<>();
            PetManager manager = PetManager.get();
            if (manager != null)
                for (PetSpecies species : manager.species())
                    for (PetSpecies.Variant variant : species.variants()) values.add(variant.model());
            return values;
        }, "[variant]"));
        setUsage("/fmm givepet <player> <species> [variant]");
        setPermission("freeminecraftmodels.pets.give");
        setSenderType(SenderType.ANY);
        setDescription("Gives a player a pet, for shops and rewards.");
    }

    @Override
    public void execute(CommandData commandData) {
        CommandSender sender = commandData.getCommandSender();
        PetManager manager = PetManager.get();
        if (manager == null) {
            Logger.sendMessage(sender, "&cPets are not available on this server; see the console at startup.");
            return;
        }
        Player player = Bukkit.getPlayerExact(commandData.getStringArgument("player"));
        if (player == null) {
            Logger.sendMessage(sender, "&cThat player is not online.");
            return;
        }
        PetSpecies species = manager.species(commandData.getStringArgument("species"));
        if (species == null) {
            Logger.sendMessage(sender, "&cUnknown pet species. /fmm petadmin list shows them.");
            return;
        }
        String asked = commandData.getStringArgument("variant");
        String model;
        if (asked == null) model = species.pickVariant(ThreadLocalRandom.current());
        else {
            PetSpecies.Variant variant = species.variant(asked);
            if (variant == null) {
                List<String> variants = species.variants().stream().map(PetSpecies.Variant::model).toList();
                Logger.sendMessage(sender, "&c" + species.id() + " has no variant " + asked + ". "
                        + (variants.isEmpty() ? "It has none." : "Its variants: " + String.join(", ", variants)));
                return;
            }
            model = variant.model();
        }
        if (manager.atOwnedCap(player)) {
            String full = PetsConfig.maxOwnedMessage.replace("$amount", String.valueOf(PetsConfig.maxOwnedPets));
            player.sendMessage(ChatColorConverter.convert(full));
            Logger.sendMessage(sender, "&c" + player.getName() + " already has as many pets as they may keep; none given.");
            return;
        }
        PetRecord record = manager.give(player, species, model);
        player.sendMessage(ChatColorConverter.convert(PetsConfig.givenMessage.replace("$pet", manager.displayName(record))));
        Logger.sendMessage(sender, "&aGave " + player.getName() + " a " + species.id() + " (" + model + ").");
    }
}
