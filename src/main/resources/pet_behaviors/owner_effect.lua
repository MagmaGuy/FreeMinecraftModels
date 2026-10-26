-- While the pet is out and within range of its owner, the owner has a potion effect: a pet's ability, such as night
-- vision or water breathing. It is renewed well before it runs out, so it never blinks between checks, and it wears
-- off within 15 seconds of the pet going away or the owner leaving its range. A stronger or longer effect the owner
-- already has, such as a drunk potion, is left alone. It suits steady effects: night vision, water breathing, speed,
-- haste, fire resistance. Effects that act on their remaining time, such as regeneration, poison and wither, misbehave
-- when renewed every second.
return ai.module {
    id = 'fmm:pet/owner_effect', revision = 1,
    -- effect is a potion effect name, at amplifier (0 is level I). range is in blocks from the pet.
    settings = { effect = 'NIGHT_VISION', amplifier = 0, range = 16 },
    sensors = {
        ai.sensor {
            id = 'fmm:pet/owner_effect', interval = 20,
            sense = function(c)
                if c.pet.wild then return end
                -- Night vision flickers in its last 10 seconds; 15 seconds renewed every second stays clear of that.
                c.pet:owner_effect(c.settings.effect, 300, c.settings.amplifier, c.settings.range)
            end
        }
    }
}
