-- On a migration: flies the flock's route ahead of everything else, until the route ends or the pet is tamed.
return ai.module {
    id = 'fmm:pet/migrate', revision = 1,
    behaviors = {
        ai.behavior {
            -- Asked to start every 10 ticks: a late starter catches up, as members outfly the flock's anchor.
            id = 'fmm:pet/migrate', priority = 1, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c) return c.pet.migrating and c.pet:route_target() ~= nil end,
            tick = function(c)
                local target = c.pet:route_target()
                -- The flock's cruise, not the species' speed, so the whole flock flies together.
                if target ~= nil then c.pet:steer_to(target, c.pet.route_cruise) end
            end,
            stop = function(c) c.pet:stop() end
        }
    }
}
