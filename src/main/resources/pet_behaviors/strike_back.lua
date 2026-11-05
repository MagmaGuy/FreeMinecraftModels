-- A wild pet strikes the player who hurt it once its touch reaches them, once per hit: a headbutt, a peck. Listed
-- beside retaliate, which charges that player, it strikes as the charge arrives, before the pet runs off. It plays its
-- attack animation as it strikes. Owned pets ignore it.

return ai.module {
    id = 'fmm:pet/strike_back', revision = 1,
    -- reach is how far past the player's box the model's touch counts, in blocks (0 to 4). damage is in half hearts.
    -- effect is a potion effect name, FREEZE to freeze the player as powder snow does, or NONE, given for
    -- duration_ticks at amplifier (0 is level I); a freeze past 140 ticks holds them fully frozen for half the rest.
    -- sound plays from the pet at volume and pitch: a Minecraft sound or a resource pack's own, or NONE. particle shows
    -- on the player: a Minecraft particle that needs no colour or item, or NONE.
    settings = { reach = 1.0, damage = 2.0, effect = 'NONE', duration_ticks = 60, amplifier = 0,
                 sound = 'entity.goat.ram_impact', volume = 1.0, pitch = 1.0, particle = 'crit' },
    memories = { strike_back_answered = 'integer' },
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
                if c.pet:strike_attacker(s.reach, s.effect, s.duration_ticks, s.amplifier, s.damage, 1, s.sound,
                        s.volume, s.pitch, s.particle) then
                    c.memory:set('strike_back_answered', c.pet.hurt_count)
                end
            end
        }
    }
}
