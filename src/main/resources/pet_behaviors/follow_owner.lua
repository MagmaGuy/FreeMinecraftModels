-- Keeps up with the owner once they get past the follow distance, faster past the leash. Teleports beside them when
-- they get too far away, or after stuck_ticks without getting anywhere: no path toward them, a path that ends short of
-- them at a fence or a shore, or a wall in a flying body's way. A catch-up only ever lands nearer the owner.
return ai.module {
    id = 'fmm:pet/follow_owner', revision = 1,
    -- speed is in blocks per tick at species speed 1 for a flying body; for a walking or swimming one, 4 x speed x the
    -- species speed multiplies its body type's own movement speed, never under a quarter of it. At speed 0 the pet
    -- follows only by its catch-up teleports. leash_urgency multiplies it past the leash distance; trail_back is how
    -- many blocks behind the owner the pet aims.
    settings = { speed = 0.25, leash_urgency = 1.6, trail_back = 1.5, stuck_ticks = 60 },
    memories = { follow_owner_stuck = 'integer', follow_owner_last = 'position' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 4 ticks: it sets off within 0.2 s of the owner passing the follow distance.
            id = 'fmm:pet/follow_owner', priority = 10, probe_interval = 4,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                if c.pet.staying or c.pet.owner_location == nil then return false end
                return c.pet.owner_distance > c.pet.species.follow_distance
            end,
            can_continue = function(c)
                if c.pet.staying or c.pet.owner_location == nil then return false end
                local species = c.pet.species
                if c.pet.owner_distance <= species.follow_distance * 0.6 then return false end
                -- At its trail point it is done, even when that point lies farther from the owner than the line
                -- above, as for a pet hovering high over them: otherwise it would hold still there, catching up.
                local target = c.pet:trail_point(c.settings.trail_back, species.hover_height)
                return target == nil or c.pet:distance_to(target) > 0.5
            end,
            start = function(c)
                c.memory:set('follow_owner_stuck', 0)
                c.memory:forget('follow_owner_last')
            end,
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
                -- A tick counts as stuck when the steering is refused or the pet has not moved since the last one:
                -- native navigation accepts a path that ends short of the owner, and keeps accepting it.
                local here, last = c.pet.position, c.memory:get('follow_owner_last')
                c.memory:set('follow_owner_last', here)
                local moved = true
                if last ~= nil then
                    local dx, dy, dz = here.x - last.x, here.y - last.y, here.z - last.z
                    moved = dx * dx + dy * dy + dz * dz >= 1e-4
                end
                local stuck = 0
                if not c.pet:steer_to(target, c.settings.speed * urgency * species.speed) or not moved then
                    stuck = (c.memory:get('follow_owner_stuck') or 0) + 1
                end
                if stuck >= c.settings.stuck_ticks then
                    -- Refused when no spot beside the owner is nearer them than the pet already is; it tries again
                    -- after another stuck_ticks.
                    c.pet:teleport_near_owner(2)
                    stuck = 0
                end
                c.memory:set('follow_owner_stuck', stuck)
            end
        }
    }
}
