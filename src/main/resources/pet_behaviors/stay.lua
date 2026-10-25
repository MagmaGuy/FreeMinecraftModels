-- Told to stay: holds the spot, with a gentle bob so it never looks parked.
return ai.module {
    id = 'fmm:pet/stay', revision = 1,
    -- speed is in blocks per tick at species speed 1; bob_radius is how far from the spot each bob may wander.
    settings = { speed = 0.03, bob_ticks = 40, bob_radius = 0.5 },
    memories = { stay_target = 'position', stay_next_bob = 'integer' },
    behaviors = {
        ai.behavior {
            id = 'fmm:pet/stay', priority = 5,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c) return c.pet.staying and c.pet.stay_anchor ~= nil end,
            tick = function(c)
                local anchor = c.pet.stay_anchor
                if anchor == nil then return end
                if not c.memory:contains('stay_target') or c.tick >= (c.memory:get('stay_next_bob') or 0) then
                    c.memory:set('stay_next_bob', c.tick + c.settings.bob_ticks)
                    local point = c.pet:hover_point(anchor, c.settings.bob_radius)
                    if point ~= nil then c.memory:set('stay_target', point) end
                end
                local target = c.memory:get('stay_target')
                if target ~= nil then c.pet:steer_to(target, c.settings.speed * c.pet.species.speed) end
            end,
            stop = function(c)
                c.memory:forget('stay_target')
                c.pet:stop()
            end
        }
    }
}
