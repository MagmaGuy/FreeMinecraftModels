package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.ModelsFolder;
import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.pets.interactions.PetInteractionRuntime;
import com.magmaguy.magmacore.ai.MindBodyProfile;
import com.magmaguy.magmacore.ai.MindHandle;
import com.magmaguy.magmacore.ai.MindHost;
import com.magmaguy.magmacore.ai.MobBody;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.persistence.PersistentDataType;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Owns every pet: the species read from the {@code *.pet.yml} files in the models folder, the bodies alive in the
 * world, and the collections of online players. Bodies are vanilla mobs (an allay by default) carrying a
 * FreeMinecraftModels model and driven by a MagmaCore Mind program; they are never saved with their chunk, so an
 * owned pet is re-created next to its owner, and a wild one waits in the {@link WildPetStore} until its chunk loads
 * again. Everything runs on the server thread.
 */
public final class PetManager {
    private static final double BREED_DISTANCE = 8D;
    private static final int INTERACTION_DEBOUNCE_TICKS = 5;

    // The Mind host belongs to this plugin enable and survives content reloads; NMSManager shuts it down on disable.
    private static MindHost host;
    private static BukkitTask clock;
    // The watchdog's period: owned pets brought back, babies grown, wild pets despawned.
    private static final int WATCH_TICKS = 20;
    private static long ticks;
    private static NamespacedKey bodyKey;
    private static PetManager instance;

    private final Map<String, PetSpecies> species = new LinkedHashMap<>();
    private final Map<UUID, PetControl> byBody = new HashMap<>();
    private final Map<UUID, PetCollection> collections = new HashMap<>();
    private final Map<UUID, Map<String, PetControl>> ownedBodies = new HashMap<>();
    private final WildPetStore wildStore = WildPetStore.load(new File(MetadataHandler.PLUGIN.getDataFolder(), "data/wild_pets.yml"));
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
        NMSAdapter adapter = NMSManager.getAdapter();
        if (adapter == null) {
            Logger.info("Pets are off: FreeMinecraftModels has no NMS adapter for this server version.");
            return;
        }
        try {
            host = adapter.createMindHost(new NamespacedKey(plugin, "pet_mind"),
                    failure -> Logger.warn("A pet's AI stopped: " + failure));
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

    /** (Re)loads the species and brings back the equipped pets of everyone online. */
    public static void initialize() {
        shutdown();
        if (host == null) return;
        PetManager manager = new PetManager();
        manager.loadSpecies();
        instance = manager;
        for (Player player : Bukkit.getOnlinePlayers()) manager.playerJoined(player);
    }

    /**
     * Despawns every pet body; collections are already saved on the players, and wild pets go to the store to come
     * back after the restart or reload.
     */
    public static void shutdown() {
        PetManager manager = instance;
        instance = null;
        if (manager == null) return;
        for (PetControl control : new ArrayList<>(manager.byBody.values())) {
            if (control.isWild() && control.route() == null) manager.stash(control);
            else manager.despawn(control);
        }
        manager.wildStore.save();
        manager.collections.clear();
        manager.ownedBodies.clear();
    }

    private static void tickAll() {
        ticks++;
        if (host != null) host.tick();
        PetManager manager = instance;
        if (manager == null) return;
        if (ticks % WATCH_TICKS == 0) manager.watch();
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

    public PetControl spawnWild(PetSpecies pet, Location location) {
        Location at = PetMotion.hoverPoint(new PetControl(pet, null, null, null, false, location), location, 0D);
        return spawn(pet, null, null, pet.pickVariant(ThreadLocalRandom.current()), false, at, location);
    }

    /** Puts an equipped pet from the collection into the world beside its owner. */
    public PetControl spawnOwned(Player owner, PetRecord record) {
        PetSpecies pet = species(record.species);
        if (pet == null) return null;
        unspawnOwned(owner.getUniqueId(), record);
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
                at, at, sizeMultiplier);
        if (control == null) return null;
        control.setRoute(route);
        control.modeled().setViewDistanceOverride(viewDistance);
        control.modeled().setBrightnessOverride(15, 15);
        return control;
    }

    private PetControl spawn(PetSpecies pet, UUID owner, PetRecord record, String model, boolean baby, Location at, Location home) {
        return spawn(pet, owner, record, model, baby, at, home, 1D);
    }

    private PetControl spawn(PetSpecies pet, UUID owner, PetRecord record, String model, boolean baby, Location at, Location home,
                             double sizeMultiplier) {
        if (host == null || at.getWorld() == null) return null;
        String variant = model == null || FileModelConverter.getModel(model) == null ? pet.model() : model;
        String modelId = baby && pet.babyModel() != null ? pet.babyModel() : variant;
        if (FileModelConverter.getModel(modelId) == null) {
            Logger.warn("Could not spawn pet " + pet.id() + ": model " + modelId + " is not loaded.");
            return null;
        }
        PetControl control = new PetControl(pet, owner, record, variant, baby, home);
        MindHandle mind = host.open(UUID.randomUUID(), PetMindProgramFactory.create(MetadataHandler.PLUGIN, control));
        double scale = pet.scale() * (baby ? pet.babyScale() : 1D) * sizeMultiplier;
        MindBodyProfile profile = new MindBodyProfile(pet.locomotion(), scale, false, pet.body().getKey().toString());
        MobBody body;
        try {
            body = host.spawnBody(at, profile, prepared -> {
                mind.attachBody(prepared);
                prepare(prepared.entity(), pet);
            });
        } catch (RuntimeException failure) {
            mind.close();
            Logger.warn("Could not spawn pet " + pet.id() + ": " + failure.getMessage());
            return null;
        }
        LivingEntity entity = body.entity();
        DynamicEntity modeled = DynamicEntity.create(modelId, entity);
        if (modeled == null) {
            mind.close();
            entity.remove();
            Logger.warn("Could not put model " + modelId + " on pet " + pet.id() + ".");
            return null;
        }
        control.attach(entity, modeled, mind);
        // Clicks land on the model players see, not on the small body hidden inside it.
        modeled.setClickSurface(true);
        modeled.setTravelOrientation(pet.orientation());
        modeled.setCenteredOnBody(pet.centerOnBody());
        modeled.setDisplayName(displayName(control));
        byBody.put(entity.getUniqueId(), control);
        if (owner != null && record != null) ownedBodies.computeIfAbsent(owner, ignored -> new HashMap<>()).put(record.id, control);
        return control;
    }

    private static void prepare(LivingEntity entity, PetSpecies pet) {
        entity.getPersistentDataContainer().set(bodyKey, PersistentDataType.BYTE, (byte) 1);
        // Never saved with its chunk: the manager re-creates owned pets beside their owner, and wild ones are transient.
        entity.setPersistent(false);
        entity.setSilent(true);
        entity.setCanPickupItems(false);
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(pet.health());
            entity.setHealth(pet.health());
        }
    }

