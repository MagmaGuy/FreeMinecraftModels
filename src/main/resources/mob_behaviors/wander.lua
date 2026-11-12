-- Strolls about when nothing else moves the mob. A mob whose AI is overridden has lost its own strolling with the
-- rest, so list this after the behaviors that matter: mob:override_ai({ 'coward', 'wander' }). It comes last, and
-- every other shipped behavior takes the mob from it at once.

return ai.module {
    id = 'fmm:mob/wander', revision = 1,
    -- It picks a spot up to radius blocks away, walks there at speed times its own movement speed, then rests for
    -- rest_ticks plus a random 0 to rest_random_ticks - 1 more. A stroll it cannot finish ends after walk_ticks.
    settings = { radius = 8, speed = 1.0, rest_ticks = 60, rest_random_ticks = 120, walk_ticks = 200 },
    memories = { wander_next = 'integer', wander_until = 'integer' },
    behaviors = {
        ai.behavior {
            id = 'fmm:mob/wander', priority = 40, probe_interval = 10, tick_interval = 10,
            controls = { ai.controls.move },
            can_start = function(c) return c.tick >= (c.memory:get('wander_next') or 0) end,
            can_continue = function(c)
                return c.tick < (c.memory:get('wander_until') or 0)
                    and c.perception:navigation().navigation_in_progress
            end,
            start = function(c)
                local to = c.entity.current_location
                local angle = math.random() * 2 * math.pi
                local length = c.settings.radius * (0.4 + 0.6 * math.random())
                to.x = to.x + math.cos(angle) * length
                to.z = to.z + math.sin(angle) * length
                c.memory:set('wander_until', c.tick + c.settings.walk_ticks)
                -- A spot it finds no path to ends the stroll at the next check, and it tries another after the rest.
                c.actuator:move_to(to, c.settings.speed)
            end,
            tick = function(c) end,
            stop = function(c)
                local rest = c.settings.rest_ticks
                    + math.floor(math.random() * math.max(0, c.settings.rest_random_ticks))
                c.memory:set('wander_next', c.tick + rest)
                c.actuator:stop_moving()
            end
        }
    }
}
