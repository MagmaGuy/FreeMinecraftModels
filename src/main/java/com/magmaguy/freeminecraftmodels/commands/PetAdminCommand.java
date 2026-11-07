package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.config.PetsConfig;
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
 * adult that marches to call a march as soon as it is free, for filming, and {@code list} shows the loaded species.
 */
public class PetAdminCommand extends AdvancedCommand {
    public PetAdminCommand() {
        super(List.of("petadmin"));
        addArgument("action", new ListStringCommandArgument(List.of("mode", "spawn", "cluster", "give", "march", "list"), "<action>"));
        addOptionalArgument("value", new DynamicListStringCommandArgument(() -> {
            List<String> values = new ArrayList<>(List.of("creatures", "cosmetic"));
            PetManager manager = PetManager.get();
            if (manager != null) manager.species().forEach(species -> values.add(species.id()));
            return values;
        }, "[value]"));
        addOptionalArgument("variant", new DynamicListStringCommandArgument(() -> {
            List<String> values = new ArrayList<>();
            PetManager manager = PetManager.get();
            if (manager != null)
                for (PetSpecies species : manager.species())
                    for (PetSpecies.Variant variant : species.variants()) values.add(variant.model());
            return values;
        }, "[variant]"));
        setUsage("/fmm petadmin <mode|spawn|cluster|give|march|list> [value] [variant]");
        setPermission("freeminecraftmodels.pets.admin");
        setSenderType(SenderType.PLAYER);
        setDescription("Pet mode switch and testing spawns.");
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
            default -> Logger.sendMessage(player, "&cUse mode, spawn, cluster, give, march or list.");
        }
    }

    /** How far from the admin /fmm petadmin march looks for a wild pet to ask. */
    private static final double MARCH_SEARCH = 32D;

    /**
     * Asks the nearest wild adult within {@link #MARCH_SEARCH} blocks whose behaviors list the march module, of the
     * named species when one is given, to call a march as soon as it is free.
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
        for (Entity entity : player.getNearbyEntities(MARCH_SEARCH, MARCH_SEARCH, MARCH_SEARCH)) {
            PetControl pet = manager.pet(entity);
            if (pet == null || !pet.isWild() || pet.isBaby() || only != null && pet.species() != only) continue;
            if (pet.species().behaviors(pet.model()).stream().noneMatch(entry -> entry.module().equals("march")))
                continue;
            double distance = entity.getLocation().distanceSquared(player.getLocation());
            if (distance < best) {
                best = distance;
                nearest = pet;
            }
        }
        if (nearest == null || !manager.requestMarch(nearest)) {
            Logger.sendMessage(player, "&cNo wild adult pet with the march behavior is within "
                    + (int) MARCH_SEARCH + " blocks.");
            return;
        }
        Logger.sendMessage(player, "&aAsked a wild " + nearest.species().id() + " to call a march. It goes once it is"
                + " free, if at least one more of its kind is near it and it has room to set off.");
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
