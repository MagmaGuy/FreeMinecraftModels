-- Drifts to a random point around its anchor (the owner, or home when wild), then rests a while.
return ai.module {
    id = 'fmm:pet/drift', revision = 1,
    -- speed is in blocks per tick at species speed 1. A rest lasts rest_ticks plus a random 0 to rest_random_ticks - 1
    -- more; a drift gives up after drift_ticks, or after stuck_ticks without moving.
    settings = { speed = 0.06, drift_ticks = 160, rest_ticks = 60, rest_random_ticks = 80, stuck_ticks = 20 },
    memories = { drift_to = 'position', drift_next = 'integer', drift_until = 'integer', drift_last = 'position',
                 drift_stuck = 'integer' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 10 ticks: it rests 3 to 7 seconds between drifts anyway.
            id = 'fmm:pet/drift', priority = 20, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                if c.pet.staying or c.tick < (c.memory:get('drift_next') or 0) then return false end
                return c.pet.anchor ~= nil
            end,
            can_continue = function(c)
                local to = c.memory:get('drift_to')
                if to == nil or c.tick >= (c.memory:get('drift_until') or 0) or c.pet.staying then return false end
                if c.pet:distance_to(to) < 0.5 then return false end
                -- Blocked by something: give up this drift rather than push against it.
                return (c.memory:get('drift_stuck') or 0) < c.settings.stuck_ticks
            end,
            start = function(c)
                local anchor = c.pet.anchor
                if anchor == nil then return end
                -- nil in an unloaded chunk: this drift ends at once, and its rest decides when the next one tries.
                local to = c.pet:hover_point(anchor, c.pet.species.roam_radius)
                if to == nil then return end
                c.memory:set('drift_to', to)
                c.memory:set('drift_until', c.tick + c.settings.drift_ticks)
                c.memory:set('drift_last', c.pet.position)
                c.memory:set('drift_stuck', 0)
            end,
            tick = function(c)
                local to = c.memory:get('drift_to')
                if to == nil then return end
                local here, last = c.pet.position, c.memory:get('drift_last')
                local stuck = 0
                if last ~= nil then
                    local dx, dy, dz = here.x - last.x, here.y - last.y, here.z - last.z
                    if dx * dx + dy * dy + dz * dz < 1e-4 then stuck = (c.memory:get('drift_stuck') or 0) + 1 end
                end
                c.memory:set('drift_stuck', stuck)
                c.memory:set('drift_last', here)
                c.pet:steer_to(to, c.settings.speed * c.pet.species.speed)
            end,
            stop = function(c)
                c.memory:forget('drift_to')
                local rest = c.settings.rest_ticks + math.floor(math.random() * math.max(0, c.settings.rest_random_ticks))
                c.memory:set('drift_next', c.tick + rest)
            end
        }
    }
}
