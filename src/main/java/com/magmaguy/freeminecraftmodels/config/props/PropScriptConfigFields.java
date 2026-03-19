package com.magmaguy.freeminecraftmodels.config.props;

import com.magmaguy.magmacore.config.CustomConfigFields;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration fields for a prop script config file.
 * <p>
 * Each model file (e.g. {@code torch_01.fmmodel}) can have a sibling YML file
 * (e.g. {@code torch_01.yml}) that specifies which Lua scripts to attach when
 * the prop spawns. Script filenames resolve from the central {@code scripts/} folder.
 */
public class PropScriptConfigFields extends CustomConfigFields {

    @Getter
    private List<String> scripts = new ArrayList<>();

    /**
     * Used when creating a new default config or loading an existing one.
     *
     * @param filename  the YML filename (e.g. {@code torch_01.yml})
     * @param isEnabled whether this config is enabled
     */
    public PropScriptConfigFields(String filename, boolean isEnabled) {
        super(filename, isEnabled);
    }

    @Override
    public void processConfigFields() {
        this.isEnabled = processBoolean("isEnabled", isEnabled, true, true);
        this.scripts = processStringList("scripts", scripts, new ArrayList<>(), true);
    }
}
