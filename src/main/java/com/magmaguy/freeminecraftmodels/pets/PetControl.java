package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.magmacore.ai.MindHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * The live state of one pet body: who owns it, whether it is told to stay, where it idles, and the timers taming and
 * breeding run on. Owned by {@link PetManager}; the Mind behaviors read it every tick on the server thread, so nothing
 * here needs synchronization.
 */
public final class PetControl {
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
    // Ticks a wild pet has gone without a player within 32 blocks: vanilla's noActionTime, which starts its despawn.
    private int aloneTicks;

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

    /** Adds the ticks since the last check while no player is near, or starts over when one is; returns the total. */
    int countAlone(boolean playerNear, int ticks) {
        aloneTicks = playerNear ? 0 : aloneTicks + ticks;
        return aloneTicks;
    }

    long lastInteractionTick() {
        return lastInteractionTick;
    }

    void setLastInteractionTick(long tick) {
        this.lastInteractionTick = tick;
    }
}
