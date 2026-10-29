package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.ModelsFolder;
import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.pets.behavior.PetBehaviorCatalog;
import com.magmaguy.freeminecraftmodels.pets.behavior.PetBehaviorDefaults;
import com.magmaguy.freeminecraftmodels.pets.behavior.PetBehaviorEntry;
import com.magmaguy.freeminecraftmodels.pets.behavior.PetFailureLog;
import com.magmaguy.freeminecraftmodels.pets.behavior.PetPrograms;
import com.magmaguy.freeminecraftmodels.pets.interactions.PetInteractionRuntime;
import com.magmaguy.freeminecraftmodels.pets.interactions.PetInteractions;
import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindBodyProfile;
import com.magmaguy.magmacore.ai.MindHandle;
import com.magmaguy.magmacore.ai.MindHost;
import com.magmaguy.magmacore.ai.MobBody;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Owns every pet: the species read from the {@code *.pet.yml} files in the models folder, the bodies alive in the
 * world, and the collections of online players. Bodies are vanilla mobs (an allay by default) carrying a
 * FreeMinecraftModels model and driven by a MagmaCore Mind program, assembled from the pet behavior modules in
 * plugins/FreeMinecraftModels/pet_behaviors/ that the pet file lists (see {@link PetPrograms}); they are never saved
 * with their chunk, so an owned pet is re-created next to its owner, and a wild one waits in the {@link WildPetStore}
 * until its chunk loads again. Everything runs on the server thread.
 */
public final class PetManager {
    private static final double BREED_DISTANCE = 8D;
    private static final int INTERACTION_DEBOUNCE_TICKS = 5;
    // Spawn refusals said in one load, past which more stay quiet.
    private static final int MAX_REFUSALS_SAID = 64;

    // The Mind host belongs to this plugin enable and survives content reloads; NMSManager shuts it down on disable.
    private static MindHost host;
    private static BukkitTask clock;
    // The watchdog's period: owned pets brought back, babies grown, wild pets despawned.
    private static final int WATCH_TICKS = 20;
    private static long ticks;
    private static NamespacedKey bodyKey;
    private static NamespacedKey wildKey;
    private static PetManager instance;
    private static final PetFailureLog FAILURES = new PetFailureLog(Logger::warn);
    // Whether a Lua player table was built since these classes loaded; see warmEntityTables.
    private static boolean entityTablesWarm;

    private final Map<String, PetSpecies> species = new LinkedHashMap<>();
    // This load's behavior modules, the programs built from them and the warnings their c.pet tables share; the
    // modules close once this load's pets are put away.
    private PetBehaviorCatalog behaviors;
    private PetPrograms programs;
    private PetLuaTable.Warnings warnings;
    // Each species and cause a spawn was refused for this load, so the retries stay quiet.
    private final Set<String> refusals = new HashSet<>();
    // The species whose migrants all run migrate, so their night flocks fly.
    private final Set<String> migrating = new HashSet<>();
    private final Map<UUID, PetControl> byBody = new HashMap<>();
    private final Map<UUID, PetCollection> collections = new HashMap<>();
    private final Map<UUID, Map<String, PetControl>> ownedBodies = new HashMap<>();
    /** Players whose attack on an owner or one of their pets went through, by owner, until the tick they are let go. */
    private final Map<UUID, Map<UUID, Long>> aggressors = new HashMap<>();
    /** Whether each owner with a pet out is on the move, which sets how far their pets range. */
    private final Map<UUID, OwnerPace> paces = new HashMap<>();
    /** How long an owner's pets keep defending them against an attacker after its latest hit: 15 seconds. */
    static final long AGGRESSION_TICKS = 20L * 15L;
    /**
     * The wild pets waiting to come back. Each chunk's are also in that chunk's persistent data (wildKey), written
     * whenever they change while it is loaded, as it unloads, and at shutdown, so Minecraft saves them with the chunk.
     */
    private final WildPetStore wildStore = new WildPetStore();
    /** Where wild pets waited before they moved into chunk data; read once, deleted once they are all moved. */
    private final File legacyWildFile = new File(MetadataHandler.PLUGIN.getDataFolder(), "data/wild_pets.yml");
    // Newly generated chunks, stocked with wild pets the tick after they load.
    private final ArrayDeque<PendingChunk> toStock = new ArrayDeque<>();
    private long nextWildSpawnTick;

    private record PendingChunk(UUID world, int cx, int cz, long due) {
    }

    private PetManager() {
    }

    /** The running manager, or null when pets are unavailable on this server or no species are installed. */
    public static PetManager get() {
        return instance;
    }

    /** The pet clock's tick count, the time base for love and interaction debouncing. */
    public static long ticks() {
        return ticks;
    }

    /** Whether the entity is a pet body, live or left over from before a restart. */
    public static boolean isPetBody(Entity entity) {
        return bodyKey != null && entity.getPersistentDataContainer().has(bodyKey, PersistentDataType.BYTE);
    }

    /** Creates the Mind host and the pet clock, then loads the species. Once per plugin enable, after the NMS adapter. */
    public static void start(Plugin plugin) {
        bodyKey = new NamespacedKey(plugin, "pet_body");
        wildKey = new NamespacedKey(plugin, "wild_pets");
        NMSAdapter adapter = NMSManager.getAdapter();
        if (adapter == null) {
            Logger.info("Pets are off: FreeMinecraftModels has no NMS adapter for this server version.");
            return;
        }
        try {
            host = adapter.createMindHost(new NamespacedKey(plugin, "pet_mind"), FAILURES::report);
        } catch (UnsupportedOperationException | IllegalStateException unavailable) {
            Logger.info("Pets are off: they need Minecraft 1.21.4 or newer (" + unavailable.getMessage() + ").");
            host = null;
            return;
        }
        ticks = 0;
        clock = Bukkit.getScheduler().runTaskTimer(plugin, PetManager::tickAll, 1L, 1L);
        initialize();
        PetInteractionRuntime.start(plugin);
    }

    /** Despawns every pet and lets go of the host; NMSManager's shutdown closes the host itself. */
    public static void stop() {
        PetInteractionRuntime.stop();
        shutdown();
        if (clock != null) clock.cancel();
        clock = null;
        host = null;
    }

    /**
     * (Re)loads the species and the behavior modules, builds every species' programs, and brings back the equipped
     * pets of everyone online. The previous load's pets are put away first, so every pet comes back running the
     * modules as they are now.
     */
    public static void initialize() {
        shutdown();
        if (host == null) return;
        FAILURES.clear();
        PetManager manager = new PetManager();
        manager.loadSpecies();
        manager.loadBehaviors();
        manager.loadWild();
        instance = manager;
        for (Player player : Bukkit.getOnlinePlayers()) manager.playerJoined(player);
    }

