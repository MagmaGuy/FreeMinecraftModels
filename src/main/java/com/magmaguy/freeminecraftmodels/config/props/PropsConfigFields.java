package com.magmaguy.freeminecraftmodels.config.props;

import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.magmacore.config.CustomConfigFields;
import lombok.Getter;
import org.bukkit.Location;

import java.util.Objects;

public class PropsConfigFields extends CustomConfigFields {

    @Getter
    private boolean onlyRemovableByOPs = true;

    /**
     * Used by plugin-generated files (defaults)
     *
     * @param filename
     * @param isEnabled
     */
    public PropsConfigFields(String filename, boolean isEnabled) {
        super(filename, isEnabled);
    }

    @Override
    public void processConfigFields() {
        onlyRemovableByOPs = processBoolean("onlyRemovableByOPs", onlyRemovableByOPs, onlyRemovableByOPs, false);
    }

    public void permanentlyAddLocation(Location location) {
        spawnPropEntity(location);
    }

    public void spawnPropEntity(Location location) {
        // Pass 'this' as the configuration to the PropEntity
        PropEntity.spawnPropEntity(filename.replace(".yml", ""), Objects.requireNonNull(location), this);
    }
}