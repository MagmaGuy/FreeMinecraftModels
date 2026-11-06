-- At night or in falling snow, pets of one kind gather into a tight huddle against the cold, as emperor penguins do.
-- Once at least min_group of them, itself included, are within radius blocks, each takes a spot on a small ring round
-- the group's middle, ring blocks out, and shuffles a little further round it every few seconds, so the huddle slowly
-- turns. Wild pets huddle with wild ones, owned pets with their owner's other pets of the kind.

-- seconds in ticks: at least a second, and finite however large the setting.
local function seconds_ticks(seconds)
    return math.floor(math.min(math.max(20, seconds * 20), 2147483647))
end

-- Whether the night or the snow calls for a huddle.
local function cold(c)
    local s = c.settings
    return (s.night and c.pet.night) or (s.snow and c.pet.snowing)
end

-- Its spot on the ring round the group's middle at this angle.
local function ring_point(c, center, angle)
    local ring = math.max(0, c.settings.ring)
    return { world = center.world, x = center.x + math.cos(angle) * ring, y = center.y,
             z = center.z + math.sin(angle) * ring }
end

return ai.module {
    id = 'fmm:pet/huddle', revision = 1,
    -- radius is how far off the others may be, in blocks (up to 32), and min_group how many must gather, itself
    -- included. night and snow choose what sets it off. It looks for the others every search_seconds, and sees where
    -- the group's middle has moved every 2 seconds. speed sets its walk to the huddle and each shuffle step as in
    -- wander: 4 x speed x the species speed multiplies the body type's movement speed, never under a quarter of it. A
    -- slower walk takes a minute to cross a few blocks. wild and pets switch it on for wild and
    -- for owned pets.
    settings = { radius = 8, min_group = 3, ring = 1.0, night = true, snow = true, wild = true, pets = true,
                 search_seconds = 5, speed = 0.15 },
    memories = { huddle_center = 'position', huddle_angle = 'number', huddle_next_search = 'integer',
                 huddle_next_check = 'integer', huddle_next_step = 'integer' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 10 ticks: it looks for the others only every search_seconds anyway.
            id = 'fmm:pet/huddle', priority = 17, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                local s = c.settings
                local allowed = s.pets
                if c.pet.wild then allowed = s.wild end
                if not allowed or not c.pet.settled or c.tick < (c.memory:get('huddle_next_search') or 0) then
                    return false
                end
                if not cold(c) then return false end
                c.memory:set('huddle_next_search', c.tick + seconds_ticks(s.search_seconds))
                local center = c.pet:kin_center(s.radius, s.min_group)
                if center == nil then return false end
                c.memory:set('huddle_center', center)
                return true
            end,
            can_continue = function(c)
                if c.memory:get('huddle_center') == nil or not c.pet.settled or not cold(c) then return false end
                if c.tick < (c.memory:get('huddle_next_check') or 0) then return true end
                -- Every 2 seconds: where the group's middle is now, and are enough of them still there?
                c.memory:set('huddle_next_check', c.tick + 40)
                local center = c.pet:kin_center(c.settings.radius, c.settings.min_group)
                if center == nil then return false end
                c.memory:set('huddle_center', center)
                return true
            end,
            start = function(c)
                c.memory:set('huddle_angle', math.random() * math.pi * 2)
                c.memory:set('huddle_next_check', c.tick + 40)
                c.memory:set('huddle_next_step', c.tick + 60 + math.random(0, 59))
            end,
            tick = function(c)
                local center = c.memory:get('huddle_center')
                if center == nil then return end
                local angle = c.memory:get('huddle_angle') or 0
                if c.tick >= (c.memory:get('huddle_next_step') or 0) then
                    -- A shuffle a little further round, every 3 to 6 seconds.
                    angle = angle + 0.2 + math.random() * 0.3
                    c.memory:set('huddle_angle', angle)
                    c.memory:set('huddle_next_step', c.tick + 60 + math.random(0, 59))
                end
                -- Onto its spot: the shuffles are a fraction of a block, and a path stops about a block short.
                c.pet:steer_onto(ring_point(c, center, angle), c.settings.speed * c.pet.species.speed)
            end,
            stop = function(c)
                c.memory:forget('huddle_center')
                c.pet:stop()
            end
        }
    }
}
