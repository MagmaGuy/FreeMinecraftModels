package com.magmaguy.freeminecraftmodels.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class ResourcePackGenerationEvent extends Event {
    private static final HandlerList handlers = new HandlerList();

    public ResourcePackGenerationEvent() {
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
}
