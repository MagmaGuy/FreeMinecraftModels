package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.freeminecraftmodels.pets.migration.PetMigrations;
import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;

import java.util.List;
import java.util.Locale;

/**
 * Server-wide pet settings ({@code pets.yml}): whether pet species also live wild in the world, how many pets a
 * player may have out, how wild spawning paces itself, and the messages players see. Each species' own values live
 * in its {@code <species>.pet.yml} beside its model.
 */
public class PetsConfig extends ConfigurationFile {
    public enum Mode {
        /** "More creatures": pet species also spawn wild and are tamed by feeding them. */
        CREATURES,
        /** "Cosmetic pets": only pets players own exist in the world. */
        COSMETIC
    }

    public static Mode mode = Mode.COSMETIC;
    public static int maxEquippedPets;
    public static boolean petsInvulnerableToPlayers;
    public static boolean petsDiePermanently;
    public static int wildSpawnIntervalTicks;
    public static double wildSpawnChunkChance;
    public static int wildPetCapPerPlayer;
    public static double wildDespawnDistance;
    public static String tamedMessage;
    public static String tameFailedMessage;
    public static String stayMessage;
    public static String followMessage;
    public static String menuTitle;
    public static String noPetsMessage;
    public static String equippedMessage;
    public static String unequippedMessage;
    public static String maxEquippedMessage;
    public static String breedingCooldownMessage;
    public static String bornMessage;
    private static PetsConfig instance;

    public PetsConfig() {
        super("pets.yml");
        instance = this;
    }

    /** Switches the mode and writes it to pets.yml, so the setup menu and the admin command survive restarts. */
    public static void setModeAndSave(Mode newMode) {
        if (instance == null || ConfigurationEngine.writeValue(newMode.name(), instance.file, instance.fileConfiguration, "mode"))
            mode = newMode;
    }

    @Override
    public void initializeValues() {
        String modeName = ConfigurationEngine.setString(
                List.of("Which kind of pet server this is. Also switchable from /fmm setup and /fmm petadmin mode.",
                        "CREATURES (more creatures): pet species also spawn wild in the world and are tamed by feeding them.",
                        "COSMETIC (cosmetic pets): only pets players own exist in the world; no wild spawns."),
                fileConfiguration, "mode", Mode.COSMETIC.name());
        try {
            mode = Mode.valueOf(modeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            mode = Mode.COSMETIC;
        }
        maxEquippedPets = ConfigurationEngine.setInt(
                List.of("How many pets a player may have out at once. 0 means no limit."),
                fileConfiguration, "maxEquippedPets", 0);
        petsInvulnerableToPlayers = ConfigurationEngine.setBoolean(
                List.of("Whether owned pets ignore damage from players. Pets are always invulnerable in COSMETIC mode."),
                fileConfiguration, "petsInvulnerableToPlayers", true);
        petsDiePermanently = ConfigurationEngine.setBoolean(
                List.of("Whether a pet that dies leaves its owner's collection. When false it goes home until re-equipped."),
                fileConfiguration, "petsDiePermanently", false);
        wildSpawnIntervalTicks = ConfigurationEngine.setInt(
                List.of("CREATURES mode: ticks between wild spawn passes. Wild pets spawn like vanilla mobs: each pass",
                        "tries the loaded chunks within 8 chunks of each player, never closer than 24 blocks to one.",
                        "400 is vanilla's pace for animals."),
                fileConfiguration, "wildSpawnIntervalTicks", 400);
        wildSpawnChunkChance = ConfigurationEngine.setDouble(
                List.of("CREATURES mode: chance that a pass tries a given chunk, 0 to 1. Vanilla tries every chunk (1);",
                        "lower values fill the area around a player more slowly."),
                fileConfiguration, "wildSpawnChunkChance", 0.02D);
        wildPetCapPerPlayer = ConfigurationEngine.setInt(
                List.of("CREATURES mode: wild pets of all species allowed in the area 8 chunks around a player, like a",
                        "vanilla mob cap. Each species' wildSpawn.cap limits that species further."),
                fileConfiguration, "wildPetCapPerPlayer", 6);
        wildDespawnDistance = ConfigurationEngine.setDouble(
                List.of("CREATURES mode: wild pets farther than this from every player despawn at once, and none spawn",
                        "farther out. Past 32 blocks they also despawn at random once no player has come near for",
                        "30 seconds, as vanilla mobs do."),
                fileConfiguration, "wildDespawnDistance", 128D);
        tamedMessage = ConfigurationEngine.setString(fileConfiguration, "tamedMessage",
                "&aYou tamed a $pet&a! It follows you now. &f/fmm pets &amanages your pets.");
        tameFailedMessage = ConfigurationEngine.setString(fileConfiguration, "tameFailedMessage",
                "&7The $pet &7eats it, but is not tamed yet.");
        stayMessage = ConfigurationEngine.setString(fileConfiguration, "stayMessage", "&7Your $pet &7stays here.");
        followMessage = ConfigurationEngine.setString(fileConfiguration, "followMessage", "&7Your $pet &7follows you again.");
        menuTitle = ConfigurationEngine.setString(fileConfiguration, "menuTitle", "&2Your pets");
        noPetsMessage = ConfigurationEngine.setString(fileConfiguration, "noPetsMessage",
                "&7You have no pets yet. Tame a wild creature by feeding it its favorite food.");
        equippedMessage = ConfigurationEngine.setString(fileConfiguration, "equippedMessage", "&a$pet &ais with you.");
        unequippedMessage = ConfigurationEngine.setString(fileConfiguration, "unequippedMessage", "&7$pet &7went home.");
        maxEquippedMessage = ConfigurationEngine.setString(fileConfiguration, "maxEquippedMessage",
                "&cYou can only have $amount pets with you at once.");
        breedingCooldownMessage = ConfigurationEngine.setString(fileConfiguration, "breedingCooldownMessage",
                "&7Your $pet &7is not ready to breed again yet.");
        bornMessage = ConfigurationEngine.setString(fileConfiguration, "bornMessage", "&aA $pet &awas born!");
        PetMigrations.loadSettings(fileConfiguration);
    }
}
