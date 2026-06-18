package com.magmaguy.freeminecraftmodels.config.props;

import lombok.Getter;

import java.util.List;

public abstract class PropScriptLuaConfigFields {
    @Getter private final String filename;

    protected PropScriptLuaConfigFields(String baseFileName) {
        this.filename = baseFileName + ".lua";
    }

    public abstract String getSource();

    public List<String> getLegacySources() {
        return List.of();
    }
}
