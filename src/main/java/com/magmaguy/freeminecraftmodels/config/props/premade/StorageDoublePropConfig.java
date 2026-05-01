package com.magmaguy.freeminecraftmodels.config.props.premade;

import com.magmaguy.freeminecraftmodels.config.props.PropScriptLuaConfigFields;

public class StorageDoublePropConfig extends PropScriptLuaConfigFields {
    public StorageDoublePropConfig() {
        super("storage_double");
    }

    @Override
    public String getSource() {
        return STORAGE_SCRIPT_TEMPLATE.formatted(
                "&8Storage", 6,
                "\"open\"", "\"close\"",
                "\"BLOCK_CHEST_OPEN\"", "\"BLOCK_CHEST_CLOSE\"");
    }

    static final String STORAGE_SCRIPT_TEMPLATE = """
            -- Premade storage script for props that act as chests/drawers/closets.
            -- Configure via the variables below.
            --
            -- INVENTORY_TITLE: the name shown at the top of the chest GUI (supports color codes)
            -- INVENTORY_ROWS:  1-6 (maps to 9-54 slots, 6 = double chest)
            -- OPEN_ANIMATION:  animation to play when opened (set to nil to skip)
            -- CLOSE_ANIMATION: animation to play when all viewers leave (set to nil to skip)
            -- OPEN_SOUND:      sound to play when opened (set to nil to skip)
            -- CLOSE_SOUND:     sound to play when closed (set to nil to skip)

            local INVENTORY_TITLE  = "%s"
            local INVENTORY_ROWS   = %d
            local OPEN_ANIMATION   = %s
            local CLOSE_ANIMATION  = %s
            local OPEN_SOUND       = %s
            local CLOSE_SOUND      = %s

            return {
                api_version = 1,

                on_spawn = function(context)
                    context.state.open_count = 0
                end,

                on_right_click = function(context)
                    local player = context.event and context.event.player
                    if not player then return end

                    -- Anti-exploit: block interaction in protected zones and dungeons
                    local protect_loc = context.prop.current_location
                    if protect_loc and (em.location.is_protected(protect_loc) or em.location.is_in_dungeon(protect_loc)) then
                        if context.event then context.event:cancel() end
                        return
                    end

                    context.state.open_count = (context.state.open_count or 0) + 1

                    if OPEN_ANIMATION then
                        context.prop:play_animation(OPEN_ANIMATION, true, false)
                    end

                    local loc = context.prop.current_location
                    if OPEN_SOUND and loc then
                        context.world:play_sound(OPEN_SOUND, loc.x, loc.y, loc.z, 1.0, 1.0)
                    end

                    context.prop:open_inventory(player, INVENTORY_TITLE, INVENTORY_ROWS)

                    local player_uuid = player.uuid
                    local existing = context.state["task_" .. player_uuid]
                    if existing then
                        context.scheduler:cancel(existing)
                    end
                    context.state["task_" .. player_uuid] = context.scheduler:run_repeating(5, 5, function(tick_context)
                        local prop_loc = tick_context.prop.current_location
                        if prop_loc == nil then
                            tick_context.scheduler:cancel(tick_context.state["task_" .. player_uuid])
                            tick_context.state["task_" .. player_uuid] = nil
                            return
                        end

                        if not tick_context.prop:is_viewing_inventory(player) then
                            tick_context.state.open_count = math.max(0, (tick_context.state.open_count or 1) - 1)

                            if tick_context.state.open_count <= 0 then
                                if CLOSE_ANIMATION then
                                    tick_context.prop:play_animation(CLOSE_ANIMATION, true, false)
                                end
                                if CLOSE_SOUND then
                                    tick_context.world:play_sound(CLOSE_SOUND, prop_loc.x, prop_loc.y, prop_loc.z, 1.0, 1.0)
                                end
                            end

                            tick_context.scheduler:cancel(tick_context.state["task_" .. player_uuid])
                            tick_context.state["task_" .. player_uuid] = nil
                        end
                    end)
                end,

                on_destroy = function(context)
                    -- Drop all stored contents and clear data
                    context.prop:drop_inventory()
                end
            }
            """;
}
