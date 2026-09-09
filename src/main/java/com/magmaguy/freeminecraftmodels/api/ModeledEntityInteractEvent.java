package com.magmaguy.freeminecraftmodels.api;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Entity permission check for a model right-click, with the real backing entity as its target.
 * Deliberately inherits PlayerInteractEntityEvent's HandlerList so ordinary entity protection
 * listeners see it. This is not another physical input: FMM's input routers must ignore it.
 * Cancellation prevents the subsequent ModeledEntityRightClickEvent and model callback.
 */
public final class ModeledEntityInteractEvent extends PlayerInteractEntityEvent {
    private final ModeledEntity modeledEntity;

    public ModeledEntityInteractEvent(Player player, ModeledEntity modeledEntity) {
        super(player, java.util.Objects.requireNonNull(modeledEntity.getUnderlyingEntity()), EquipmentSlot.HAND);
        this.modeledEntity = modeledEntity;
    }

    public ModeledEntity getModeledEntity() {
        return modeledEntity;
    }
}
