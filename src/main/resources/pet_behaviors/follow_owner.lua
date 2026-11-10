-- Keeps up with the owner once they get past its follow range, faster past the leash. The range is the follow
-- distance while the owner is on the move, and the pet file's follow.idleDistance while they stand about, so a pet
-- roams further then. Teleports up behind them when they get too far away, or after stuck_ticks without getting
-- anywhere: no path toward them, a path that ends short of them at a fence or a shore, or a wall in a flying body's
-- way. A catch-up only ever lands nearer the owner.
return ai.module {
    id = 'fmm:pet/follow_owner', revision = 1,
    -- speed is in blocks per tick at species speed 1 for a flying body; for a walking or swimming one, 4 x speed x the
    -- species speed multiplies its body type's own movement speed, never under a quarter of it. At speed 0 the pet
    -- follows only by its catch-up teleports. leash_urgency multiplies it past the leash distance; trail_back is how
    -- many blocks behind the owner the pet aims. wild switches it on for wild pets, which have no owner to follow, so
    -- it is off and their bodies leave it out.
    settings = { speed = 0.25, leash_urgency = 1.6, trail_back = 1.5, stuck_ticks = 60, wild = false },
    memories = { follow_owner_retry = 'integer' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 4 ticks: it sets off within 0.2 s of the owner passing the follow distance.
            id = 'fmm:pet/follow_owner', priority = 10, probe_interval = 4,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                if c.pet.staying or c.pet.owner_location == nil then return false end
                return c.pet.owner_distance > c.pet.follow_range
            end,
            can_continue = function(c)
                if c.pet.staying or c.pet.owner_location == nil then return false end
                local species = c.pet.species
                if c.pet.owner_distance <= c.pet.follow_range * 0.6 then return false end
                -- At its trail point it is done, even when that point lies farther from the owner than the line
                -- above, as for a pet hovering high over them: otherwise it would hold still there, catching up.
                local target = c.pet:trail_point(c.settings.trail_back, species.hover_height)
                return target == nil or c.pet:distance_to(target) > 0.5
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
                c.pet:steer_to(target, c.settings.speed * urgency * species.speed)
                -- Stuck whether its steering is refused or accepted: native navigation accepts a path that ends short
                -- of the owner, and keeps accepting it.
                if c.pet.stuck_ticks >= c.settings.stuck_ticks
                        and c.tick >= (c.memory:get('follow_owner_retry') or 0) then
                    -- Refused when no spot beside the owner is nearer them than the pet already is; it tries again
                    -- after another stuck_ticks. One that lands moves the pet, which starts the count again.
                    if not c.pet:teleport_near_owner(2) then
                        c.memory:set('follow_owner_retry', c.tick + c.settings.stuck_ticks)
                    end
                end
            end
        }
    }
}
