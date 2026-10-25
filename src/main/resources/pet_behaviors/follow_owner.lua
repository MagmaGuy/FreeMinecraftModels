-- Keeps up with the owner once they get past the follow distance, faster past the leash. Teleports beside them when
-- they get too far away, or when a walking or swimming body finds no path toward them for stuck_ticks.
return ai.module {
    id = 'fmm:pet/follow_owner', revision = 1,
    -- speed is in blocks per tick at species speed 1, and leash_urgency multiplies it past the leash distance;
    -- trail_back is how many blocks behind the owner the pet aims.
    settings = { speed = 0.25, leash_urgency = 1.6, trail_back = 1.5, stuck_ticks = 60 },
    memories = { follow_owner_stuck = 'integer' },
    behaviors = {
        ai.behavior {
            id = 'fmm:pet/follow_owner', priority = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                if c.pet.staying or c.pet.owner_location == nil then return false end
                return c.pet.owner_distance > c.pet.species.follow_distance
            end,
            can_continue = function(c)
                if c.pet.staying or c.pet.owner_location == nil then return false end
                return c.pet.owner_distance > c.pet.species.follow_distance * 0.6
            end,
            start = function(c) c.memory:set('follow_owner_stuck', 0) end,
            tick = function(c)
                local species = c.pet.species
                local distance = c.pet.owner_distance
                if distance > species.teleport_distance then
                    c.pet:teleport_near_owner(2)
                    return
                end
                local target = c.pet:trail_point(c.settings.trail_back, species.hover_height)
                if target == nil then return end
                local urgency = 1
                if distance > species.leash_distance then urgency = c.settings.leash_urgency end
                -- A flying body steers by velocity, which only fails while something else holds its movement, so
                -- in practice only walking and swimming bodies count stuck ticks.
                local stuck = c.memory:get('follow_owner_stuck') or 0
                if c.pet:steer_to(target, c.settings.speed * urgency * species.speed) then
                    if stuck ~= 0 then c.memory:set('follow_owner_stuck', 0) end
                    return
                end
                stuck = stuck + 1
                if stuck >= c.settings.stuck_ticks then
                    c.pet:teleport_near_owner(2)
                    stuck = 0
                end
                c.memory:set('follow_owner_stuck', stuck)
            end
        }
    }
}