    /**
     * Despawns every pet body; collections are already saved on the players, and wild pets go to the store to come
     * back after the restart or reload. Then the behavior modules close, since no pet runs them any more.
     */
    public static void shutdown() {
        PetManager manager = instance;
        instance = null;
        if (manager == null) return;
        for (PetControl control : new ArrayList<>(manager.byBody.values())) {
            if (control.isWild() && control.route() == null) manager.stash(control);
            else manager.despawn(control);
        }
        manager.flushWild();
        manager.collections.clear();
        manager.ownedBodies.clear();
        manager.aggressors.clear();
        manager.paces.clear();
        if (manager.behaviors != null) manager.behaviors.close();
    }

    private static void tickAll() {
        ticks++;
        if (host != null) host.tick();
        PetManager manager = instance;
        if (manager == null) return;
        if (ticks % WATCH_TICKS == 0) manager.watch();
        if (ticks % OwnerPace.SAMPLE_TICKS == 0) manager.samplePaces();
        manager.workChunks();
        if (ticks >= manager.nextWildSpawnTick) {
            manager.nextWildSpawnTick = ticks + Math.max(20, PetsConfig.wildSpawnIntervalTicks);
            WildSpawner.pass(manager, manager.species.values());
        }
    }

    private void loadSpecies() {
        species.clear();
        Path root = ModelsFolder.resolveModelsFolder().toPath();
        if (!Files.isDirectory(root)) return;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(PetSpecies.FILE_SUFFIX))
                    .sorted().toList();
        } catch (IOException failure) {
            Logger.warn("Could not look for pet files in " + root + ": " + failure.getMessage());
            return;
        }
        for (Path path : files) {
            PetSpecies pet = PetSpecies.load(path.toFile());
            if (pet == null) continue;
            if (FileModelConverter.getModel(pet.model()) == null)
                Logger.warn("Pet " + pet.id() + " (" + path.getFileName() + ") names model " + pet.model() + ", which is not loaded.");
            PetSpecies previous = species.putIfAbsent(pet.id(), pet);
            if (previous != null)
                Logger.warn("Pet species " + pet.id() + " is defined by both " + previous.file().getName() + " and "
                        + path.getFileName() + "; keeping the first.");
        }
        if (!species.isEmpty()) Logger.info("Loaded " + species.size() + " pet species; pet mode is " + PetsConfig.mode + ".");
    }

    /**
     * Loads the behavior modules from plugins/FreeMinecraftModels/pet_behaviors/, writing each missing shipped default
     * there first, then builds the program of every species for its own model, each variant and its migration look.
     * Building them all now puts every warning about a pet file's list in the load's log, and keeps the building out
     * of Lua callbacks, as PetPrograms requires.
     */
    private void loadBehaviors() {
        Path folder = new File(MetadataHandler.PLUGIN.getDataFolder(), "pet_behaviors").toPath();
        PetBehaviorDefaults.removeLeftovers(folder, System.currentTimeMillis());
        behaviors = PetBehaviorCatalog.load(folder, PetBehaviorCatalog.DEFAULTS,
                name -> PetBehaviorDefaults.write(folder, name, MetadataHandler.PLUGIN::getResource), Logger::warn);
        programs = new PetPrograms(behaviors, Logger::warn);
        warnings = new PetLuaTable.Warnings();
        for (PetSpecies pet : species.values()) {
            program(pet, pet.model());
            for (PetSpecies.Variant variant : pet.variants()) program(pet, variant.model());
            checkMigrants(pet);
            PetSpecies.babyModelProblems(pet.file().getName(), pet.variants(), pet.babyModel(), PetManager::loaded)
                    .forEach(Logger::warn);
        }
        if (!species.isEmpty()) Logger.info("Loaded " + behaviors.names().size() + " pet behaviors.");
    }

    /**
     * Builds the program of every model a migrant of the species may wear, and notes the species as one whose flocks
     * fly when it flies and each of those programs runs migrate. Any other flock would hover, or fall, where it
     * spawned, so it is never picked; a species whose migration section has a weight is warned about.
     */
    private void checkMigrants(PetSpecies pet) {
        PetInteractions.Migration look = pet.interactions().migration();
        Set<String> models = migrantModels(pet.model(), pet.variants(), look == null ? null : look.model(),
                PetManager::loaded);
        List<String> grounded = new ArrayList<>();
        for (String model : models) if (!program(pet, model).runs("migrate")) grounded.add(model);
        String problem = migrationProblem(pet.file().getName(), pet.locomotion(), pet.variants(), models, grounded);
        if (problem == null) migrating.add(pet.id());
        else if (look != null && look.weight() > 0D) Logger.warn(problem);
    }

    /**
     * The models a migrant of the species may wear, as spawning settles them: the migration look's own model, or else
     * each variant with a share of the picks, or the species' model when none has one. A model {@code loaded} says is
     * not loaded settles to the species' model.
     */
    static Set<String> migrantModels(String speciesModel, List<PetSpecies.Variant> variants, String lookModel,
                                     Predicate<String> loaded) {
        Set<String> models = new LinkedHashSet<>();
        if (lookModel != null) models.add(wornVariant(speciesModel, lookModel, loaded));
        else for (PetSpecies.Variant variant : variants)
            if (variant.weight() > 0D) models.add(wornVariant(speciesModel, variant.model(), loaded));
        if (models.isEmpty()) models.add(speciesModel);
        return models;
    }

    /** The variant a pet asked to wear this model runs as: the model when it is loaded, else the species' model. */
    static String wornVariant(String speciesModel, String model, Predicate<String> loaded) {
        return model == null || !loaded.test(model) ? speciesModel : model;
    }

    private static boolean loaded(String model) {
        return FileModelConverter.getModel(model) != null;
    }

    /**
     * Why a night flock of the species would not fly its route, worded as the warning about its pet file, or null
     * when it flies. Only a flying species can, and only when the program of every model its migrants may wear runs
     * migrate; {@code grounded} holds those whose program does not. A model that is one of the variants is named as
     * one; the species' model a missing variant settled to is named as a model.
     */
    static String migrationProblem(String fileName, MindBodyLocomotion locomotion, List<PetSpecies.Variant> variants,
                                   Set<String> models, List<String> grounded) {
        String start = fileName + ": has a migration section, but ";
        String end = ", so its night flocks are not launched; ";
        if (locomotion != MindBodyLocomotion.FLYING)
            return start + "a " + locomotion.name() + " species cannot fly a migration route" + end
                    + "remove the migration section.";
        if (grounded.isEmpty()) return null;
        String whose;
        if (grounded.size() == models.size()) whose = "its behaviors don't";
        else {
            List<String> named = new ArrayList<>();
            for (String model : grounded) {
                boolean variant = variants.stream().anyMatch(listed -> listed.model().equals(model));
                named.add((variant ? "variant " : "model ") + model);
            }
            whose = "the behaviors of " + String.join(", ", named) + " don't";
        }
        return start + whose + " run migrate" + end + "list migrate under behaviors: to launch them.";
    }

    /**
     * Whether a night flock of the species flies its route: the species flies, and every model its migrants may wear
     * runs the migrate module. Known for this load's species only.
     */
    public boolean canMigrate(PetSpecies pet) {
        return migrating.contains(pet.id());
    }

    /** The program a pet of the species wearing this variant runs; the first call for a variant builds it. */
    private PetPrograms.Program program(PetSpecies pet, String variant) {
        List<PetBehaviorEntry> entries = pet.behaviors(variant);
        return programs.program(pet.id(), variant, entries, where(pet.file().getName(), pet.listsBehaviors(),
                pet.locomotion(), variant, !entries.equals(pet.behaviors(null))));
    }

    /**
     * How warnings about a species' program name it: its pet file, and when that file lists no behaviors, that the
     * list is its locomotion's defaults, for example {@code jellyfish.pet.yml (default behaviors for FLYING)}, with
     * the variant's own list when {@code variantAdds}: {@code jellyfish.pet.yml (default behaviors for FLYING, plus
     * variant em_pet_jellyfish_gold's list)}.
     */
    static String where(String fileName, boolean listsBehaviors, MindBodyLocomotion locomotion, String variant,
                        boolean variantAdds) {
        if (listsBehaviors) return fileName;
        return fileName + " (default behaviors for " + locomotion.name()
                + (variantAdds ? ", plus variant " + variant + "'s list" : "") + ")";
    }

    public Collection<PetSpecies> species() {
        return Collections.unmodifiableCollection(species.values());
    }

    public PetSpecies species(String id) {
        return id == null ? null : species.get(id.toLowerCase(Locale.ROOT));
    }

    /** The pet behind a body, or null when the entity is not a live pet. */
    public PetControl pet(Entity entity) {
        return entity == null ? null : byBody.get(entity.getUniqueId());
    }

    public Collection<PetControl> pets() {
        return Collections.unmodifiableCollection(byBody.values());
    }

    public PetCollection collection(Player player) {
        return collections.computeIfAbsent(player.getUniqueId(), ignored -> PetCollection.load(player));
    }

    // ---- spawning --------------------------------------------------------------------------------------------

    /** A wild one of a variant that lives in the location's biome. */
    public PetControl spawnWild(PetSpecies pet, Location location) {
        String model = pet.pickWildVariant(ThreadLocalRandom.current(), location);
        if (model == null) return refuse(pet, "none of its variants lives in the biome it was spawned in");
        return spawnWild(pet, location, model);
    }

    /** A wild one wearing this variant, wherever it is. */
    public PetControl spawnWild(PetSpecies pet, Location location, String model) {
        Location at = PetMotion.hoverPoint(new PetControl(pet, null, null, null, false, location), location, 0D);
        return spawn(pet, null, null, model, false, at, location);
    }

    /**
     * Puts an equipped pet from the collection into the world: beside its owner, or where it was told to stay, as a
     * sitting wolf keeps its spot. A staying pet whose spot is not loaded waits for it (null); one whose world is gone
     * follows its owner again.
     */
    public PetControl spawnOwned(Player owner, PetRecord record) {
        PetSpecies pet = species(record.species);
        if (pet == null) return null;
        unspawnOwned(owner.getUniqueId(), record);
        if (record.stay != null) {
            Location spot = stayLocation(record.stay);
            if (spot != null) {
                if (!PetMotion.loaded(spot)) return null;
                PetControl staying = spawn(pet, owner.getUniqueId(), record, record.model, record.baby, spot, null);
                if (staying != null) staying.setStay(true, spot);
                return staying;
            }
            record.stay = null;
            collection(owner).save();
        }
        Location at = PetMotion.hoverPoint(new PetControl(pet, owner.getUniqueId(), record, record.model, record.baby, null),
                owner.getLocation(), 2D);
        return spawn(pet, owner.getUniqueId(), record, record.model, record.baby, at, null);
    }

    /**
     * Puts a wild pet on a migration route: an ordinary wild pet of the species, wearing its full model, flying the
     * route at the top of its Mind program. It is sizeMultiplier times the species' size, seen from viewDistance blocks
     * and lit fully bright. A player who reaches it can tame it like any wild pet.
     */
    public PetControl spawnMigrant(PetSpecies pet, Location at, String model, double sizeMultiplier, int viewDistance,
                                   MigrationRoute route) {
        PetControl control = spawn(pet, null, null, model != null ? model : pet.pickVariant(ThreadLocalRandom.current()), false,
                at, at, sizeMultiplier, null);
        if (control == null) return null;
        control.setRoute(route);
        control.modeled().setViewDistanceOverride(viewDistance);
        control.modeled().setBrightnessOverride(15, 15);
        return control;
    }

    private PetControl spawn(PetSpecies pet, UUID owner, PetRecord record, String model, boolean baby, Location at, Location home) {
        return spawn(pet, owner, record, model, baby, at, home, 1D, null);
    }

    /**
     * Puts a pet's body and model in the world. An owned pet's stats are on its record (rolled now for a record from
     * before stats); a wild one brings wildStats, or rolls a wild pet's when null. Its stats set its max health and,
     * with sizeMultiplier, its size.
     */
    private PetControl spawn(PetSpecies pet, UUID owner, PetRecord record, String model, boolean baby, Location at, Location home,
                             double sizeMultiplier, PetStats wildStats) {
        if (host == null || at.getWorld() == null) return null;
        String variant = wornVariant(pet.model(), model, PetManager::loaded);
        // A baby's look comes from the variant it was born as, even when that adult model is missing here.
        String babyLook = baby ? PetSpecies.babyLook(model, pet.variants(), pet.babyModel(), PetManager::loaded) : null;
        String modelId = babyLook != null ? babyLook : variant;
        if (FileModelConverter.getModel(modelId) == null) return refuse(pet, "model " + modelId + " is not loaded");
        PetControl control = new PetControl(pet, owner, record, variant, baby, home);
        PetStats stats;
        if (record != null) {
            if (record.stats == null) record.stats = PetStats.wild(ThreadLocalRandom.current());
            stats = record.stats;
        } else {
            stats = wildStats != null ? wildStats : PetStats.wild(ThreadLocalRandom.current());
            control.setWildStats(stats);
        }
        MindHandle mind;
        try {
            PetPrograms.Program program = program(pet, variant);
            // One context per body: its c.pet table holds this pet's glow and sting cooldowns.
            mind = host.open(UUID.randomUUID(),
                    program.definition().instantiate(new PetLuaContext(control, program, warnings)));
        } catch (RuntimeException failure) {
            return refuse(pet, "its behaviors could not start (" + reason(failure) + ")");
        }
        double scale = pet.scale() * (baby ? pet.babyScale() : 1D) * sizeMultiplier * PetStats.size(stats, pet.statCap());
        double maxHealth = Math.max(1D, pet.health() * stats.health * control.babyShare());
        // Collidable, as vanilla mobs are: bodies push each other apart instead of stacking, and players brush them
        // aside. The model sizes its body to its hitbox bone when it attaches.
        MindBodyProfile profile = new MindBodyProfile(pet.locomotion(), scale, true, pet.body().getKey().toString());
        MobBody body;
        try {
            body = host.spawnBody(at, profile, prepared -> {
                mind.attachBody(prepared);
                prepare(prepared.entity(), maxHealth);
            });
        } catch (RuntimeException failure) {
            mind.close();
            return refuse(pet, "its body could not spawn (" + reason(failure) + ")");
        }
        LivingEntity entity = body.entity();
        if (record != null) restoreHealth(entity, record.health);
        DynamicEntity modeled = DynamicEntity.create(modelId, entity);
        if (modeled == null) {
            mind.close();
            entity.remove();
            return refuse(pet, "model " + modelId + " could not be put on its body");
        }
        control.attach(entity, modeled, mind);
        // Clicks land on the model players see, not on the small body hidden inside it.
        modeled.setClickSurface(true);
        modeled.setTravelOrientation(pet.orientation());
        modeled.setCenteredOnBody(pet.centerOnBody());
        modeled.setDisplayName(nameplate(control));
        byBody.put(entity.getUniqueId(), control);
        if (owner != null && record != null) {
            Map<String, PetControl> owned = ownedBodies.computeIfAbsent(owner, ignored -> new HashMap<>());
            List<Integer> taken = new ArrayList<>();
            for (Map.Entry<String, PetControl> other : owned.entrySet()) if (!other.getKey().equals(record.id)) taken.add(other.getValue().slot());
            control.setSlot(freeSlot(taken));
            owned.put(record.id, control);
        }
        return control;
    }

    /**
     * Says why a pet of the species was not spawned, once per species and cause for this load: the watchdog tries
     * again every second and chunk stocking a few times over, so each retry would say it again. Returns null, as
     * spawning does then.
     */
    private PetControl refuse(PetSpecies pet, String cause) {
        if (refusals.size() < MAX_REFUSALS_SAID && refusals.add(pet.id() + "\n" + cause))
            Logger.warn("Could not spawn pet " + pet.id() + ": " + cause + ". This is logged once per species and cause"
                    + " until the next reload.");
        return null;
    }

    /** A failure's message, or its type when it has none. */
    private static String reason(RuntimeException failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }

    private static void prepare(LivingEntity entity, double health) {
        entity.getPersistentDataContainer().set(bodyKey, PersistentDataType.BYTE, (byte) 1);
        // Never saved with its chunk: the manager re-creates owned pets beside their owner, and wild ones are transient.
        entity.setPersistent(false);
        entity.setSilent(true);
        entity.setCanPickupItems(false);
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(health);
            entity.setHealth(health);
        }
    }

    /** Gives a body the health it had when last seen, up to its maximum; 0 or less means full. */
    private static void restoreHealth(LivingEntity body, double health) {
        if (health <= 0D) return;
        AttributeInstance max = body.getAttribute(Attribute.MAX_HEALTH);
        body.setHealth(Math.min(health, max == null ? body.getHealth() : max.getValue()));
    }

    /** Keeps an owned pet's current health on its record; whether that changed it. */
    private static boolean rememberHealth(PetControl control) {
        PetRecord record = control.record();
        LivingEntity body = control.body();
        if (record == null || body == null || body.isDead() || body.getHealth() <= 0D || body.getHealth() == record.health)
            return false;
        record.health = body.getHealth();
        return true;
    }

    /** Removes a pet body from the world and from every map; an owned pet's health stays on its record. */
    public void despawn(PetControl control) {
        if (rememberHealth(control)) {
            PetCollection collection = collections.get(control.ownerId());
            if (collection != null) collection.save();
        }
        forget(control);
        MindHandle mind = control.mind();
        if (mind != null) {
            try {
                mind.close();
            } catch (RuntimeException ignored) {
                // The body goes either way.
            }
        }
        DynamicEntity modeled = control.modeled();
        if (modeled != null && !modeled.isRemoved()) modeled.remove();
        LivingEntity body = control.body();
        // A body whose chunk is on its way out may no longer be valid but still be there; it goes too.
        if (body != null && !body.isDead()) body.remove();
    }

    private void forget(PetControl control) {
        LivingEntity body = control.body();
        if (body != null) byBody.remove(body.getUniqueId(), control);
        if (control.ownerId() != null && control.record() != null) {
            Map<String, PetControl> owned = ownedBodies.get(control.ownerId());
            if (owned != null) owned.remove(control.record().id, control);
        }
    }

    /** Tells an owned pet to stay where it is, or to follow again. The order is kept on its record, so it lasts. */
    private void setStay(Player owner, PetControl control, boolean stay, Location at) {
        control.setStay(stay, at);
        PetRecord record = control.record();
        if (record == null) return;
        record.stay = stay && at != null && at.getWorld() != null
                ? new PetRecord.StaySpot(at.getWorld().getUID().toString(), at.getX(), at.getY(), at.getZ(), at.getYaw()) : null;
        collection(owner).save();
    }

    /** Where a stay spot is, or null when its world is not loaded. */
    private static Location stayLocation(PetRecord.StaySpot spot) {
        World world = null;
        try {
            if (spot.world != null) world = Bukkit.getWorld(UUID.fromString(spot.world));
        } catch (IllegalArgumentException malformed) {
            // Not a world id: the spot is gone.
        }
        return world == null ? null : new Location(world, spot.x, spot.y, spot.z, spot.yaw, 0F);
    }

    private void unspawnOwned(UUID owner, PetRecord record) {
        Map<String, PetControl> owned = ownedBodies.get(owner);
        PetControl live = owned == null ? null : owned.get(record.id);
        if (live != null) despawn(live);
    }

    public PetControl liveBody(UUID owner, PetRecord record) {
        Map<String, PetControl> owned = ownedBodies.get(owner);
        return owned == null ? null : owned.get(record.id);
    }

    // ---- players ---------------------------------------------------------------------------------------------

    public void playerJoined(Player player) {
        warmEntityTables(player);
        PetCollection collection = PetCollection.load(player);
        collections.put(player.getUniqueId(), collection);
        boolean rolled = false;
        for (PetRecord record : collection.pets())
            if (record.stats == null) {
                record.stats = PetStats.wild(ThreadLocalRandom.current());
                rolled = true;
            }
        if (rolled) collection.save();
        for (PetRecord record : collection.equipped()) spawnOwned(player, record);
    }

    /**
     * Builds the Lua table c.perception:nearest_player gives a module for this player, and drops it, once per class
     * load (a reload keeps the classes linked; a new jar starts cold). The first build links MagmaCore's entity table
     * classes, which on a cold server can take longer than the 20 ms runaway limit of the callback that first finds a
     * player, idle_look's can_start, and fail it. Here it runs on the server thread outside any Lua limit, when a
     * player first joins or is online at load, before any pet can look for them.
     */
    private static void warmEntityTables(Player player) {
        if (entityTablesWarm) return;
        entityTablesWarm = true;
        try {
            LuaLivingEntityTable.build(player);
        } catch (RuntimeException | LinkageError failure) {
            Logger.warn("Could not prepare the Lua player table pet behaviors use: " + failure);
        }
    }

    public void playerLeft(Player player) {
        Map<String, PetControl> owned = ownedBodies.remove(player.getUniqueId());
        if (owned != null) for (PetControl control : new ArrayList<>(owned.values())) despawn(control);
        collections.remove(player.getUniqueId());
        aggressors.remove(player.getUniqueId());
        paces.remove(player.getUniqueId());
    }

    /** Where each owner with a pet out is now; owners whose pets are all away are forgotten. */
    private void samplePaces() {
        paces.keySet().retainAll(ownedBodies.keySet());
        for (UUID owner : ownedBodies.keySet()) {
            Player player = Bukkit.getPlayer(owner);
            if (player == null) continue;
            Location at = player.getLocation();
            paces.computeIfAbsent(owner, ignored -> new OwnerPace())
                    .sample(ticks, at.getWorld() == null ? null : at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
        }
    }

    /** Whether the owner went {@link OwnerPace#MOVING_BLOCKS} or more within the last three seconds. */
    public boolean ownerMoving(UUID owner) {
        OwnerPace pace = owner == null ? null : paces.get(owner);
        return pace != null && pace.moving();
    }

    /**
     * Someone's attack on an owner, or on one of the owner's pets, went through (for a player, PvP is on where it
     * happened): for {@link #AGGRESSION_TICKS} after it, the owner's pets defend them against that attacker, a player
     * with their pets, a mob or a wild pet. Only owners with a pet out are tracked, and nobody is their own aggressor.
     */
    public void attacked(UUID owner, UUID attacker) {
        if (owner == null || attacker == null || owner.equals(attacker) || !ownedBodies.containsKey(owner)) return;
        aggressors.computeIfAbsent(owner, ignored -> new HashMap<>()).put(attacker, ticks + AGGRESSION_TICKS);
    }

    /** The players this owner's pets defend against right now. */
    public Set<UUID> aggressors(UUID owner) {
        Map<UUID, Long> attackers = owner == null ? null : aggressors.get(owner);
        if (attackers == null) return Set.of();
        attackers.values().removeIf(until -> until <= ticks);
        if (attackers.isEmpty()) {
            aggressors.remove(owner);
            return Set.of();
        }
        return Set.copyOf(attackers.keySet());
    }

    public boolean equip(Player player, PetRecord record) {
        PetCollection collection = collection(player);
        int limit = PetsConfig.maxEquippedPets;
        if (limit > 0 && !record.equipped && collection.equipped().size() >= limit) {
            player.sendMessage(ChatColorConverter.convert(PetsConfig.maxEquippedMessage.replace("$amount", String.valueOf(limit))));
            return false;
        }
        record.equipped = true;
        collection.save();
        spawnOwned(player, record);
        player.sendMessage(ChatColorConverter.convert(PetsConfig.equippedMessage.replace("$pet", displayName(record))));
        return true;
    }

    public void unequip(Player player, PetRecord record) {
        // Sent home, it comes back beside its owner next time, wherever it was told to stay.
        record.stay = null;
        record.equipped = false;
        collection(player).save();
        unspawnOwned(player.getUniqueId(), record);
        player.sendMessage(ChatColorConverter.convert(PetsConfig.unequippedMessage.replace("$pet", displayName(record))));
    }

    /** The lowest follow slot none of these takes, so a pet brought out fills the gap a pet sent home left. */
    static int freeSlot(Collection<Integer> taken) {
        int slot = 0;
        while (taken.contains(slot)) slot++;
        return slot;
    }

    /** Whether the player owns as many pets as pets.yml's maxOwnedPets allows; never when it is 0. */
    public boolean atOwnedCap(Player player) {
        return atCap(PetsConfig.maxOwnedPets, collection(player).pets().size());
    }

    static boolean atCap(int limit, int owned) {
        return limit > 0 && owned >= limit;
    }

    private void sayOwnedCap(Player player) {
        player.sendMessage(ChatColorConverter.convert(PetsConfig.maxOwnedMessage.replace("$amount", String.valueOf(PetsConfig.maxOwnedPets))));
    }

    /**
     * Sets a pet free for good: it leaves the player's collection and the world. In CREATURES mode one that was out
     * stays where it was as a wild pet of the same colour and age, for anyone to tame again.
     */
    public void release(Player player, PetRecord record) {
        PetSpecies pet = species(record.species);
        PetControl live = liveBody(player.getUniqueId(), record);
        LivingEntity body = live == null ? null : live.body();
        Location at = body == null || !body.isValid() ? null : body.getLocation();
        double health = body == null ? 0D : body.getHealth();
        if (live != null) despawn(live);
        collection(player).remove(record);
        if (pet != null && at != null && PetsConfig.mode == PetsConfig.Mode.CREATURES) {
            PetControl wild = spawn(pet, null, null, record.model, record.baby, at, at, 1D, record.stats);
            if (wild != null) {
                restoreHealth(wild.body(), health);
                if (record.baby) wild.setWildBornAt(record.bornAt);
            }
        }
        player.sendMessage(ChatColorConverter.convert(PetsConfig.releasedMessage.replace("$pet", displayName(record))));
    }

    /** Adds a new adult of the species wearing this variant to the player's collection and brings it out. */
    public PetRecord give(Player player, PetSpecies pet, String model) {
        PetRecord record = new PetRecord(pet.id(), model, false, System.currentTimeMillis());
        record.stats = PetStats.wild(ThreadLocalRandom.current());
        record.origin = PetRecord.GIVEN;
        int limit = PetsConfig.maxEquippedPets;
        PetCollection collection = collection(player);
        if (limit > 0 && collection.equipped().size() >= limit) record.equipped = false;
        collection.add(record);
        if (record.equipped) spawnOwned(player, record);
        return record;
    }

    public String displayName(PetRecord record) {
        if (record.name != null && !record.name.isBlank()) return ChatColorConverter.convert(record.name);
        PetSpecies pet = species(record.species);
        String name = pet == null ? record.species : ChatColorConverter.convert(pet.name(record.model));
        return record.baby ? ChatColorConverter.convert("&7Baby ") + name : name;
    }

    private String displayName(PetControl control) {
        if (control.record() != null) return displayName(control.record());
        return ChatColorConverter.convert(control.species().name(control.model()));
    }

    /** What floats above the pet: like any mob, only the name a name tag gave it; none until then. */
    private static String nameplate(PetControl control) {
        PetRecord record = control.record();
        return record == null || record.name == null || record.name.isBlank() ? null : ChatColorConverter.convert(record.name);
    }

    // ---- interactions ----------------------------------------------------------------------------------------

    /**
     * A player right-clicked a pet. Wild adults are tamed with their species' food; owned pets are fed their breeding
     * food, or told to stay and to follow again by any other click, as a dog is. Returns whether the click was
     * consumed; a lead passes through to the vanilla leash.
     */
    public boolean interact(Player player, PetControl control) {
        // A click can reach us from the model and from the body in the same moment; one interaction per few ticks.
        if (control.lastInteractionTick() + INTERACTION_DEBOUNCE_TICKS > ticks) return true;
        control.setLastInteractionTick(ticks);
        PetSpecies pet = control.species();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (control.isWild()) {
            if (held.getType() == Material.NAME_TAG) {
                player.sendMessage(ChatColorConverter.convert(PetsConfig.nameTagWildMessage.replace("$pet", displayName(control))));
                return true;
            }
            if (PetsConfig.mode != PetsConfig.Mode.CREATURES || control.isBaby() || !pet.isTamingFood(held)) return held.getType() != Material.LEAD;
            if (atOwnedCap(player)) {
                sayOwnedCap(player);
                return true;
            }
            consume(player, held);
            if (ThreadLocalRandom.current().nextDouble() < pet.tameChance()) {
                tame(player, control);
            } else {
                particles(control, Particle.SMOKE, 8);
                player.sendMessage(ChatColorConverter.convert(PetsConfig.tameFailedMessage.replace("$pet", displayName(control))));
            }
            return true;
        }
        if (!control.isOwnedBy(player)) return true;
        // Sneaking, a click opens its passport, unless the click is a name tag, its food or a lead, which keep their use.
        if (player.isSneaking() && held.getType() != Material.NAME_TAG && held.getType() != Material.LEAD
                && !pet.isBreedingFood(held)) {
            PetPassport.open(this, player, control.record());
            return true;
        }
        // A named name tag names it, as it names any mob; it is used up outside creative. A blank one does nothing.
        if (held.getType() == Material.NAME_TAG) {
            ItemMeta tag = held.getItemMeta();
            if (tag != null && tag.hasDisplayName() && !tag.getDisplayName().isBlank()) {
                control.record().name = tag.getDisplayName();
                collection(player).save();
                DynamicEntity modeled = control.modeled();
                if (modeled != null && !modeled.isRemoved()) modeled.setDisplayName(nameplate(control));
                if (player.getGameMode() != GameMode.CREATIVE) held.setAmount(held.getAmount() - 1);
                particles(control, Particle.HEART, 3);
            } else player.sendMessage(ChatColorConverter.convert(PetsConfig.nameTagBlankMessage));
            return true;
        }
        if (!control.isBaby() && pet.isBreedingFood(held)) {
            if (!control.canBreed()) {
                player.sendMessage(ChatColorConverter.convert(PetsConfig.breedingCooldownMessage.replace("$pet", displayName(control.record()))));
                return true;
            }
            if (atOwnedCap(player)) {
                sayOwnedCap(player);
                return true;
            }
            consume(player, held);
            control.setLoveUntil(ticks + pet.loveTicks());
            particles(control, Particle.HEART, 7);
            tryBreed(control);
            return true;
        }
        if (held.getType() == Material.LEAD) return false;
        // Anything else is the dog gesture: a click tells it to stay, the next one to follow again.
        if (pet.canStay()) {
            boolean stay = !control.isStaying();
            LivingEntity body = control.body();
            setStay(player, control, stay, body == null ? player.getLocation() : body.getLocation());
            player.sendMessage(ChatColorConverter.convert((stay ? PetsConfig.stayMessage : PetsConfig.followMessage)
                    .replace("$pet", displayName(control.record()))));
        }
        particles(control, Particle.HEART, 2);
        return true;
    }

    private void tame(Player player, PetControl wild) {
        PetSpecies pet = wild.species();
        LivingEntity body = wild.body();
        Location at = body == null ? player.getLocation() : body.getLocation();
        PetRecord record = new PetRecord(pet.id(), wild.model(), false, System.currentTimeMillis());
        record.stats = wild.stats().copy();
        record.origin = PetRecord.TAMED;
        PetCollection collection = collection(player);
        int limit = PetsConfig.maxEquippedPets;
        if (limit > 0 && collection.equipped().size() >= limit) record.equipped = false;
        collection.add(record);
        particles(wild, Particle.HEART, 12);
        despawn(wild);
        if (record.equipped) {
            PetControl owned = spawn(pet, player.getUniqueId(), record, record.model, false, at, null);
            if (owned != null) playInteract(owned);
        }
        player.sendMessage(ChatColorConverter.convert(PetsConfig.tamedMessage.replace("$pet", displayName(record))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6F, 1.4F);
    }

    private void tryBreed(PetControl first) {
        if (!first.isInLove(ticks)) return;
        Map<String, PetControl> owned = ownedBodies.get(first.ownerId());
        LivingEntity body = first.body();
        if (owned == null || body == null) return;
        for (PetControl second : owned.values()) {
            if (second == first || second.species() != first.species() || !second.isInLove(ticks) || second.isBaby()) continue;
            LivingEntity other = second.body();
            if (other == null || other.getWorld() != body.getWorld() || other.getLocation().distance(body.getLocation()) > BREED_DISTANCE) continue;
            breed(first, second);
            return;
        }
    }

    private void breed(PetControl mother, PetControl father) {
        PetSpecies pet = mother.species();
        Player owner = mother.owner().orElse(null);
        if (owner == null) return;
        mother.endLove();
        father.endLove();
        long cooldownUntil = System.currentTimeMillis() + pet.breedingCooldownMillis();
        mother.record().breedCooldownUntil = cooldownUntil;
        father.record().breedCooldownUntil = cooldownUntil;
        // From the parents' records, not the models they wear: a parent whose variant model is missing here wears the
        // species' model, and its baby would keep that for good.
        String model = pet.inherit(mother.record().model, father.record().model, ThreadLocalRandom.current());
        PetRecord mum = mother.record(), dad = father.record();
        PetRecord baby = new PetRecord(pet.id(), model, true, System.currentTimeMillis());
        baby.parents = List.of(displayName(mum), displayName(dad));
        baby.generation = PetRecord.childGeneration(mum, dad);
        baby.stats = PetStats.inherit(mum.stats, dad.stats, pet.statCap(), ThreadLocalRandom.current());
        baby.origin = model.equals(mum.model) || model.equals(dad.model) ? PetRecord.BRED : PetRecord.MUTATED;
        baby.family = List.of(asParent(mum), asParent(dad));
        PetRecord.Relative born = new PetRecord.Relative(baby.id, null, baby.model, null);
        for (PetRecord parent : List.of(mum, dad)) {
            if (parent.babies == null) parent.babies = new ArrayList<>();
            parent.babies.add(born);
        }
        PetCollection collection = collection(owner);
        int limit = PetsConfig.maxEquippedPets;
        if (limit > 0 && collection.equipped().size() >= limit) baby.equipped = false;
        collection.add(baby);
        particles(mother, Particle.HEART, 10);
        particles(father, Particle.HEART, 10);
        // The interact animation marks a success (a tame, a birth), not every feeding, which made it spam.
        playInteract(mother);
        playInteract(father);
        if (baby.equipped) {
            LivingEntity body = mother.body();
            spawn(pet, owner.getUniqueId(), baby, model, true, body == null ? owner.getLocation() : body.getLocation(), null);
        }
        owner.sendMessage(ChatColorConverter.convert(PetsConfig.bornMessage.replace("$pet", displayName(baby))));
    }

    /** A parent as its baby's family keeps it: its id, name and colour, with its own parents but not theirs. */
    static PetRecord.Relative asParent(PetRecord parent) {
        List<PetRecord.Relative> grandparents = new ArrayList<>();
        if (parent.family != null)
            for (PetRecord.Relative grandparent : parent.family)
                grandparents.add(new PetRecord.Relative(grandparent.id, grandparent.name, grandparent.model, null));
        return new PetRecord.Relative(parent.id, parent.name, parent.model, grandparents);
    }

    private static void consume(Player player, ItemStack held) {
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EAT, 1F, 1F);
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        held.setAmount(held.getAmount() - 1);
    }

    private static void playInteract(PetControl control) {
        DynamicEntity modeled = control.modeled();
        if (modeled != null && !modeled.isRemoved()) modeled.playAnimation("interact", true, false);
    }

    private static void particles(PetControl control, Particle particle, int count) {
        LivingEntity body = control.body();
        if (body == null || !body.isValid()) return;
        Location at = body.getLocation().add(0D, body.getHeight() * 0.5D, 0D);
        body.getWorld().spawnParticle(particle, at, count, 0.5D, 0.5D, 0.5D, 0D);
    }

    /** A pet body died: drop it from the maps, and send an owned pet home or out of the collection. */
    public void bodyDied(PetControl control) {
        forget(control);
        MindHandle mind = control.mind();
        if (mind != null) {
            try {
                mind.close();
            } catch (RuntimeException ignored) {
                // Its body is already gone.
            }
        }
        DynamicEntity modeled = control.modeled();
        if (modeled != null && !modeled.isRemoved()) modeled.remove();
        Player owner = control.owner().orElse(null);
        if (owner == null || control.record() == null) return;
        owner.sendMessage(ChatColorConverter.convert(PetsConfig.diedMessage.replace("$pet", displayName(control.record()))));
        PetCollection collection = collection(owner);
        if (PetsConfig.petsDiePermanently) collection.remove(control.record());
        else {
            // It goes home and recovers there.
            control.record().equipped = false;
            control.record().stay = null;
            control.record().health = 0D;
            collection.save();
        }
    }

    // ---- watchdog ---------------------------------------------------------------------------------------------

    private void watch() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PetCollection collection = collections.get(player.getUniqueId());
            if (collection == null) continue;
            boolean healthChanged = false;
            for (PetRecord record : collection.equipped()) {
                PetSpecies pet = species(record.species);
                if (pet == null) continue;
                if (record.baby && PetsConfig.babiesGrowUp && now >= record.bornAt + pet.babyGrowthMillis()) {
                    record.baby = false;
                    collection.save();
                    spawnOwned(player, record);
                    continue;
                }
                PetControl live = liveBody(player.getUniqueId(), record);
                LivingEntity body = live == null ? null : live.body();
                if (live != null && body != null && body.isValid()) healthChanged |= rememberHealth(live);
                if (record.stay != null) {
                    // Told to stay: it keeps its spot in its own world, as a sitting wolf does. While that chunk is
                    // unloaded it waits, and it comes back once the chunk loads.
                    if (body == null || !body.isValid()) {
                        if (live != null) despawn(live);
                        spawnOwned(player, record);
                    }
                    continue;
                }
                // Lost to a chunk unload, a world change or a teleport too far: bring the pet back beside its owner.
                if (body == null || !body.isValid() || body.getWorld() != player.getWorld()) {
                    if (live != null) despawn(live);
                    spawnOwned(player, record);
                }
            }
            if (healthChanged) collection.save();
        }
        for (PetControl control : new ArrayList<>(byBody.values())) {
            if (!control.isWild()) continue;
            LivingEntity body = control.body();
            // A wild baby (one set free) grows up as an owned one does, where it is, keeping its health.
            if (control.isBaby() && control.route() == null && body != null && body.isValid() && PetsConfig.babiesGrowUp
                    && now >= control.bornAt() + control.species().babyGrowthMillis()) {
                Location at = body.getLocation();
                double health = body.getHealth();
                despawn(control);
                PetControl grown = spawn(control.species(), null, null, control.model(), false, at, control.home(), 1D,
                        control.stats());
                if (grown != null) restoreHealth(grown.body(), health);
                continue;
            }
            // A migrant's flock decides when it leaves, whatever the mode; a lost migrant just goes.
            if (control.route() != null) {
                if (body == null || !body.isValid()) despawn(control);
                continue;
            }
            // Its chunk unloaded or stopped ticking (the model has let go of it by then), or the server turned
            // cosmetic: it waits in the store and never despawns. A real death went through bodyDied instead.
            if (body == null || !body.isValid() || PetsConfig.mode != PetsConfig.Mode.CREATURES) stash(control);
        }
        restoreNearPlayers();
    }

    // ---- wild pets that stay -----------------------------------------------------------------------------------

    /** A newly generated chunk loaded; it is stocked next tick. */
    public void chunkGenerated(World world, int cx, int cz) {
        toStock.add(new PendingChunk(world.getUID(), cx, cz, ticks + 1));
    }

    /**
     * A plugin removed a wild pet's body. FreeMinecraftModels itself removes the body of a model whose chunk stopped
     * ticking or is unloading; that body is no longer valid, and the pet waits in the store. A live body that another
     * plugin removes is gone, as if it had died.
     */
    public void bodyRemovedByPlugin(Entity entity) {
        PetControl control = pet(entity);
        if (control == null || !control.isWild() || control.route() != null) return;
        if (!entity.isValid()) store(control);
        bodyDied(control);
    }

    /** Brings each loaded chunk's persistent data up to date with its waiting wild pets; called with every world save. */
    public void saveWild() {
        for (UUID worldId : wildStore.worlds()) {
            World world = Bukkit.getWorld(worldId);
            if (world == null) continue;
            for (long chunk : wildStore.chunks(worldId)) {
                int cx = (int) (chunk >> 32), cz = (int) chunk;
                if (world.isChunkLoaded(cx, cz)) writeChunk(world.getChunkAt(cx, cz));
            }
        }
    }

    /**
     * At shutdown: every waiting wild pet goes into its chunk's persistent data, loading a chunk that is not loaded (one
     * whose pet was lost after it unloaded). Once all are there, the old wild_pets.yml goes.
     */
    private void flushWild() {
        boolean all = true;
        for (UUID worldId : wildStore.worlds()) {
            World world = Bukkit.getWorld(worldId);
            if (world == null) {
                all = false;
                continue;
            }
            for (long chunk : wildStore.chunks(worldId)) {
                Chunk loaded = world.getChunkAt((int) (chunk >> 32), (int) chunk);
                writeChunk(loaded);
                wildStore.take(worldId, loaded.getX(), loaded.getZ());
            }
        }
        if (all && legacyWildFile.isFile() && !legacyWildFile.delete())
            Logger.warn("Could not delete " + legacyWildFile + "; its wild pets are in chunk data now, so delete it by hand.");
    }

    /** Writes a loaded chunk's waiting wild pets into its persistent data, or clears it when none wait there. */
    private void writeChunk(Chunk chunk) {
        List<WildPetStore.Entry> entries = wildStore.peek(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        if (entries.isEmpty()) data.remove(wildKey);
        else data.set(wildKey, PersistentDataType.STRING, WildPetStore.encodeAll(entries));
    }

    /** Keeps a loaded chunk's persistent data in step after its waiting pets changed; an unloaded one waits. */
    private void syncChunk(World world, int cx, int cz) {
        if (world.isChunkLoaded(cx, cz)) writeChunk(world.getChunkAt(cx, cz));
    }

    /**
     * A chunk loaded: the wild pets its persistent data keeps join any that were lost there while it was unloaded, and
     * wait in the store for a player to come near. Wild pets from the old wild_pets.yml join their chunk the same way.
     */
    public void chunkLoaded(Chunk chunk) {
        UUID worldId = chunk.getWorld().getUID();
        List<WildPetStore.Entry> saved = WildPetStore.decodeAll(
                chunk.getPersistentDataContainer().get(wildKey, PersistentDataType.STRING));
        List<WildPetStore.Entry> pending = wildStore.take(worldId, chunk.getX(), chunk.getZ());
        if (saved.isEmpty() && pending.isEmpty()) return;
        for (WildPetStore.Entry entry : saved) wildStore.put(worldId, entry);
        for (WildPetStore.Entry entry : pending) wildStore.put(worldId, entry);
        writeChunk(chunk);
    }

    /**
     * A chunk is unloading: its live wild pets join the store, and its waiting pets go into its persistent data, saved
     * with it; they leave memory until it loads again.
     */
    public void chunkUnloading(Chunk chunk) {
        World world = chunk.getWorld();
        for (PetControl control : new ArrayList<>(byBody.values())) {
            LivingEntity body = control.body();
            if (!control.isWild() || control.route() != null || body == null || body.getWorld() != world) continue;
            Location at = body.getLocation();
            if (at.getBlockX() >> 4 == chunk.getX() && at.getBlockZ() >> 4 == chunk.getZ()) {
                store(control);
                despawn(control);
            }
        }
        writeChunk(chunk);
        wildStore.take(world.getUID(), chunk.getX(), chunk.getZ());
    }

    /** At load: the old file's pets and those in the chunks already loaded (a reload keeps them) join the store. */
    private void loadWild() {
        for (Map.Entry<UUID, List<WildPetStore.Entry>> world : WildPetStore.readLegacy(legacyWildFile).entrySet())
            for (WildPetStore.Entry entry : world.getValue()) wildStore.put(world.getKey(), entry);
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) chunkLoaded(chunk);
    }

    private void workChunks() {
        while (!toStock.isEmpty() && toStock.peek().due() <= ticks) {
            PendingChunk chunk = toStock.poll();
            World world = Bukkit.getWorld(chunk.world());
            if (world != null && world.isChunkLoaded(chunk.cx(), chunk.cz()))
                WildSpawner.stockChunk(this, world, chunk.cx(), chunk.cz(), species.values());
        }
    }

    /**
     * Brings back the pets waiting in the loaded chunks within 8 chunks of a player. Checking every watchdog pass,
     * rather than on chunk loads, also catches a chunk that stopped ticking and came back without unloading, a pet
     * that left with an unload the chunk has already recovered from, and a switch back from cosmetic mode.
     */
    private void restoreNearPlayers() {
        if (PetsConfig.mode != PetsConfig.Mode.CREATURES) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            UUID worldId = world.getUID();
            int px = player.getLocation().getBlockX() >> 4, pz = player.getLocation().getBlockZ() >> 4;
            for (int dx = -WildSpawner.SPAWN_DISTANCE_CHUNKS; dx <= WildSpawner.SPAWN_DISTANCE_CHUNKS; dx++)
                for (int dz = -WildSpawner.SPAWN_DISTANCE_CHUNKS; dz <= WildSpawner.SPAWN_DISTANCE_CHUNKS; dz++)
                    if (wildStore.has(worldId, px + dx, pz + dz) && world.isChunkLoaded(px + dx, pz + dz)) restore(world, px + dx, pz + dz);
        }
    }

    /** Puts a wild pet in the store at the place its body was last seen, then clears it from the world. */
    private void stash(PetControl control) {
        store(control);
        despawn(control);
    }

    private void store(PetControl control) {
        LivingEntity body = control.body();
        if (body == null) return;
        Location at = body.getLocation(), home = control.home() == null ? at : control.home();
        wildStore.put(body.getWorld().getUID(), new WildPetStore.Entry(control.species().id(), control.model(), control.isBaby(),
                at.getX(), at.getY(), at.getZ(), home.getX(), home.getY(), home.getZ(), body.isDead() ? 0D : body.getHealth(),
                control.isBaby() ? control.bornAt() : 0L, control.stats()));
        syncChunk(body.getWorld(), at.getBlockX() >> 4, at.getBlockZ() >> 4);
    }

    /** Brings back the pets waiting in a loaded chunk; pets of a species that is not installed keep waiting. */
    private void restore(World world, int cx, int cz) {
        for (WildPetStore.Entry entry : wildStore.take(world.getUID(), cx, cz)) {
            PetSpecies pet = species(entry.species());
            PetControl back = pet == null ? null
                    : spawn(pet, null, null, entry.model(), entry.baby(), entry.at(world), entry.home(world), 1D, entry.stats());
            if (back == null) {
                wildStore.put(world.getUID(), entry);
                continue;
            }
            restoreHealth(back.body(), entry.health());
            // A baby from before births were kept starts growing now.
            if (entry.baby()) back.setWildBornAt(entry.bornAt() > 0L ? entry.bornAt() : System.currentTimeMillis());
        }
        writeChunk(world.getChunkAt(cx, cz));
    }

    // ---- wild spawning ----------------------------------------------------------------------------------------

    /**
     * Wild pets of all species, or of one, in the player's spawn area: the chunks within 8 of theirs. Migrants pass
     * through and do not fill the caps.
     */
    int wildInSpawnArea(Player player, PetSpecies only) {
        int count = 0;
        for (PetControl control : byBody.values()) {
            if (!control.isWild() || control.route() != null || (only != null && control.species() != only)) continue;
            LivingEntity body = control.body();
            if (body != null && body.isValid() && WildSpawner.inSpawnArea(player, body.getLocation())) count++;
        }
        return count;
    }
}
