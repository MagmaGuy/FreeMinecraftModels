package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.magmacore.ai.MindHandle;
import org.bukkit.Bukkit;
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
    private long loveUntilTick;
    private long lastInteractionTick = Long.MIN_VALUE / 2;
    // Set on a wild pet flying a migration; null for every other pet.
    private MigrationRoute route;
    // Its place among the pets its owner has out: which spot around the trail point it follows to. 0 for a wild pet.
    private int slot;
    // Players whose touch reached this wild pet's model since its sting last looked, each with the pet clock tick of
    // their latest touch; a map, so a long touch counts once.
    private final Map<UUID, Long> contacts = new LinkedHashMap<>();

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

    public boolean isOwnedBy(Player player) {
        return owner != null && owner.equals(player.getUniqueId());
    }

    /** The owner while they are online and alive in a world. */
    public Optional<Player> owner() {
        if (owner == null) return Optional.empty();
        Player player = Bukkit.getPlayer(owner);
        return player != null && player.isOnline() && !player.isDead() ? Optional.of(player) : Optional.empty();
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

    public LivingEntity body() {
        return body;
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

    void setModeled(DynamicEntity modeled) {
        this.modeled = modeled;
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

    /** Distance to the owner, or infinity when the owner is away or in another world. */
    public double ownerDistance(LivingEntity body) {
        Player player = owner().orElse(null);
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

    void setLastInteractionTick(long tick) {
        this.lastInteractionTick = tick;
    }
}
