package com.magmaguy.freeminecraftmodels.config.props;

import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;

public final class PropBlocks {
    @Getter
    private final Material material;
    private Vector vector = null;
    private Location location = null;

    public PropBlocks(Vector vector, Material material) {
        this.vector = vector;
        this.material = material;
    }

    public PropBlocks(Location location, Material material) {
        this.location = location;
        this.material = material;
    }

    public Location vectorToLocation(Location entityLocation) {
        return entityLocation.clone().add(vector);
    }

    public Location getProcessedLocation(Location entityLocation) {
        if (location != null) return location;
        return vectorToLocation(entityLocation);
    }
}