package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.commands.arguments.PetVariantArgument;
import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import com.magmaguy.freeminecraftmodels.pets.MarchRequests;
import com.magmaguy.freeminecraftmodels.pets.PetControl;
import com.magmaguy.freeminecraftmodels.pets.PetManager;
import com.magmaguy.freeminecraftmodels.pets.PetSpecies;
import com.magmaguy.freeminecraftmodels.pets.PetVariantMenu;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.DynamicListStringCommandArgument;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Admin and testing switches for pets: {@code mode creatures|cosmetic} picks whether species also live wild,
 * {@code spawn <species> [variant]} puts a wild one where you look, {@code cluster <species> [variant]} puts a wild
 * cluster there (see {@link PetSpecies.Cluster}), {@code give <species> [variant]} adds an owned one to your collection
 * (without a variant, a menu of the species' colours to pick from), {@code march [species]} asks the nearest wild
 * adult that marches to call a march as soon as it is free, for filming, and tells its sender what came of it,
 * {@code colony [go]} tells how the nearest wild colony's day stands, and with {@code go} sets it off at once where the
 * time of day wants it, and {@code list} shows the loaded species.
 */
public class PetAdminCommand extends AdvancedCommand {
    public PetAdminCommand() {
        super(List.of("petadmin"));
        addArgument("action", new ListStringCommandArgument(List.of("mode", "spawn", "cluster", "give", "march", "colony", "list"), "<action>"));
        addOptionalArgument("value", new DynamicListStringCommandArgument(() -> {
            List<String> values = new ArrayList<>(List.of("creatures", "cosmetic", "go"));
            PetManager manager = PetManager.get();
            if (manager != null) manager.species().forEach(species -> values.add(species.id()));
            return values;
        }, "[value]"));
        addOptionalArgument("variant", PetVariantArgument.create(PetAdminCommand::speciesTyped, "[variant]"));
        setUsage("/fmm petadmin <mode|spawn|cluster|give|march|colony|list> [value] [variant]");
        setPermission("freeminecraftmodels.pets.admin");
        setSenderType(SenderType.PLAYER);
        setDescription("Pet mode switch and testing spawns.");
    }

    /**
     * The species a variant is being typed for, from the action and value typed before it: spawn, cluster and give
     * take a species and then one of its variants. null after the other actions, which take no variant.
     */
    static String speciesTyped(List<String> earlier) {
        if (earlier.size() < 2) return null;
        return switch (earlier.get(0).toLowerCase(Locale.ROOT)) {
            case "spawn", "cluster", "give" -> earlier.get(1);
            default -> null;
        };
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        PetManager manager = PetManager.get();
        if (manager == null) {
            Logger.sendMessage(player, "&cPets are not available on this server; see the console at startup.");
            return;
        }
        String action = commandData.getStringArgument("action").toLowerCase(Locale.ROOT);
        String value = commandData.getStringArgument("value");
        switch (action) {
            case "mode" -> {
                if (value == null) {
                    Logger.sendMessage(player, "&7Pet mode is &f" + PetsConfig.mode.name().toLowerCase(Locale.ROOT));
                    return;
                }
                try {
                    PetsConfig.setModeAndSave(PetsConfig.Mode.valueOf(value.toUpperCase(Locale.ROOT)));
                    Logger.sendMessage(player, "&aPet mode is now &f" + PetsConfig.mode.name().toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    Logger.sendMessage(player, "&cUse creatures or cosmetic.");
                }
            }
            case "list" -> {
                for (PetSpecies species : manager.species())
                    Logger.sendMessage(player, "&f" + species.id() + " &7(" + species.file().getName() + ", model " + species.model()
                            + ", body " + species.body() + ", tame with " + species.tamingFood() + ", breed with " + species.breedingFood() + ")");
                if (manager.species().isEmpty()) Logger.sendMessage(player, "&7No pet species are installed.");
            }
            case "spawn", "cluster" -> {
                PetSpecies species = manager.species(value);
                if (species == null) {
                    Logger.sendMessage(player, "&cUnknown pet species. /fmm petadmin list shows them.");
                    return;
                }
                if (PetsConfig.mode != PetsConfig.Mode.CREATURES) {
                    Logger.sendMessage(player, "&cWild pets only exist in creatures mode. &7/fmm petadmin mode creatures &cfirst.");
                    return;
                }
                String asked = commandData.getStringArgument("variant");
                if (action.equals("cluster")) {
                    // Without a colour, each member picks its own, or now and then the whole cluster wears a rare one.
                    String colour = asked == null ? null : model(player, species, asked);
                    if (asked != null && colour == null) return;
                    int standing = manager.spawnCluster(species, player.getTargetBlock(null, 12).getLocation(), colour);
                    Logger.sendMessage(player, standing == 0 ? "&cNo cluster member could stand there; see the console."
                            : "&aSpawned a wild cluster of " + standing + " " + species.id() + ".");
                    return;
                }
                String model = model(player, species, asked);
                if (model == null) return;
                // A test spawn goes where you look, whatever biomes its variants live in.
                PetControl spawned = manager.spawnWild(species, player.getTargetBlock(null, 12).getLocation().add(0.5D, 1D, 0.5D), model);
                Logger.sendMessage(player, spawned == null ? "&cThe pet could not be spawned; see the console." : "&aSpawned a wild " + species.id() + " (" + model + ").");
            }
            case "give" -> {
                PetSpecies species = manager.species(value);
                if (species == null) {
                    Logger.sendMessage(player, "&cUnknown pet species. /fmm petadmin list shows them.");
                    return;
                }
                String asked = commandData.getStringArgument("variant");
                // Without a colour, a menu of the species' colours to pick from.
                if (asked == null) {
                    PetVariantMenu.open(player, species);
                    return;
                }
                String model = model(player, species, asked);
                if (model == null) return;
                manager.give(player, species, model);
                Logger.sendMessage(player, "&aAdded a " + species.id() + " (" + model + ") to your pets.");
            }
            case "march" -> march(player, manager, value);
            case "colony" -> {
                boolean go = "go".equalsIgnoreCase(value);
                if (value != null && !go) {
                    Logger.sendMessage(player, "&cUse /fmm petadmin colony, or /fmm petadmin colony go.");
                    return;
                }
                String status = manager.colony(player.getLocation(), MARCH_SEARCH, go);
                Logger.sendMessage(player, status != null ? status : "&cNo wild colony on its daily routine has a pet within "
                        + (int) MARCH_SEARCH + " blocks. A colony is 2 or more wild pets of a kind whose homes are close"
                        + " together, with the march behavior's routine on.");
            }
            default -> Logger.sendMessage(player, "&cUse mode, spawn, cluster, give, march, colony or list.");
        }
    }

    /** How far from the admin /fmm petadmin march looks for a wild pet to ask. */
    private static final double MARCH_SEARCH = 32D;

    /**
     * Asks the nearest wild adult within {@link #MARCH_SEARCH} blocks whose behaviors run the march module, of the
     * named species when one is given, to call a march as soon as it is free. The sender is told what its march
     * needs, by its own settings, and then once what came of it: the march, when it starts, or why there was none,
     * when the request runs out (see {@link MarchRequests}).
     */
    private static void march(Player player, PetManager manager, String speciesId) {
        PetSpecies only = null;
        if (speciesId != null) {
            only = manager.species(speciesId);
            if (only == null) {
                Logger.sendMessage(player, "&cUnknown pet species. /fmm petadmin list shows them.");
                return;
            }
        }
        PetControl nearest = null;
        double best = Double.MAX_VALUE;
        // The wild pets there that cannot be asked, by why, for the sender when nobody can.
        int babies = 0, migrants = 0, unmarching = 0;
        for (Entity entity : player.getNearbyEntities(MARCH_SEARCH, MARCH_SEARCH, MARCH_SEARCH)) {
            PetControl pet = manager.pet(entity);
            if (pet == null || !pet.isWild() || only != null && pet.species() != only) continue;
            // A migrant is passing through and calls no march.
            if (pet.route() != null) {
                migrants++;
                continue;
            }
            if (pet.isBaby()) {
                babies++;
                continue;
            }
            if (manager.marchSettings(pet) == null) {
                unmarching++;
                continue;
            }
            double distance = entity.getLocation().distanceSquared(player.getLocation());
            if (distance < best) {
                best = distance;
                nearest = pet;
            }
        }
        if (nearest == null || !manager.requestMarch(nearest, player)) {
            Logger.sendMessage(player, MarchRequests.noneNear(only == null ? null : only.id(), (int) MARCH_SEARCH,
                    babies, migrants, unmarching));
            return;
        }
        Logger.sendMessage(player, MarchRequests.asked(nearest.species().id(), manager.marchSettings(nearest)));
    }

    /**
     * The variant model a spawn or give asked for, as the pet file writes it; a pick by weight when none was named,
     * and null after telling the player when the species has no such variant.
     */
    private static String model(Player player, PetSpecies species, String asked) {
        if (asked == null) return species.pickVariant(ThreadLocalRandom.current());
        PetSpecies.Variant variant = species.variant(asked);
        if (variant != null) return variant.model();
        List<String> variants = species.variants().stream().map(PetSpecies.Variant::model).toList();
        Logger.sendMessage(player, "&c" + species.id() + " has no variant " + asked + ". "
                + (variants.isEmpty() ? "It has none." : "Its variants: " + String.join(", ", variants)));
        return null;
    }
}
