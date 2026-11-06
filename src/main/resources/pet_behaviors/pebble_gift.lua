-- Now and then an owned pet brings its owner a present, as Adelie penguins bring pebbles. About every
-- interval_seconds, chance of the time, while its owner is near, it waddles up to them and drops one of items at their
-- feet, for them alone to pick up. Wild pets never do.

-- seconds in ticks: at least a second, and finite however large the setting.
local function seconds_ticks(seconds)
    return math.floor(math.min(math.max(20, seconds * 20), 2147483647))
end

return ai.module {
    id = 'fmm:pet/pebble_gift', revision = 1,
    -- items lists the presents (item names), one picked at random each time; the first chance comes at a random
    -- moment of the first interval_seconds. It drops the present within range blocks of its owner (up to 8), with
    -- sound at volume and pitch, and gives up after give_up_seconds when it cannot reach them. speed sets the walk as
    -- in wander.
    settings = { items = { 'FLINT', 'GRAVEL' }, interval_seconds = 600, chance = 0.5, range = 2, speed = 0.15,
                 give_up_seconds = 15, sound = 'block.gravel.hit', volume = 1.0, pitch = 1.2 },
    memories = { pebble_next = 'integer', pebble_until = 'integer', pebble_done = 'boolean' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 10 ticks: presents come minutes apart.
            id = 'fmm:pet/pebble_gift', priority = 14, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                local s = c.settings
                if c.pet.wild or not c.pet.settled or c.pet.owner_location == nil or #s.items == 0 then return false end
                local interval = seconds_ticks(s.interval_seconds)
                local next = c.memory:get('pebble_next')
                if next == nil then
                    c.memory:set('pebble_next', c.tick + math.floor(math.random() * interval))
                    return false
                end
                if c.tick < next then return false end
                c.memory:set('pebble_next', c.tick + interval)
                return math.random() < s.chance
            end,
            can_continue = function(c)
                return not c.memory:get('pebble_done') and not c.pet.staying and c.pet.owner_location ~= nil
                        and c.tick < (c.memory:get('pebble_until') or 0)
            end,
            start = function(c)
                c.memory:forget('pebble_done')
                c.memory:set('pebble_until', c.tick + seconds_ticks(c.settings.give_up_seconds))
            end,
            tick = function(c)
                local s = c.settings
                local owner = c.pet.owner_location
                if owner == nil then return end
                if c.pet.owner_distance <= s.range then
                    local items = s.items
                    if #items > 0 then c.pet:gift(items[math.random(#items)], 1, s.range, s.sound, s.volume, s.pitch) end
                    c.memory:set('pebble_done', true)
                    return
                end
                c.pet:steer_to(owner, s.speed * c.pet.species.speed)
            end,
            stop = function(c)
                c.memory:forget('pebble_until')
                c.memory:forget('pebble_done')
                c.pet:stop()
            end
        }
    }
}
