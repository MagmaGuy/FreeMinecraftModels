package com.magmaguy.freeminecraftmodels.config.props.premade;

import com.magmaguy.freeminecraftmodels.config.props.PropScriptLuaConfigFields;

import java.util.List;

public class StorageSinglePropConfig extends PropScriptLuaConfigFields {
    public StorageSinglePropConfig() {
        super("storage_single");
    }

    @Override
    public String getSource() {
        return StorageDoublePropConfig.STORAGE_SCRIPT_TEMPLATE.formatted(
                "&8Storage", 3,
                "\"open\"", "\"close\"",
                "\"BLOCK_CHEST_OPEN\"", "\"BLOCK_CHEST_CLOSE\"");
    }

    @Override
    public List<String> getLegacySources() {
        return List.of(StorageDoublePropConfig.LEGACY_STORAGE_SCRIPT_TEMPLATE.formatted(
                "&8Storage", 3,
                "\"open\"", "\"close\"",
                "\"BLOCK_CHEST_OPEN\"", "\"BLOCK_CHEST_CLOSE\""));
    }
}
