package com.magmaguy.freeminecraftmodels.config.props;

import com.magmaguy.freeminecraftmodels.config.CustomConfigFields;
import com.magmaguy.freeminecraftmodels.utils.ConfigurationLocation;
import lombok.Getter;
import org.bukkit.Location;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PropsConfigFields extends CustomConfigFields {

    private List<String> deserializedPropLocations;
    @Getter
    private List<Location> propLocations;

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
        this.deserializedPropLocations = processStringList("propLocations", deserializedPropLocations, new ArrayList<>(), false);
        deserializedPropLocations.forEach(deserializedPropLocation -> addLocation(ConfigurationLocation.serialize(deserializedPropLocation))
        );
    }

    public void addLocation(Location location) {
        this.propLocations.add(location);
        this.deserializedPropLocations.add(ConfigurationLocation.deserialize(location));
        fileConfiguration.set("propLocations", deserializedPropLocations);
        try {
            fileConfiguration.save(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
