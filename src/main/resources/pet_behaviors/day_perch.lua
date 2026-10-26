-- By day it settles on the nearest leaves under it (up to search_radius blocks to the side), or the ground right
-- below, and rests height blocks above them with the barest breathing bob. It lifts off at dusk, in the rain, or
-- when its owner walks off.
return ai.module {
    id = 'fmm:pet/day_perch', revision = 1,
    -- height is in blocks (0 to 8) and search_radius in whole blocks (0 to 4). speed is in blocks per tick at
    -- species speed 1 while more than a block from the perch, settle_speed once closer. wild and pets switch it on
    -- for wild and for owned pets.
    settings = { height = 0.3, search_radius = 2, wild = true, pets = true, speed = 0.06, settle_speed = 0.03 },
    memories = { day_perch_spot = 'position', day_perch_target = 'position', day_perch_next_try = 'integer',
                 day_perch_next_bob = 'integer' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 10 ticks: settling takes seconds, and a failed search waits 5 seconds.
            id = 'fmm:pet/day_perch', priority = 18, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                local s = c.settings
                local allowed = s.pets
                if c.pet.wild then allowed = s.wild end
                if not allowed or c.tick < (c.memory:get('day_perch_next_try') or 0) then return false end
                if c.pet.raining or not c.pet.day or not c.pet.settled then return false end
                local spot = c.pet:find_perch(s.height, s.search_radius)
                if spot == nil then
                    -- Nothing to sit on (water, a long drop): look again in a while instead of every tick.
                    c.memory:set('day_perch_next_try', c.tick + 100)
                    return false
                end
                c.memory:set('day_perch_spot', spot)
                return true
            end,
            can_continue = function(c)
                local spot = c.memory:get('day_perch_spot')
                return spot ~= nil and spot.world == c.pet.position.world and not c.pet.raining and c.pet.day
                        and c.pet.settled
            end,
            start = function(c) c.memory:set('day_perch_target', c.memory:get('day_perch_spot')) end,
            tick = function(c)
                local spot = c.memory:get('day_perch_spot')
                if spot == nil then return end
                local distance = c.pet:distance_to(spot)
                if distance < 0.2 and c.tick >= (c.memory:get('day_perch_next_bob') or 0) then
                    -- Settled: the barest breathing bob now and then.
                    c.memory:set('day_perch_next_bob', c.tick + 60)
                    local bob = { world = spot.world, x = spot.x, y = spot.y + math.random() * 0.08, z = spot.z }
                    c.memory:set('day_perch_target', bob)
                end
                local cruise = c.settings.settle_speed
                if distance > 1 then cruise = c.settings.speed end
                c.pet:steer_to(c.memory:get('day_perch_target') or spot, cruise * c.pet.species.speed)
            end,
            stop = function(c)
                c.memory:forget('day_perch_spot')
                c.memory:forget('day_perch_target')
                c.memory:set('day_perch_next_try', c.tick + 40)
            end
        }
    }
}
