-- Now and then, near water, it goes fishing. It walks in and dives to a spot between min_depth and max_depth under
-- the surface, swims on to a few more spots near it, and comes back: a wild pet to where it set off, an owned one to
-- its owner. Back beside its owner, gift_chance of the time it brings them a fish, dropping catch at their feet for
-- them alone to pick up.

-- Within this many blocks of a spot it has reached it: native navigation stops about a block short of its goal.
local ARRIVED = 1.5
-- The trip's two legs: out swimming from spot to spot, then back.
local OUT, BACK = 1, 2

-- seconds in ticks: at least a second, and finite however large the setting.
local function seconds_ticks(seconds)
    return math.floor(math.min(math.max(20, seconds * 20), 2147483647))
end

-- Ends the trip; can_continue stops it.
local function done(c)
    c.memory:set('fishing_done', true)
end

return ai.module {
    id = 'fmm:pet/fishing_trip', revision = 1,
    -- Every interval_seconds, chance of the time (0 to 1), it looks for water within radius blocks of itself, or, owned,
    -- within its roam range of its owner, so follow_owner does not call it back. It swims to spots (at most 16), the
    -- first within radius and each next one within 4 blocks of the last, the depths in blocks (0 to 16). speed sets
    -- the walk and swim as in wander. The whole trip gives up after trip_seconds; a leg that gets nowhere for
    -- stuck_ticks heads back, or ends the trip on the way back. catch is any item, NONE for no gift, dropped within
    -- gift_range blocks of the owner (up to 8) with sound at volume and pitch. wild and pets switch it on for wild
    -- and for owned pets.
    settings = { radius = 8, min_depth = 1, max_depth = 3, spots = 3, interval_seconds = 60, chance = 0.5,
                 speed = 0.15, trip_seconds = 40, stuck_ticks = 40, wild = true, pets = true, gift_chance = 0.3,
                 catch = 'COD', gift_range = 3, sound = 'entity.cod.flop', volume = 1.0, pitch = 1.0 },
    memories = { fishing_phase = 'integer', fishing_to = 'position', fishing_from = 'position',
                 fishing_left = 'integer', fishing_until = 'integer', fishing_next = 'integer',
                 fishing_last = 'position', fishing_stuck = 'integer', fishing_done = 'boolean' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 10 ticks: it looks for water only every interval_seconds anyway.
            id = 'fmm:pet/fishing_trip', priority = 16, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                local s = c.settings
                local allowed = s.pets
                if c.pet.wild then allowed = s.wild end
                if not allowed or not c.pet.settled or c.tick < (c.memory:get('fishing_next') or 0) then
                    return false
                end
                c.memory:set('fishing_next', c.tick + seconds_ticks(s.interval_seconds))
                if math.random() >= s.chance then return false end
                local center, radius = c.pet.position, s.radius
                if not c.pet.wild then
                    center = c.pet.anchor
                    radius = math.min(s.radius, c.pet.roam_range)
                    if center == nil then return false end
                end
                local spot = c.pet:water_point(center, radius, s.min_depth, s.max_depth)
                if spot == nil then return false end
                c.memory:set('fishing_to', spot)
                return true
            end,
            can_continue = function(c)
                return not c.memory:get('fishing_done') and not c.pet.staying
                        and c.tick < (c.memory:get('fishing_until') or 0)
            end,
            start = function(c)
                local s = c.settings
                c.memory:forget('fishing_done')
                c.memory:set('fishing_phase', OUT)
                c.memory:set('fishing_from', c.pet.position)
                c.memory:set('fishing_left', math.max(1, math.min(16, math.floor(s.spots))))
                c.memory:set('fishing_until', c.tick + seconds_ticks(s.trip_seconds))
                c.memory:set('fishing_last', c.pet.position)
                c.memory:set('fishing_stuck', 0)
            end,
            tick = function(c)
                local s = c.settings
                local here, last = c.pet.position, c.memory:get('fishing_last')
                local stuck = 0
                if last ~= nil then
                    local dx, dy, dz = here.x - last.x, here.y - last.y, here.z - last.z
                    if dx * dx + dy * dy + dz * dz < 1e-4 then stuck = (c.memory:get('fishing_stuck') or 0) + 1 end
                end
                c.memory:set('fishing_last', here)
                local to
                if (c.memory:get('fishing_phase') or OUT) == OUT then
                    to = c.memory:get('fishing_to')
                    if to ~= nil and c.pet:distance_to(to) <= ARRIVED then
                        local left = (c.memory:get('fishing_left') or 1) - 1
                        c.memory:set('fishing_left', left)
                        if left > 0 then
                            to = c.pet:water_point(to, 4, s.min_depth, s.max_depth)
                        else
                            to = nil
                        end
                        if to ~= nil then c.memory:set('fishing_to', to) end
                    end
                    if to == nil or stuck >= s.stuck_ticks then
                        -- Caught enough, nowhere left to swim, or stuck: back it goes.
                        c.memory:set('fishing_phase', BACK)
                        stuck = 0
                    end
                end
                c.memory:set('fishing_stuck', stuck)
                if c.memory:get('fishing_phase') == BACK then
                    if stuck >= s.stuck_ticks then return done(c) end
                    if c.pet.wild then
                        to = c.memory:get('fishing_from')
                        if to == nil or c.pet:distance_to(to) <= ARRIVED then return done(c) end
                    else
                        to = c.pet.owner_location
                        if to == nil then return done(c) end
                        if c.pet.owner_distance <= s.gift_range then
                            if math.random() < s.gift_chance then
                                c.pet:gift(s.catch, 1, s.gift_range, s.sound, s.volume, s.pitch)
                            end
                            return done(c)
                        end
                    end
                end
                c.pet:steer_to(to, s.speed * c.pet.species.speed)
            end,
            stop = function(c)
                for _, name in ipairs({ 'fishing_phase', 'fishing_to', 'fishing_from', 'fishing_left', 'fishing_until',
                                        'fishing_last', 'fishing_stuck', 'fishing_done' }) do
                    c.memory:forget(name)
                end
                c.pet:stop()
            end
        }
    }
}
