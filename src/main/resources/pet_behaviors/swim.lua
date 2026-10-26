-- Settled in water: swims to a spot near its anchor (the owner, or home when wild), between min_depth and max_depth
-- under the surface, rests a while, and goes again. It only picks spots in water. A swimmer stranded on land cannot
-- path anywhere, so it waits there; an owned one is back in water once its owner moves off and follow_owner's
-- catch-up teleport finds water beside them.
return ai.module {
    id = 'fmm:pet/swim', revision = 1,
    -- speed is in blocks per tick at species speed 1; the depths are in blocks (0 to 16). A swim gives up after
    -- swim_ticks, or after stuck_ticks without moving; a rest lasts rest_ticks plus up to rest_random_ticks more.
    settings = { speed = 0.1, min_depth = 0.5, max_depth = 3, swim_ticks = 120, stuck_ticks = 20, rest_ticks = 40,
                 rest_random_ticks = 60 },
    memories = { swim_to = 'position', swim_next = 'integer', swim_until = 'integer', swim_last = 'position',
                 swim_stuck = 'integer' },
    behaviors = {
        ai.behavior {
            id = 'fmm:pet/swim', priority = 20,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                if c.pet.staying or c.tick < (c.memory:get('swim_next') or 0) then return false end
                return c.pet.anchor ~= nil
            end,
            can_continue = function(c)
                local to = c.memory:get('swim_to')
                if to == nil or c.tick >= (c.memory:get('swim_until') or 0) or c.pet.staying then return false end
                if c.pet:distance_to(to) <= 1 then return false end
                -- Not moving: no path reaches the spot, or the path ended short of it. Rest, then try another.
                return (c.memory:get('swim_stuck') or 0) < c.settings.stuck_ticks
            end,
            start = function(c)
                local s = c.settings
                local anchor = c.pet.anchor
                if anchor == nil then return end
                -- nil with no water near the anchor's height, or in an unloaded chunk: this swim ends at once, and its
                -- rest decides when the next one looks.
                local to = c.pet:water_point(anchor, c.pet.species.roam_radius, s.min_depth, s.max_depth)
                if to == nil then return end
                c.memory:set('swim_to', to)
                c.memory:set('swim_until', c.tick + s.swim_ticks)
                c.memory:set('swim_last', c.pet.position)
                c.memory:set('swim_stuck', 0)
            end,
            tick = function(c)
                local to = c.memory:get('swim_to')
                if to == nil then return end
                local here, last = c.pet.position, c.memory:get('swim_last')
                local stuck = 0
                if last ~= nil then
                    local dx, dy, dz = here.x - last.x, here.y - last.y, here.z - last.z
                    if dx * dx + dy * dy + dz * dz < 1e-4 then stuck = (c.memory:get('swim_stuck') or 0) + 1 end
                end
                c.memory:set('swim_stuck', stuck)
                c.memory:set('swim_last', here)
                c.pet:steer_to(to, c.settings.speed * c.pet.species.speed)
            end,
            stop = function(c)
                c.memory:forget('swim_to')
                c.pet:stop()
                local rest = c.settings.rest_ticks + math.floor(math.random() * math.max(0, c.settings.rest_random_ticks))
                c.memory:set('swim_next', c.tick + rest)
            end
        }
    }
}
