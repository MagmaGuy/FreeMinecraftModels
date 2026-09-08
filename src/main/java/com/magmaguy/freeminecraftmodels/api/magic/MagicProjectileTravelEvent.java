package com.magmaguy.freeminecraftmodels.api.magic;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/**
 * A physical magic projectile's swept segment, checked before travel and again at impact.
 * Cancelling absorbs the projectile without damage or an explosion. A segment may be checked
 * more than once; listeners must treat this as a collision query, not a damage notification.
 * Locations are defensive copies. Fired synchronously on the server thread.
 */
public final class MagicProjectileTravelEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player shooter;
    private final MagicWeaponKind kind;
    private final Location from;
    private final Location to;
    private boolean cancelled;

    public MagicProjectileTravelEvent(Player shooter, MagicWeaponKind kind, Location from, Location to) {
        this.shooter = Objects.requireNonNull(shooter);
        this.kind = Objects.requireNonNull(kind);
        this.from = Objects.requireNonNull(from).clone();
        this.to = Objects.requireNonNull(to).clone();
    }

    public Player getShooter() { return shooter; }
    public MagicWeaponKind getKind() { return kind; }
    public Location getFrom() { return from.clone(); }
    public Location getTo() { return to.clone(); }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
