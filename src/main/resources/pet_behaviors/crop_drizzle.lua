-- Hovering still over crops, it rains on them: every interval_seconds, a chance that the crop under it grows one
-- stage. Wild and owned pets alike.

-- interval_seconds in ticks: at least a second, and finite however large the setting. The check runs once a second,
-- so an interval that is not a whole number of seconds waits until the next whole second.
local function interval_ticks(c)
    return math.floor(math.min(math.max(20, c.settings.interval_seconds * 20), 2147483647))
end

return ai.module {
    id = 'fmm:pet/crop_drizzle', revision = 1,
    -- chance is 0 to 1 per drizzle. depth is how many blocks down it looks for the crop (1 to 8), through air only.
    settings = { chance = 0.1, interval_seconds = 5, depth = 4 },
    memories = { crop_drizzle_next = 'integer' },
    sensors = {
        ai.sensor {
            id = 'fmm:pet/crop_drizzle', interval = 20,
            sense = function(c)
                if c.tick < (c.memory:get('crop_drizzle_next') or 0) then return end
                c.memory:set('crop_drizzle_next', c.tick + interval_ticks(c))
                c.pet:drizzle(c.settings.depth, c.settings.chance)
            end
        }
    }
}
