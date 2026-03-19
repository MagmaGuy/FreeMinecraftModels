package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.magmacore.scripting.ScriptHook;
import com.magmaguy.magmacore.scripting.ScriptProvider;

import java.nio.file.Path;

public class PropScriptProvider implements ScriptProvider {
    private final Path scriptDirectory;

    public PropScriptProvider(Path scriptDirectory) {
        this.scriptDirectory = scriptDirectory;
    }

    @Override
    public String getNamespace() { return "fmm"; }

    @Override
    public Path getScriptDirectory() { return scriptDirectory; }

    @Override
    public ScriptHook resolveHook(String key) {
        return switch (key) {
            case "on_spawn" -> ScriptHook.ON_SPAWN;
            case "on_game_tick" -> ScriptHook.ON_TICK;
            case "on_zone_enter" -> ScriptHook.ON_ZONE_ENTER;
            case "on_zone_leave" -> ScriptHook.ON_ZONE_LEAVE;
            case "on_destroy" -> ScriptableProp.ON_DESTROY;
            case "on_left_click" -> ScriptableProp.ON_LEFT_CLICK;
            case "on_right_click" -> ScriptableProp.ON_RIGHT_CLICK;
            case "on_projectile_hit" -> ScriptableProp.ON_PROJECTILE_HIT;
            default -> null;
        };
    }
}
