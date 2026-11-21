package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.magmacore.ai.MindHandle;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The live state of one pet body: who owns it, whether it is told to stay, where it idles, and the timers taming and
 * breeding run on. Owned by {@link PetManager}; the Mind behaviors read it every tick on the server thread, so nothing
 * here needs synchronization.
 */
public final class PetControl {
    /** The most players a wild pet remembers touching it between looks; past that, more wait for the next touch. */
    private static final int MAX_CONTACTS = 16;
    /**
     * How long a touch waits for a behavior module to take it, in pet clock ticks. A pet whose modules never take
     * touches, one without a sting, forgets them unseen, so none waits to sting long after it happened.
     */
    private static final long CONTACT_TICKS = 20L;

    private final PetSpecies species;
    private final UUID owner;
    private final PetRecord record;
    private final String model;
    private final boolean baby;
    private LivingEntity body;
    private DynamicEntity modeled;
    private MindHandle mind;
    private boolean stay;
    private Location stayAnchor;
    // Where a wild pet drifts around; an owned pet drifts around its owner instead.
    private Location home;
    // Where its colony keeps it for now, in place of home, and the water it fishes there (see PetColonies); null at
    // home, and never saved, so home stays where the pet lives.
    private Location colonyPlace, colonyWater;
    private long farSince = PetCatchUp.NEVER;
    private int watchedAge = -1;
    private Runnable movedListener;
    private long loveUntilTick;
    // The pet clock tick its health display goes away (0 while none shows), and the health it last showed.
    private long healthBarUntil;
    private double shownHealth = -1D;
    // The rider its nameplate is kept from now, who is shown none of its lines; else null.
    private UUID nameplateSpared;
    // Where it last stood and how far it has walked since its last footstep.
    private final PetSteps.Walk walk = new PetSteps.Walk();
    // The pet clock tick it next regains a point of health on its own.
    private long nextRegenTick;
    // Epoch milliseconds a wild baby was born (one set free keeps its own); an owned pet's is on its record.
    private long wildBornAt;
    // A wild pet's stats; an owned pet's are on its record.
    private PetStats wildStats;
    private long lastInteractionTick = Long.MIN_VALUE / 2;
    // Set on a wild pet flying a migration; null for every other pet.
    private MigrationRoute route;
    // Its place among the pets its owner has out: which spot around the trail point it follows to. 0 for a wild pet.
    private int slot;
    // The player whose hit on this pet last went through, the pet clock tick of that hit, and how many such hits it
    // has taken; the retaliate module answers each hit once.
    private UUID hurtBy;
    private long hurtTick = Long.MIN_VALUE / 2;
    private int hurtCount;
    // Players whose touch reached this wild pet's model since its sting last looked, each with the pet clock tick of
    // their latest touch; a map, so a long touch counts once.
    private final Map<UUID, Long> contacts = new LinkedHashMap<>();
    // Whether its program reads those touches; a pet whose program never does records none.
    private boolean readsContacts;

    PetControl(PetSpecies species, UUID owner, PetRecord record, String model, boolean baby, Location home) {
        this.species = species;
        this.owner = owner;
        this.record = record;
        this.model = model;
        this.baby = baby;
        this.home = home == null ? null : home.clone();
    }

    public PetSpecies species() {
        return species;
    }

    /** The owning player's id, or null for a wild pet. */
    public UUID ownerId() {
        return owner;
    }

    public boolean isWild() {
        return owner == null;
    }

    /** The pet tick the watchdog first found it past its teleport distance, or {@link PetCatchUp#NEVER} (see there). */
    long farSince() {
        return farSince;
    }

    void setFarSince(long tick) {
        this.farSince = tick;
    }

    /** Its body's age at the watchdog's last look, to tell a body that has stopped ticking; -1 before the first. */
    int watchedAge() {
        return watchedAge;
    }

    void setWatchedAge(int age) {
        this.watchedAge = age;
    }

    /** What to run when something other than its own behaviors moves its body: its c.pet table's (see {@link #moved}). */
    void onMoved(Runnable listener) {
        this.movedListener = listener;
    }

    /**
     * Its body was moved from outside its behaviors, as when the watchdog brings it to its owner: its c.pet table
     * drops the path it was walking and reads its position afresh, as after its own catch-up teleport.
     */
    void moved() {
        if (movedListener != null) movedListener.run();
    }

    public boolean isOwnedBy(Player player) {
        return owner != null && owner.equals(player.getUniqueId());
    }