    /** Removes a pet body from the world and from every map. */
    public void despawn(PetControl control) {
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
        PetCollection collection = PetCollection.load(player);
        collections.put(player.getUniqueId(), collection);
        for (PetRecord record : collection.equipped()) spawnOwned(player, record);
    }

    public void playerLeft(Player player) {
        Map<String, PetControl> owned = ownedBodies.remove(player.getUniqueId());
        if (owned != null) for (PetControl control : new ArrayList<>(owned.values())) despawn(control);
        collections.remove(player.getUniqueId());
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
        record.equipped = false;
        collection(player).save();
        unspawnOwned(player.getUniqueId(), record);
        player.sendMessage(ChatColorConverter.convert(PetsConfig.unequippedMessage.replace("$pet", displayName(record))));
    }

    /** Adds a new adult of the species to the player's collection and brings it out. */
    public PetRecord give(Player player, PetSpecies pet) {
        PetRecord record = new PetRecord(pet.id(), pet.pickVariant(ThreadLocalRandom.current()), false, System.currentTimeMillis());
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
        String name = pet == null ? record.species : ChatColorConverter.convert(pet.name());
        return record.baby ? ChatColorConverter.convert("&7Baby ") + name : name;
    }

    private String displayName(PetControl control) {
        if (control.record() != null) return displayName(control.record());
        return ChatColorConverter.convert(control.species().name());
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
            if (PetsConfig.mode != PetsConfig.Mode.CREATURES || control.isBaby() || !pet.isTamingFood(held)) return held.getType() != Material.LEAD;
            consume(player, held);
            if (ThreadLocalRandom.current().nextDouble() < pet.tameChance()) {
                tame(player, control);
            } else {
                particles(control, Particle.SMOKE, 8);
                player.sendMessage(ChatColorConverter.convert(PetsConfig.tameFailedMessage.replace("$pet", ChatColorConverter.convert(pet.name()))));
            }
            return true;
        }
        if (!control.isOwnedBy(player)) return true;
        if (!control.isBaby() && pet.isBreedingFood(held)) {
            if (!control.canBreed()) {
                player.sendMessage(ChatColorConverter.convert(PetsConfig.breedingCooldownMessage.replace("$pet", displayName(control.record()))));
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
            control.setStay(stay, body == null ? player.getLocation() : body.getLocation());
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
        // The baby takes one parent's colouring, as Minecraft's variant-bearing animals do.
        String model = ThreadLocalRandom.current().nextBoolean() ? mother.model() : father.model();
        PetRecord baby = new PetRecord(pet.id(), model, true, System.currentTimeMillis());
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
        PetCollection collection = collection(owner);
        if (PetsConfig.petsDiePermanently) collection.remove(control.record());
        else {
            control.record().equipped = false;
            collection.save();
        }
    }

    // ---- watchdog ---------------------------------------------------------------------------------------------

    private void watch() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PetCollection collection = collections.get(player.getUniqueId());
            if (collection == null) continue;
            for (PetRecord record : collection.equipped()) {
                PetSpecies pet = species(record.species);
                if (pet == null) continue;
                if (record.baby && now >= record.bornAt + pet.babyGrowthMillis()) {
                    record.baby = false;
                    collection.save();
                    spawnOwned(player, record);
                    continue;
                }
                PetControl live = liveBody(player.getUniqueId(), record);
                LivingEntity body = live == null ? null : live.body();
                // Lost to a chunk unload, a world change or a teleport too far: bring the pet back beside its owner.
                if (body == null || !body.isValid() || body.getWorld() != player.getWorld()) {
                    if (live != null) despawn(live);
                    spawnOwned(player, record);
                }
            }
        }
        for (PetControl control : new ArrayList<>(byBody.values())) {
            if (!control.isWild()) continue;
            LivingEntity body = control.body();
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

    /** Writes the waiting wild pets to disk; called with every world save. */
    public void saveWild() {
        wildStore.save();
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
                at.getX(), at.getY(), at.getZ(), home.getX(), home.getY(), home.getZ()));
    }

    /** Brings back the pets waiting in a loaded chunk; pets of a species that is not installed keep waiting. */
    private void restore(World world, int cx, int cz) {
        for (WildPetStore.Entry entry : wildStore.take(world.getUID(), cx, cz)) {
            PetSpecies pet = species(entry.species());
            if (pet == null || spawn(pet, null, null, entry.model(), entry.baby(), entry.at(world), entry.home(world)) == null)
                wildStore.put(world.getUID(), entry);
        }
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
