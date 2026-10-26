-- Lights the model at a fixed level at night, whatever the light where it floats, and hands it back to world light by
-- day. A model with the bone that lantern_bone names also loops its glow animation while it glows. Java players only.
return ai.module {
    id = 'fmm:pet/night_glow', revision = 1,
    -- light is 0 to 15; 15 is fully bright. With night_only false it glows day and night.
    settings = { night_only = true, light = 15, lantern_bone = 'lantern', animation = 'glow' },
    sensors = {
        ai.sensor {
            id = 'fmm:pet/night_glow', interval = 40,
            sense = function(c)
                local s = c.settings
                if not s.night_only or c.pet.night then
                    c.pet:set_glow(s.light, s.lantern_bone, s.animation)
                else
                    c.pet:clear_glow()
                end
            end
        }
    }
}
