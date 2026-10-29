-- While rain falls on it, the pet swells to scale_multiplier times its size over scale_seconds, and drifts round its
-- anchor (the owner, or home when wild) drift_frequency times as often as the plain drift, rise blocks higher and
-- speed_multiplier times as fast. It shrinks back when the rain stops or it finds cover.

-- How often the swelling takes a step, in ticks: 15 steps over the default 3 seconds.
local SWELL_TICKS = 4

return ai.module {
    id = 'fmm:pet/rain_bloom', revision = 1,
    -- speed is in blocks per tick at species speed 1, before speed_multiplier. A bloom drift gives up after
    -- drift_ticks, or after stuck_ticks without moving. The pause after it is a plain drift's rest (rest_ticks plus a
    -- random 0 to rest_random_ticks - 1 more) made drift_frequency times shorter, but never under 10 ticks.
    settings = { speed_multiplier = 1.4, scale_multiplier = 1.25, drift_frequency = 2.5, rise = 1, scale_seconds = 3,
                 speed = 0.06, drift_ticks = 120, stuck_ticks = 20, rest_ticks = 60, rest_random_ticks = 80 },
    memories = { rain_bloom_to = 'position', rain_bloom_next = 'integer', rain_bloom_until = 'integer',
                 rain_bloom_last = 'position', rain_bloom_stuck = 'integer' },
    sensors = {
        ai.sensor {
            id = 'fmm:pet/rain_bloom_swell', interval = SWELL_TICKS,
            sense = function(c)
                local s = c.settings
                local target = 1
                if c.pet.raining then target = s.scale_multiplier end
                -- scale_seconds in ticks: at least 2, and finite however large the setting.
                local ticks = math.max(2, math.min(s.scale_seconds * 20, 2147483647))
                c.pet:scale_toward(target, s.scale_multiplier, ticks, SWELL_TICKS)
            end
        }
    },
    behaviors = {
        ai.behavior {
            -- Asked to start every 10 ticks: it pauses at least 10 ticks between bloom drifts anyway.
            id = 'fmm:pet/rain_bloom', priority = 19, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                if not c.pet.raining or c.pet.staying then return false end
                if c.tick < (c.memory:get('rain_bloom_next') or 0) then return false end
                return c.pet.anchor ~= nil
            end,
            can_continue = function(c)
                local to = c.memory:get('rain_bloom_to')
                if to == nil or c.tick >= (c.memory:get('rain_bloom_until') or 0) or c.pet.staying then return false end
                if c.pet:distance_to(to) < 0.5 then return false end
                -- Blocked by something: give up this drift rather than push against it.
                return (c.memory:get('rain_bloom_stuck') or 0) < c.settings.stuck_ticks
            end,
            start = function(c)
                local anchor = c.pet.anchor
                if anchor == nil then return end
                -- nil in an unloaded chunk: this drift ends at once and the next waits its pause.
                local to = c.pet:hover_point(anchor, c.pet.roam_range)
                if to == nil then return end
                to.y = to.y + c.settings.rise
                c.memory:set('rain_bloom_to', to)
                c.memory:set('rain_bloom_until', c.tick + c.settings.drift_ticks)
                c.memory:set('rain_bloom_last', c.pet.position)
                c.memory:set('rain_bloom_stuck', 0)
            end,
            tick = function(c)
                local to = c.memory:get('rain_bloom_to')
                if to == nil then return end
                local here, last = c.pet.position, c.memory:get('rain_bloom_last')
                local stuck = 0
                if last ~= nil then
                    local dx, dy, dz = here.x - last.x, here.y - last.y, here.z - last.z
                    if dx * dx + dy * dy + dz * dz < 1e-4 then stuck = (c.memory:get('rain_bloom_stuck') or 0) + 1 end
                end
                c.memory:set('rain_bloom_stuck', stuck)
                c.memory:set('rain_bloom_last', here)
                local multiplier = math.max(0.1, c.settings.speed_multiplier)
                c.pet:steer_to(to, c.settings.speed * c.pet.species.speed * multiplier)
            end,
            stop = function(c)
                c.memory:forget('rain_bloom_to')
                local s = c.settings
                local rest = s.rest_ticks + math.floor(math.random() * math.max(0, s.rest_random_ticks))
                local pause = rest / math.max(0.1, s.drift_frequency)
                c.memory:set('rain_bloom_next', c.tick + math.max(10, math.floor(pause)))
            end
        }
    }
}
