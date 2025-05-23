package com.magmaguy.freeminecraftmodels.api;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class DynamicEntityHitboxContactEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();

    @Getter
    private final DynamicEntity entity;
    @Getter
    private final Player player;
    private boolean cancelled = false;

    public DynamicEntityHitboxContactEvent(Player player, DynamicEntity entity) {
        this.entity = entity;
        this.player = player;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}