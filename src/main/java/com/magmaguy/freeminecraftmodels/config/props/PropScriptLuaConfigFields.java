package com.magmaguy.freeminecraftmodels.config.props;

import lombok.Getter;

public abstract class PropScriptLuaConfigFields {
    @Getter private final String filename;

    protected PropScriptLuaConfigFields(String baseFileName) {
        this.filename = baseFileName + ".lua";
    }

    public abstract String getSource();
}
