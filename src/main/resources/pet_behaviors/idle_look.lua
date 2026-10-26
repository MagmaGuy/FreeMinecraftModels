-- Resting, it now and then looks at the nearest player within range for a couple of seconds. It takes only the look,
-- so a moving behavior always takes over, and it never runs while one holds the look. Players in creative or
-- spectator mode are never looked at.

-- Once a rest is over, how often it looks for someone, in ticks, so a pet with nobody near, or whose look a moving
-- behavior holds, does not search every tick.
local SEARCH_TICKS = 20

-- The nearest player within range, or nil; a range of 0 or less turns the look off.
local function nearest_player(c)
    local range = c.settings.range
    if range <= 0 then return nil end
    return c.perception:nearest_player(range)
end

return ai.module {
    id = 'fmm:pet/idle_look', revision = 1,
    -- range is in blocks. A look lasts look_ticks, or until the player leaves the range; the rest after it lasts
    -- rest_ticks plus up to rest_random_ticks more.
    settings = { range = 8, look_ticks = 40, rest_ticks = 100, rest_random_ticks = 100 },
    memories = { idle_look_next = 'integer', idle_look_until = 'integer' },
    behaviors = {
        ai.behavior {
            id = 'fmm:pet/idle_look', priority = 30,
            controls = { ai.controls.look },
            can_start = function(c)
                if c.tick < (c.memory:get('idle_look_next') or 0) then return false end
                c.memory:set('idle_look_next', c.tick + SEARCH_TICKS)
                return nearest_player(c) ~= nil
            end,
            can_continue = function(c) return c.tick < (c.memory:get('idle_look_until') or 0) end,
            start = function(c) c.memory:set('idle_look_until', c.tick + c.settings.look_ticks) end,
            tick = function(c)
                local player = nearest_player(c)
                if player == nil then
                    -- They left: the look ends at the next check.
                    c.memory:set('idle_look_until', c.tick)
                    return
                end
                c.actuator:look_at(player:get_eye_location())
            end,
            stop = function(c)
                local rest = c.settings.rest_ticks + math.floor(math.random() * math.max(0, c.settings.rest_random_ticks))
                c.memory:set('idle_look_next', c.tick + rest)
            end
        }
    }
}
