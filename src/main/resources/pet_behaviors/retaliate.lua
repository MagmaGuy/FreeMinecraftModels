-- A wild pet a player hurts charges that player until it reaches them (sting, listed beside it, stings on that
-- touch) or chase_seconds run out, then turns and flees flee_distance blocks away. Each hit is answered once; owned
-- pets ignore it.

-- Seconds to ticks, at least 1, kept finite however large the setting.
local function ticks(seconds)
    return math.max(1, math.floor(math.min(seconds * 20, 2147483647)))
end

-- A hit it has not answered yet, from a player still there to charge.
local function unanswered(c)
    return c.pet.wild and c.pet.attacker ~= nil and c.pet.hurt_count > (c.memory:get('retaliate_answered') or 0)
end

return ai.module {
    id = 'fmm:pet/retaliate', revision = 1,
    -- Speeds are in blocks per tick at species speed 1 for a flying body; for a walking or swimming one, 4 x speed x
    -- the species speed multiplies its body type's own movement speed, never under a quarter of it. reach is how close
    -- to the player's middle the charge ends.
    settings = { chase_seconds = 10, chase_speed = 0.3, reach = 1.5, flee_distance = 8, flee_seconds = 4,
                 flee_speed = 0.3 },
    memories = { retaliate_answered = 'integer', retaliate_charge_until = 'integer', retaliate_flee_until = 'integer',
                 retaliate_flee_to = 'position' },
    behaviors = {
        ai.behavior {
            -- Runs from the player it just reached. Ahead of the charge and of every shipped movement but migrate.
            id = 'fmm:pet/retaliate_flee', priority = 2, probe_interval = 2,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                return c.pet.wild and c.tick < (c.memory:get('retaliate_flee_until') or 0)
                    and c.memory:get('retaliate_flee_to') ~= nil
            end,
            can_continue = function(c)
                local to = c.memory:get('retaliate_flee_to')
                return c.pet.wild and to ~= nil and c.tick < (c.memory:get('retaliate_flee_until') or 0)
                    and c.pet:distance_to(to) >= 1.5
            end,
            tick = function(c)
                local to = c.memory:get('retaliate_flee_to')
                if to == nil then return end
                if not c.pet:steer_to(to, c.settings.flee_speed * c.pet.species.speed) then
                    c.memory:forget('retaliate_flee_until')
                end
            end,
            stop = function(c)
                c.memory:forget('retaliate_flee_to')
                c.pet:stop()
            end
        },
        ai.behavior {
            -- Charges the player who hurt it. Asked every 2 ticks, so it turns on them within a tenth of a second.
            id = 'fmm:pet/retaliate_charge', priority = 3, probe_interval = 2,
            controls = { ai.controls.move, ai.controls.look },
            can_start = unanswered,
            can_continue = function(c)
                return unanswered(c) and c.tick < (c.memory:get('retaliate_charge_until') or 0)
            end,
            start = function(c)
                c.memory:set('retaliate_charge_until', c.tick + ticks(c.settings.chase_seconds))
            end,
            tick = function(c)
                local target = c.pet.attacker
                if target == nil then return end
                if c.pet:distance_to(target) > c.settings.reach then
                    c.pet:steer_to(target, c.settings.chase_speed * c.pet.species.speed)
                    return
                end
                -- Reached: this hit is answered, and it flees straight away from the player.
                local here = c.pet.position
                local dx, dy, dz = here.x - target.x, here.y - target.y, here.z - target.z
                local length = math.sqrt(dx * dx + dy * dy + dz * dz)
                if length < 0.01 then dx, dy, dz, length = 1, 0, 0, 1 end
                local distance = c.settings.flee_distance
                local away = c.pet.position
                away.x = here.x + dx / length * distance
                away.y = here.y + dy / length * distance
                away.z = here.z + dz / length * distance
                -- In its own medium (water for a swimmer) when there is room there.
                c.memory:set('retaliate_flee_to', c.pet:rest_point(away, 3) or away)
                c.memory:set('retaliate_flee_until', c.tick + ticks(c.settings.flee_seconds))
                c.memory:set('retaliate_answered', c.pet.hurt_count)
            end,
            stop = function(c)
                -- A charge that ran out of time gives the hit up too.
                if c.pet.wild and c.tick >= (c.memory:get('retaliate_charge_until') or 0) then
                    c.memory:set('retaliate_answered', c.pet.hurt_count)
                end
                c.memory:forget('retaliate_charge_until')
                c.pet:stop()
            end
        }
    }
}
