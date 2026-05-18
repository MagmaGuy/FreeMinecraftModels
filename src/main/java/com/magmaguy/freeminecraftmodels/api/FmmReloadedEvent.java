package com.magmaguy.freeminecraftmodels.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fires after FMM finishes its initialization sequence — both on initial server
 * startup and after every {@code /freeminecraftmodels reload}. Consumer plugins
 * (e.g. EliteMobs, BetterStructures) that hold long-lived references to
 * {@link com.magmaguy.freeminecraftmodels.customentity.DynamicEntity DynamicEntity}
 * or {@link com.magmaguy.freeminecraftmodels.customentity.PropEntity PropEntity}
 * instances need to handle this event by re-creating their custom-model
 * attachments on the surviving underlying entities, otherwise those entities
 * become invisible after a reload — the FMM-side display entities were torn
 * down during {@code onDisable} but the consumer's reference is now stale.
 * <p>
 * Fires on the main server thread.
 */
public class FmmReloadedEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    public FmmReloadedEvent() {
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
}
