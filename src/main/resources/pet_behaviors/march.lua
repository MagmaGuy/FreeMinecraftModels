-- Now and then by day a group of pets of one kind sets off on a march in single file, as penguins do crossing the ice.
-- One of them calls with two toots, the others within radius blocks fall in behind it nearest first, spacing blocks
-- apart, and the leader walks straight ahead, turning only where something is in the way. Halfway through, it turns
-- aside with a hairpin and leads the line back to where it called the march, the way back passing beside the way out.
-- The others walk in the leader's own footprints, so the line keeps its shape through a turn. One that something else
-- takes away leaves a gap the ones behind close up, and when it comes back it joins at the back. Wild pets march with
-- wild ones, owned pets with their owner's other pets of the kind.

-- How often a pet that is free looks into calling a march, in ticks.
local CHECK_TICKS = 100
-- Nearer its spot than this, a marcher stands still.
local CLOSE = 0.3
-- Farther from its spot than this, a marcher has fallen behind and hurries. The leader's own spot is at most a block
-- ahead of it, so the leader never hurries.
local BEHIND = 3

return ai.module {
    id = 'fmm:pet/march', revision = 1,
    -- radius is how far off the others may be, in blocks (up to 32), and min_group how many must be there to set off,
    -- the caller included. A group calls a march about every every_minutes, however many are in it. A march lasts
    -- min_seconds to max_seconds, half of it out and the rest on the way back. speed sets the walk as in wander,
    -- the leader keeping to 85% of the slowest marcher's so the others can always close up; a marcher that fell
    -- behind walks catch_up times as fast. sound (any Minecraft or resource pack sound, or NONE)
    -- is the call, at volume and pitch, and animation (a model animation, or '' for none) plays with it. Only adults
    -- call a march, and only by day with no snow falling, unless an admin asks for one. wild and pets switch it on for
    -- wild and for owned pets.
    settings = { radius = 12, min_group = 4, every_minutes = 8, min_seconds = 60, max_seconds = 120, spacing = 1.5,
                 speed = 0.18, catch_up = 1.6, sound = 'block.note_block.didgeridoo', volume = 1.0, pitch = 2.0,
                 animation = '', wild = true, pets = false },
    memories = { march_next = 'integer', march_close = 'boolean' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 10 ticks, so a pet called to a march falls in within half a second. It outranks
            -- fishing, huddling, sliding, presents and walks, but not a fight, a stolen fish or its owner.
            id = 'fmm:pet/march', priority = 13, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                local s = c.settings
                local allowed = s.pets
                if c.pet.wild then allowed = s.wild end
                if not allowed or not c.pet.settled then return false end
                -- Already in a march: one it was called to, or its own.
                if c.pet:march_spot() ~= nil then return true end
                if not c.pet.march_requested then
                    if c.pet.baby or not c.pet.day or c.pet.snowing then return false end
                    if c.tick < (c.memory:get('march_next') or 0) then return false end
                    c.memory:set('march_next', c.tick + CHECK_TICKS)
                end
                -- This check's share of a march every every_minutes; march_call splits it among the group.
                local chance = math.min(1, CHECK_TICKS / (math.max(s.every_minutes, 0.001) * 1200))
                if not c.pet:march_call(s.radius, s.min_group, chance, s.min_seconds, s.max_seconds, s.spacing,
                        s.sound, s.volume, s.pitch) then
                    return false
                end
                if s.animation ~= '' then c.pet:play_animation(s.animation) end
                return true
            end,
            can_continue = function(c)
                return c.pet.settled and c.pet:march_spot() ~= nil
            end,
            start = function(c) c.memory:forget('march_close') end,
            tick = function(c)
                local s = c.settings
                local spot = c.pet:march_spot()
                if spot == nil then return end
                local away = c.pet:distance_to(spot)
                if away > CLOSE then
                    c.memory:forget('march_close')
                    local cruise = s.speed * c.pet.species.speed
                    if away > BEHIND then cruise = cruise * s.catch_up end
                    c.pet:steer_onto(spot, cruise)
                elseif not c.memory:get('march_close') then
                    -- On its spot: the line is forming, or it waits for the one ahead to move on.
                    c.memory:set('march_close', true)
                    c.pet:stop()
                end
            end,
            stop = function(c)
                c.memory:forget('march_close')
                c.pet:stop()
            end
        }
    }
}
