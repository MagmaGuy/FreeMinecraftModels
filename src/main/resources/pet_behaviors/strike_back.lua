-- A wild pet strikes the player who hurt it once its touch reaches them, once per hit: a headbutt, a peck. Listed
-- beside retaliate, which charges that player, it strikes as the charge arrives, before the pet runs off. It plays an
-- animation of its model as it strikes. Owned pets ignore it.

return ai.module {
    id = 'fmm:pet/strike_back', revision = 1,
    -- reach is how far past the player's box the model's touch counts, in blocks (0 to 4). damage is in half hearts.
    -- effect is a potion effect name, FREEZE to freeze the player as powder snow does, or NONE, given for
    -- duration_ticks at amplifier (0 is level I); a freeze past 140 ticks holds them fully frozen for half the rest.
    -- sound plays from the pet at volume and pitch: a Minecraft sound or a resource pack's own, or NONE. particle shows
    -- on the player: a Minecraft particle that needs no colour or item, or NONE. animation is the model animation it
    -- plays as it strikes, or NONE; a model without it plays none. delay_ticks is how long after the animation starts
    -- the strike lands, for an animation that winds up before it connects: at 0 the hit, the sound and the particle
    -- come with the animation's first tick. With a delay the animation starts when the pet's touch first reaches the
    -- player, within reach as the strike itself counts it, and the strike lands delay_ticks later on a player still
    -- within reach. One who stepped out of reach in
    -- that time has dodged it: the swing was thrown and missed, and the pet does not swing again for that hit. pets
    -- switches it on for owned pets, which never strike back, so it is off and their bodies leave it out.
    settings = { reach = 1.0, damage = 2.0, effect = 'NONE', duration_ticks = 60, amplifier = 0,
                 sound = 'entity.goat.ram_impact', volume = 1.0, pitch = 1.0, particle = 'crit', animation = 'attack',
                 delay_ticks = 0, pets = false },
    -- strike_back_swing is the hit a swing in the air answers, and strike_back_lands the tick it lands.
    memories = { strike_back_answered = 'integer', strike_back_swing = 'integer', strike_back_lands = 'integer' },
    sensors = {
        ai.sensor {
            -- Every tick while a hit is unanswered, so a charge that arrives and turns away is still caught.
            id = 'fmm:pet/strike_back', interval = 1,
            sense = function(c)
                if not c.pet.wild or c.pet.hurt_count <= (c.memory:get('strike_back_answered') or 0) then return end
                if c.pet.attacker == nil then
                    -- Gone, out of time, or no longer hurtable: this hit goes unanswered.
                    c.memory:set('strike_back_answered', c.pet.hurt_count)
                    return
                end
                local s = c.settings
                if s.delay_ticks <= 0 then
                    if c.pet:strike_attacker(s.reach, s.effect, s.duration_ticks, s.amplifier, s.damage, 1, s.sound,
                            s.volume, s.pitch, s.particle, s.animation) then
                        c.memory:set('strike_back_answered', c.pet.hurt_count)
                    end
                    return
                end
                if c.memory:get('strike_back_swing') ~= c.pet.hurt_count then
                    -- No swing for this hit yet: it starts as the touch arrives, and the animation plays once.
                    if c.pet:touching_attacker(s.reach) then
                        c.pet:play_animation(s.animation)
                        c.memory:set('strike_back_swing', c.pet.hurt_count)
                        c.memory:set('strike_back_lands', c.tick + s.delay_ticks)
                    end
                    return
                end
                if c.tick < (c.memory:get('strike_back_lands') or 0) then return end
                -- The swing comes down. It lands only on a touch, so a player out of reach now has dodged it, and
                -- either way this hit is answered.
                c.pet:strike_attacker(s.reach, s.effect, s.duration_ticks, s.amplifier, s.damage, 1, s.sound, s.volume,
                        s.pitch, s.particle, 'NONE')
                c.memory:set('strike_back_answered', c.pet.hurt_count)
            end
        }
    }
}