    /** The owner while they are online and alive in a world. */
    public Optional<Player> owner() {
        if (owner == null) return Optional.empty();
        Player player = Bukkit.getPlayer(owner);
        return player != null && player.isOnline() && !player.isDead() ? Optional.of(player) : Optional.empty();
    }

    /**
     * The owner as someone the pet can be with: {@link #owner()}, but nobody while they are a spectator. Everything
     * a pet does about its owner asks this: following and catching up, where it roams, what it brings them, whom it
     * defends. A spectator is a camera that flies through blocks, faster than any pet, to places with nowhere to
     * stand; a pet that tried to keep up ran after them, teleported into whatever they flew through, or was left in
     * chunks that had stopped ticking. So while its owner is a spectator a pet stays where they left it, as it does
     * while they are dead, and as a tamed wolf does; once they play again it comes to them.
     */
    public Optional<Player> companion() {
        return owner().filter(player -> keepsCompany(player.getGameMode()));
    }

    /** Whether a pet can be with an owner in this game mode: any but spectator. */
    static boolean keepsCompany(GameMode mode) {
        return mode != GameMode.SPECTATOR;
    }

    /** The collection entry behind an owned pet; null for a wild one. */
    public PetRecord record() {
        return record;
    }

    public String model() {
        return model;
    }

    public boolean isBaby() {
        return baby;
    }

    long healthBarUntil() {
        return healthBarUntil;
    }

    void setHealthBarUntil(long tick) {
        this.healthBarUntil = tick;
    }

    double shownHealth() {
        return shownHealth;
    }

    void setShownHealth(double health) {
        this.shownHealth = health;
    }

    PetSteps.Walk walk() {
        return walk;
    }

    UUID nameplateSpared() {
        return nameplateSpared;
    }

    void setNameplateSpared(UUID rider) {
        this.nameplateSpared = rider;
    }

    long nextRegenTick() {
        return nextRegenTick;
    }

    void setNextRegenTick(long tick) {
        this.nextRegenTick = tick;
    }

    /** Epoch milliseconds the pet was born or tamed; 0 for a wild adult. */
    public long bornAt() {
        return record != null ? record.bornAt : wildBornAt;
    }

    void setWildBornAt(long bornAt) {
        this.wildBornAt = bornAt;
    }

    /** Its stats: an owned pet's from its record, a wild one's as it rolled or brought them; the base when unknown. */
    public PetStats stats() {
        return PetStats.orBase(record != null ? record.stats : wildStats);
    }

    void setWildStats(PetStats stats) {
        this.wildStats = stats;
    }

    /** Its share of its stats while a baby: health, sting and poison are halved until it grows up. */
    public double babyShare() {
        return baby ? PetStats.BABY_SHARE : 1D;
    }

    public LivingEntity body() {
        return body;
    }

    /**
     * Kin: a pet of this species, wild like this one or of the same owner, alive, neither migrating nor carrying a
     * rider. A pet is not its own kin. Huddles and marches gather kin.
     */
    boolean isKin(PetControl other) {
        if (other == null || other == this || !other.species().id().equals(species().id())) return false;
        if (other.isWild() != isWild()) return false;
        if (!isWild() && !ownerId().equals(other.ownerId())) return false;
        LivingEntity otherBody = other.body();
        return other.route() == null && otherBody != null && otherBody.isValid() && otherBody.isEmpty();
    }

    public DynamicEntity modeled() {
        return modeled;
    }

    MindHandle mind() {
        return mind;
    }

    void attach(LivingEntity body, DynamicEntity modeled, MindHandle mind) {
        this.body = body;
        this.modeled = modeled;
        this.mind = mind;
    }

    /** The migration this wild pet is flying, or null. */
    public MigrationRoute route() {
        return route;
    }

    void setRoute(MigrationRoute route) {
        this.route = route;
    }

    /** A player's hit on this pet went through. */
    void hurtBy(UUID player, long tick) {
        hurtBy = player;
        hurtTick = tick;
        hurtCount++;
    }

    /** The player who last hurt it, while that hit is at most {@code withinTicks} old; null otherwise. */
    public UUID attacker(long now, long withinTicks) {
        return hurtBy != null && now - hurtTick <= withinTicks ? hurtBy : null;
    }

    /** How many times a player has hurt it since its body spawned. */
    public int hurtCount() {
        return hurtCount;
    }

    /** Its place among the pets its owner has out, from 0; the first keeps the trail point itself. */
    public int slot() {
        return slot;
    }

    void setSlot(int slot) {
        this.slot = slot;
    }

    public boolean isStaying() {
        return stay;
    }

