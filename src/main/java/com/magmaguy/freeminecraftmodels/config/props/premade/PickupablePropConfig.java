package com.magmaguy.freeminecraftmodels.config.props.premade;

import com.magmaguy.freeminecraftmodels.config.props.PropScriptLuaConfigFields;

public class PickupablePropConfig extends PropScriptLuaConfigFields {
    public PickupablePropConfig() {
        super("pickupable");
    }

    @Override
    public String getSource() {
        return """
                return {
                    api_version = 1,
                    on_spawn = function(context)
                        context.state.hits = 0
                        context.state.reset_task = nil
                    end,
                    on_left_click = function(context)
                        if context.event then
                            context.event.cancel()
                        end
                        local state = context.state
                        state.hits = (state.hits or 0) + 1
                        if state.hits >= 3 then
                            state.hits = 0
                            context.prop:pickup()
                            return
                        end
                        -- reset hit counter after 100 ticks (5 seconds)
                        if state.reset_task then
                            state.reset_task.cancel()
                        end
                        state.reset_task = context.scheduler.run_later(100, function()
                            state.hits = 0
                            state.reset_task = nil
                        end)
                    end
                }
                """;
    }
}
