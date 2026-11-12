-- Makes a mob run from whatever its fears setting names: by default the nearest player, which sends a pillager or a
-- zombie running instead of attacking. It runs from whoever hurts it too. With an owner it does not fear them or the
-- other mobs that serve them. Hemmed in, it backs away to the far wall. A mob that cannot plan a path at all, like a
-- slime, stays where it is.
--
-- Put it on a mob from a script with mob:override_ai('coward', { duration = 200 }), or in game with
-- /fmm mobbehavior applywild coward. /fmm mobbehavior apply makes you its owner, whom it does not fear.

local function distance(a, b)
    local dx, dy, dz = a.x - b.x, a.y - b.y, a.z - b.z
    return math.sqrt(dx * dx + dy * dy + dz * dz)
end

-- What it runs from now: whoever hurt it in the last ten seconds, else the nearest thing it fears within the range.
local function scare(c, range)
    local attacker = c.overlay.attacker
    if attacker ~= nil and distance(c.entity.current_location, attacker.current_location) <= range then
        return attacker
    end
    return c.overlay:nearest(range, c.settings.fears)
end

return ai.module {
    id = 'fmm:mob/coward', revision = 1,
    -- fears is player, hostile, passive, living, or an entity type such as wolf. It starts running when one comes
    -- within range blocks and stops once it is safe_distance away. speed multiplies the mob's own movement speed.
    -- cornered_distance is how far it tries to back off where it finds no way out, in a room or a pen.
    settings = { fears = 'player', range = 10, safe_distance = 16, speed = 1.5, cornered_distance = 6 },
    behaviors = {
        ai.behavior {
            -- Asked and run every 5 ticks: each run plans a path of about ten blocks away from the scare, and the
            -- mob follows it in between.
            id = 'fmm:mob/coward_flee', priority = 5, probe_interval = 5, tick_interval = 5,
            controls = { ai.controls.move },
            can_start = function(c) return scare(c, c.settings.range) ~= nil end,
            can_continue = function(c) return scare(c, c.settings.safe_distance) ~= nil end,
            tick = function(c)
                local from = scare(c, c.settings.safe_distance)
                if from == nil then return end
                local threat = from.current_location
                if c.actuator:flee(threat, c.settings.speed) then return end
                -- Hemmed in: a room or a pen leaves no way out ten blocks long. It backs off straight away from
                -- the scare, as far as it can get, and ends up against the far wall.
                local here = c.entity.current_location
                local dx, dz = here.x - threat.x, here.z - threat.z
                local length = math.sqrt(dx * dx + dz * dz)
                if length < 0.01 then dx, dz, length = 1, 0, 1 end
                here.x = here.x + dx / length * c.settings.cornered_distance
                here.z = here.z + dz / length * c.settings.cornered_distance
                c.actuator:move_to(here, c.settings.speed)
            end,
            stop = function(c) c.actuator:stop_moving() end
        }
    }
}
