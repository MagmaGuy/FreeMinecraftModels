-- It waddles over to fish lying on the ground nearby and eats them, one at a time. It leaves alone items kept for one
-- player, such as the fish a pet brings its owner, and anything carrying a name, lore or other data. An owned pet only
-- goes for fish within its follow range of its owner. Each one eaten heals it heal half hearts.

-- seconds in ticks: at least a second, and finite however large the setting.
local function seconds_ticks(seconds)
    return math.floor(math.min(math.max(20, seconds * 20), 2147483647))
end

return ai.module {
    id = 'fmm:pet/fish_thief', revision = 1,
    -- radius is how far it looks for items (names), in blocks (up to 16), every search_seconds. It eats one once its
    -- model is within reach blocks of it (0 to 4), healing heal half hearts (0 to 40), with sound at volume and pitch,
    -- and gives up after give_up_seconds when it cannot reach it. speed sets the walk as in wander. wild and pets
    -- switch it on for wild and for owned pets.
    settings = { radius = 8, items = { 'COD', 'SALMON', 'TROPICAL_FISH', 'COOKED_COD', 'COOKED_SALMON' }, reach = 0.5,
                 heal = 2, speed = 0.2, search_seconds = 2, give_up_seconds = 15, sound = 'entity.generic.eat',
                 volume = 1.0, pitch = 1.0, wild = true, pets = true },
    memories = { fish_at = 'position', fish_until = 'integer', fish_next = 'integer', fish_next_check = 'integer',
                 fish_done = 'boolean' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 10 ticks: it looks only every search_seconds anyway.
            id = 'fmm:pet/fish_thief', priority = 12, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                local s = c.settings
                local allowed = s.pets
                if c.pet.wild then allowed = s.wild end
                if not allowed or c.pet.staying or c.tick < (c.memory:get('fish_next') or 0) then return false end
                c.memory:set('fish_next', c.tick + seconds_ticks(s.search_seconds))
                local fish = c.pet:nearest_item(s.radius, s.items)
                if fish == nil then return false end
                c.memory:set('fish_at', fish)
                return true
            end,
            can_continue = function(c)
                if c.memory:get('fish_done') or c.memory:get('fish_at') == nil or c.pet.staying
                        or c.tick >= (c.memory:get('fish_until') or 0) then
                    return false
                end
                if c.tick < (c.memory:get('fish_next_check') or 0) then return true end
                -- Every half second: still there? A fish swept along by water is followed.
                c.memory:set('fish_next_check', c.tick + 10)
                local fish = c.pet:nearest_item(c.settings.radius, c.settings.items)
                if fish == nil then return false end
                c.memory:set('fish_at', fish)
                return true
            end,
            start = function(c)
                c.memory:forget('fish_done')
                c.memory:set('fish_until', c.tick + seconds_ticks(c.settings.give_up_seconds))
                c.memory:set('fish_next_check', c.tick + 10)
            end,
            tick = function(c)
                local s = c.settings
                local fish = c.memory:get('fish_at')
                if fish == nil then return end
                if c.pet:eat_item(s.reach, s.items, s.heal, s.sound, s.volume, s.pitch) then
                    c.memory:set('fish_done', true)
                    return
                end
                c.pet:steer_to(fish, s.speed * c.pet.species.speed)
            end,
            stop = function(c)
                c.memory:forget('fish_at')
                c.memory:forget('fish_done')
                c.pet:stop()
            end
        }
    }
}