    public void setStay(boolean stay, Location at) {
        this.stay = stay;
        this.stayAnchor = stay && at != null ? at.clone() : null;
    }

    public Location stayAnchor() {
        return stayAnchor == null ? null : stayAnchor.clone();
    }

    public Location home() {
        return home == null ? null : home.clone();
    }

    /** Where its colony keeps this wild pet for now, its shore by day; null when that is home. */
    Location colonyPlace() {
        return colonyPlace == null ? null : colonyPlace.clone();
    }

    /** The water its colony fishes, while the colony is at its shore; else null. */
    Location colonyWater() {
        return colonyWater == null ? null : colonyWater.clone();
    }

    /** Set by its colony: where it keeps the pet for now (null for home), and the water it fishes there. */
    void setColonyPlace(Location place, Location water) {
        this.colonyPlace = place == null ? null : place.clone();
        this.colonyWater = water == null ? null : water.clone();
    }

    /**
     * How far from its owner the pet ranges before following, and the most its owner can be from what it goes to
     * (a fire, a perch): the follow distance while the owner is on the move, the idle distance while they stand about.
     * A wild pet's is the follow distance.
     */
    public double followRange() {
        PetManager manager = owner == null ? null : PetManager.get();
        if (manager == null || manager.ownerMoving(owner)) return species.followDistance();
        return species.idleDistance();
    }

    /**
     * How far from its anchor a drift, walk or swim may end: the roam radius around home when wild; around the owner,
     * the roam radius or 0.6 of the follow range, whichever is more, and never past 0.8 of it, so a pet arriving
     * there is still in range.
     */
    public double roamRange() {
        if (owner == null) return species.roamRadius();
        return roamRange(species.roamRadius(), followRange());
    }

    static double roamRange(double roamRadius, double followRange) {
        return Math.min(followRange * 0.8D, Math.max(roamRadius, followRange * 0.6D));
    }

    /** Distance to the owner, or infinity when the owner is away or in another world. */
    public double ownerDistance(LivingEntity body) {
        Player player = companion().orElse(null);
        if (player == null || body == null || player.getWorld() != body.getWorld()) return Double.POSITIVE_INFINITY;
        return player.getLocation().distance(body.getLocation());
    }

    public boolean isInLove(long tick) {
        return tick < loveUntilTick;
    }

    void setLoveUntil(long tick) {
        this.loveUntilTick = tick;
    }

    void endLove() {
        this.loveUntilTick = 0;
    }

    /** Adults breed again once the cooldown on their record has run out. */
    public boolean canBreed() {
        return !baby && (record == null || System.currentTimeMillis() >= record.breedCooldownUntil);
    }

    long lastInteractionTick() {
        return lastInteractionTick;
    }

    /**
     * A player's touch reached this wild pet's model. Up to {@link #MAX_CONTACTS} players are kept until the next
     * {@link #drainContacts}, for {@link #CONTACT_TICKS} after their latest touch; a body that is spawned again (a
     * stash, a tame, growing up) starts with none.
     */
    public void recordContact(Player player) {
        recordContact(player.getUniqueId(), PetManager.ticks());
    }

    /** {@link #recordContact(Player)} on the pet clock's tick {@code now}. */
    void recordContact(UUID player, long now) {
        if (contacts.size() >= MAX_CONTACTS) contacts.values().removeIf(tick -> now - tick > CONTACT_TICKS);
        if (contacts.size() < MAX_CONTACTS || contacts.containsKey(player)) contacts.put(player, now);
    }

    /** The players that touched the model since the last call, within {@link #CONTACT_TICKS}, and are still online. */
    public List<Player> drainContacts() {
        // Only online players are found by id.
        return drainContacts(PetManager.ticks(), Bukkit::getPlayer);
    }

    /** {@link #drainContacts()} on the pet clock's tick {@code now}, finding each player through {@code online}. */
    <T> List<T> drainContacts(long now, Function<UUID, T> online) {
        if (contacts.isEmpty()) return List.of();
        List<T> touched = new ArrayList<>(contacts.size());
        contacts.forEach((id, tick) -> {
            if (now - tick > CONTACT_TICKS) return;
            T player = online.apply(id);
            if (player != null) touched.add(player);
        });
        contacts.clear();
        return touched;
    }

    /** Whether its program reads the touches on its model, so they are worth recording. */
    public boolean readsContacts() {
        return readsContacts;
    }

    void setReadsContacts(boolean readsContacts) {
        this.readsContacts = readsContacts;
    }

    void setLastInteractionTick(long tick) {
        this.lastInteractionTick = tick;
    }
}
