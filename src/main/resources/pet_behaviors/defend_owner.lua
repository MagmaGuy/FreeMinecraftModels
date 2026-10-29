-- Goes for whoever just attacked its owner or one of its owner's pets, as a wolf does: a mob, a wild pet, or a player
-- whose attack the server let through (so PvP is on there) and that player's pets. It only goes after them near its
-- owner, and sting then stings them on touch. A pet told to stay stays out of it.
return ai.module {
    id = 'fmm:pet/defend_owner', revision = 1,
    -- speed is in blocks per tick at species speed 1 for a flying body; for a walking or swimming one, 4 x speed x the
    -- species speed multiplies its body type's own movement speed, never under a quarter of it.
    settings = { speed = 0.3 },
    behaviors = {
        ai.behavior {
            -- Asked to start every 4 ticks: it turns on an attacker within 0.2 s.
            id = 'fmm:pet/defend_owner', priority = 7, probe_interval = 4,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                return not c.pet.wild and not c.pet.staying and c.pet.threat ~= nil
            end,
            can_continue = function(c)
                return not c.pet.wild and not c.pet.staying and c.pet.threat ~= nil
            end,
            tick = function(c)
                local threat = c.pet.threat
                if threat == nil then return end
                c.pet:steer_to(threat, c.settings.speed * c.pet.species.speed)
            end
        }
    }
}
