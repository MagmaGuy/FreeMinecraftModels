-- Resting, it now and then looks at the nearest player within range for a couple of seconds. It takes only the look,
-- so a moving behavior always takes over, and it never runs while one holds the look. Players in creative or
-- spectator mode are never looked at.

-- Once a rest is over, how often it looks for someone, in ticks, so a pet with nobody near, or whose look a moving
-- behavior holds, does not search every tick.
local SEARCH_TICKS = 20
-- While it looks, how often it finds the player again, in ticks. It turns toward where it last saw their eyes every
-- tick, since the body's look lets go of a point after 2 ticks.
local REFRESH_TICKS = 5

-- The nearest player within range, or nil; a range of 0 or less turns the look off.
local function nearest_player(c)
    local range = c.settings.range
    if range <= 0 then return nil end
    return c.perception:nearest_player(range)
end

-- Finds the nearest player and remembers where their eyes are until the next refresh; false when nobody is in range.
local function see(c)
    c.memory:set('idle_look_refresh', c.tick + REFRESH_TICKS)
    local player = nearest_player(c)
    if player == nil then
        c.memory:forget('idle_look_at')
        return false
    end
    c.memory:set('idle_look_at', player:get_eye_location())
    return true
end

return ai.module {
    id = 'fmm:pet/idle_look', revision = 1,
    -- range is in blocks. A look lasts look_ticks, or until the player leaves the range; the rest after it lasts
    -- rest_ticks plus a random 0 to rest_random_ticks - 1 more.
    settings = { range = 8, look_ticks = 40, rest_ticks = 100, rest_random_ticks = 100 },
    memories = { idle_look_next = 'integer', idle_look_until = 'integer', idle_look_at = 'position',
                 idle_look_refresh = 'integer' },
    behaviors = {
        ai.behavior {
            id = 'fmm:pet/idle_look', priority = 30,
            controls = { ai.controls.look },
            can_start = function(c)
                if c.tick < (c.memory:get('idle_look_next') or 0) then return false end
                c.memory:set('idle_look_next', c.tick + SEARCH_TICKS)
                return see(c)
            end,
            can_continue = function(c)
                return c.memory:contains('idle_look_at') and c.tick < (c.memory:get('idle_look_until') or 0)
            end,
            start = function(c) c.memory:set('idle_look_until', c.tick + c.settings.look_ticks) end,
            tick = function(c)
                -- A player who left the range ends the look at the next check.
                if c.tick >= (c.memory:get('idle_look_refresh') or 0) and not see(c) then return end
                local at = c.memory:get('idle_look_at')
                if at ~= nil then c.actuator:look_at(at) end
            end,
            stop = function(c)
                c.memory:forget('idle_look_at')
                local rest = c.settings.rest_ticks + math.floor(math.random() * math.max(0, c.settings.rest_random_ticks))
                c.memory:set('idle_look_next', c.tick + rest)
            end
        }
    }
}
