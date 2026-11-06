-- When one of its kind nearby sets off on a walk, it falls in behind, and so may the others, each following the one
-- ahead of it spacing blocks back: the group waddles along in single file, as penguins do on their way to the sea.
-- The line breaks up when the leader's walk ends. Wild pets follow wild ones, owned pets their owner's other pets of
-- the kind.

return ai.module {
    id = 'fmm:pet/single_file', revision = 1,
    -- A pet on a walk of one of the leaders behaviors (module names) within radius blocks (up to 32) leads; join_chance
    -- (0 to 1) is how likely it is to fall in behind a walk it sees, and a refusal waits 5 seconds. speed sets the walk
    -- as in wander, a little quicker than wander's own so the line keeps up. wild and pets switch it on for wild and
    -- for owned pets.
    settings = { radius = 8, spacing = 1.5, join_chance = 0.6, speed = 0.2, leaders = { 'wander' }, wild = true,
                 pets = false },
    memories = { file_ahead = 'position', file_next = 'integer', file_close = 'boolean' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 10 ticks: a walk lasts seconds.
            id = 'fmm:pet/single_file', priority = 18, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                local s = c.settings
                local allowed = s.pets
                if c.pet.wild then allowed = s.wild end
                if not allowed or not c.pet.settled or c.tick < (c.memory:get('file_next') or 0) then return false end
                -- Rolled before it looks, since looking takes its place in a line.
                if math.random() >= s.join_chance then
                    c.memory:set('file_next', c.tick + 100)
                    return false
                end
                local ahead = c.pet:line_ahead(s.radius, s.leaders)
                if ahead == nil then return false end
                c.memory:set('file_ahead', ahead)
                return true
            end,
            can_continue = function(c)
                if not c.pet.settled then return false end
                -- Keeps its place in the line every tick; the line is gone once the leader's walk ends.
                local ahead = c.pet:line_ahead(c.settings.radius, c.settings.leaders)
                if ahead == nil then return false end
                c.memory:set('file_ahead', ahead)
                return true
            end,
            start = function(c) c.memory:forget('file_close') end,
            tick = function(c)
                local ahead = c.memory:get('file_ahead')
                if ahead == nil then return end
                if c.pet:distance_to(ahead) > c.settings.spacing then
                    c.memory:forget('file_close')
                    c.pet:steer_to(ahead, c.settings.speed * c.pet.species.speed)
                elseif not c.memory:get('file_close') then
                    -- Close behind: wait for the one ahead to move on.
                    c.memory:set('file_close', true)
                    c.pet:stop()
                end
            end,
            stop = function(c)
                c.memory:forget('file_ahead')
                c.memory:forget('file_close')
                c.pet:stop()
            end
        }
    }
}
