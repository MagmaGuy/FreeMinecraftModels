package com.magmaguy.freeminecraftmodels.config.props.premade;

import com.magmaguy.freeminecraftmodels.config.props.PropScriptLuaConfigFields;

public class InvulnerablePropConfig extends PropScriptLuaConfigFields {
    public InvulnerablePropConfig() {
        super("invulnerable");
    }

    @Override
    public String getSource() {
        return """
                return {
                    api_version = 1,
                    on_left_click = function(context)
                        if context.event then
                            context.event.cancel()
                        end
                    end
                }
                """;
    }
}
