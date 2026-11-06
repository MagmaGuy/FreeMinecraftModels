-- On snow or ice it belly-slides for fun now and then. It picks the best straight run from where it stands over the
-- surface blocks, downhill where it can, and slides it fast, playing animation in place of its walk.

-- Within this many blocks of the run's end the slide is over: native navigation stops about a block short.
local ARRIVED = 1.5

-- seconds in ticks: at least a second, and finite however large the setting.
local function seconds_ticks(seconds)
    return math.floor(math.min(math.max(20, seconds * 20), 2147483647))
end

return ai.module {
    id = 'fmm:pet/toboggan', revision = 1,
    -- Every interval_seconds, chance of the time (0 to 1), it looks for a run at least min_length and at most
    -- max_length blocks long (up to 24) over the surfaces (block names; a snow layer counts where the pet stands in it,
    -- anything else under its feet). speed sets the slide as in wander. A slide gives up after slide_seconds, or after
    -- stuck_ticks without moving. animation is any of the model's animations; a model without it does not slide. wild
    -- and pets switch it on for wild and for owned pets.
    settings = { min_length = 4, max_length = 12,
                 surfaces = { 'SNOW', 'SNOW_BLOCK', 'ICE', 'PACKED_ICE', 'BLUE_ICE', 'FROSTED_ICE' },
                 animation = 'slide', speed = 0.35, interval_seconds = 15, chance = 0.5, slide_seconds = 8,
                 stuck_ticks = 20, wild = true, pets = true },
    memories = { toboggan_to = 'position', toboggan_next = 'integer', toboggan_until = 'integer',
                 toboggan_last = 'position', toboggan_stuck = 'integer' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 10 ticks: it looks for a run only every interval_seconds anyway.
            id = 'fmm:pet/toboggan', priority = 19, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                local s = c.settings
                local allowed = s.pets
                if c.pet.wild then allowed = s.wild end
                if not allowed or not c.pet.settled or c.tick < (c.memory:get('toboggan_next') or 0) then
                    return false
                end
                c.memory:set('toboggan_next', c.tick + seconds_ticks(s.interval_seconds))
                if math.random() >= s.chance then return false end
                local to = c.pet:slide_point(s.min_length, s.max_length, s.surfaces)
                if to == nil then return false end
                c.memory:set('toboggan_to', to)
                return true
            end,
            can_continue = function(c)
                local to = c.memory:get('toboggan_to')
                if to == nil or c.pet.staying or c.tick >= (c.memory:get('toboggan_until') or 0) then return false end
                if c.pet:distance_to(to) <= ARRIVED then return false end
                return (c.memory:get('toboggan_stuck') or 0) < c.settings.stuck_ticks
            end,
            start = function(c)
                c.memory:set('toboggan_until', c.tick + seconds_ticks(c.settings.slide_seconds))
                c.memory:set('toboggan_last', c.pet.position)
                c.memory:set('toboggan_stuck', 0)
                -- A model without the animation, such as a baby's, does not slide.
                if not c.pet:set_walk_animation(c.settings.animation) then c.memory:forget('toboggan_to') end
            end,
            tick = function(c)
                local to = c.memory:get('toboggan_to')
                if to == nil then return end
                local here, last = c.pet.position, c.memory:get('toboggan_last')
                local stuck = 0
                if last ~= nil then
                    local dx, dy, dz = here.x - last.x, here.y - last.y, here.z - last.z
                    if dx * dx + dy * dy + dz * dz < 1e-4 then stuck = (c.memory:get('toboggan_stuck') or 0) + 1 end
                end
                c.memory:set('toboggan_stuck', stuck)
                c.memory:set('toboggan_last', here)
                c.pet:steer_to(to, c.settings.speed * c.pet.species.speed)
            end,
            stop = function(c)
                c.memory:forget('toboggan_to')
                c.pet:set_walk_animation('')
                c.pet:stop()
            end
        }
    }
}
