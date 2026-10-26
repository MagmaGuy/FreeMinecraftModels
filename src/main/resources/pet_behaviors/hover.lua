-- Between drifts: holds position with a small bob in its hover band, so the model never sits dead still.
return ai.module {
    id = 'fmm:pet/hover', revision = 1,
    -- speed is in blocks per tick at species speed 1. A pet more than correct_above blocks off its hover height
    -- heads straight back to it instead of bobbing.
    settings = { speed = 0.03, bob_ticks = 30, correct_above = 1.5 },
    memories = { hover_target = 'position', hover_next_bob = 'integer' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 4 ticks: it takes up its bob again within 4 ticks (0.2 s) of a drift ending.
            id = 'fmm:pet/hover', priority = 30, probe_interval = 4,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c) return not c.pet.staying end,
            tick = function(c)
                if not c.memory:contains('hover_target') or c.tick >= (c.memory:get('hover_next_bob') or 0) then
                    c.memory:set('hover_next_bob', c.tick + c.settings.bob_ticks)
                    local here = c.pet.position
                    local y = here.y + (math.random() - 0.5) * c.pet.species.hover_variance
                    -- The ground within 12 blocks down; nil when its chunk is unloaded, and then it only bobs.
                    local ground = c.pet:ground_below(here, 12)
                    if ground ~= nil then
                        local wanted = ground + c.pet.species.hover_height
                        if math.abs(here.y - wanted) > c.settings.correct_above then y = wanted end
                    end
                    c.memory:set('hover_target', { world = here.world, x = here.x, y = y, z = here.z })
                end
                c.pet:steer_to(c.memory:get('hover_target'), c.settings.speed * c.pet.species.speed)
            end,
            stop = function(c) c.memory:forget('hover_target') end
        }
    }
}
