-- Touching it stings. A wild pet stings players whose touch reaches its model; an owned one stings hostile mobs that
-- come close, never a player. Adults play their attack animation as they sting.

-- cooldown_seconds in ticks, kept finite however large the setting; c.pet takes at least 1.
local function cooldown_ticks(c)
    return math.min(c.settings.cooldown_seconds * 20, 2147483647)
end

return ai.module {
    id = 'fmm:pet/sting', revision = 1,
    -- effect is a potion effect name, or NONE for none, given for duration_ticks at amplifier (0 is level I); damage
    -- is in half hearts. The same victim waits cooldown_seconds before this pet stings it again. reach is how far
    -- past a mob's box the model's touch counts, in blocks (0 to 4). wild and pets switch it on for wild and for
    -- owned pets.
    settings = { wild = true, pets = true, effect = 'SLOWNESS', duration_ticks = 60, amplifier = 0, damage = 0,
                 cooldown_seconds = 3, reach = 0.5 },
    sensors = {
        ai.sensor {
            id = 'fmm:pet/sting_players', interval = 2,
            sense = function(c)
                -- Only a wild pet's model records touches, so an owned pet never stings a player. A wild one always
                -- takes them, so they never pile up while stinging is off.
                if not c.pet.wild then return end
                local s = c.settings
                c.pet:sting_contacts(s.wild, s.effect, s.duration_ticks, s.amplifier, s.damage, cooldown_ticks(c))
            end
        },
        ai.sensor {
            id = 'fmm:pet/sting_mobs', interval = 10,
            sense = function(c)
                local s = c.settings
                if c.pet.wild or not s.pets then return end
                c.pet:sting_enemies(s.reach, s.effect, s.duration_ticks, s.amplifier, s.damage, cooldown_ticks(c))
            end
        }
    }
}
