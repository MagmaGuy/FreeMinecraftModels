-- Turns a mob on whatever its targets setting names: by default the nearest player, which makes a chicken, a cow or a
-- villager attack on sight. It also goes for whoever hurts it. It fights the way its kind fights: a creeper blows
-- itself up, a skeleton or pillager shoots, anything else hits. A kind with no attack of its own, like a chicken,
-- deals 2, or the damage setting when that is above 0. With an owner it spares them and the other mobs that serve them.
--
-- Put it on a mob from a script with mob:override_ai('hostile'), or in game with /fmm mobbehavior applywild hostile.
-- /fmm mobbehavior apply makes you its owner, whom it spares.

-- How often it plans a new path to someone who keeps moving, in ticks.
local REPATH_TICKS = 10

local function distance(a, b)
    local dx, dy, dz = a.x - b.x, a.y - b.y, a.z - b.z
    return math.sqrt(dx * dx + dy * dy + dz * dz)
end

-- How often it looks around for the nearest target, in ticks. In between it keeps after the one it found.
local LOOK_TICKS = 10

-- Whom it fights now: whoever hurt it in the last ten seconds, else the nearest target in range.
local function prey(c)
    local attacker = c.overlay.attacker
    if attacker ~= nil then return attacker end
    if c.tick >= (c.memory:get('hostile_look') or 0) then
        c.memory:set('hostile_look', c.tick + LOOK_TICKS)
        local found = c.overlay:nearest(c.settings.range, c.settings.targets)
        if found == nil then
            c.memory:forget('hostile_prey')
            return nil
        end
        c.memory:set('hostile_prey', found.uuid)
        return found
    end
    local known = c.memory:get('hostile_prey')
    if known == nil then return nil end
    return c.overlay:entity(known)
end

local function approach(c, where, speed)
    if c.tick < (c.memory:get('hostile_repath') or 0) then return end
    c.memory:set('hostile_repath', c.tick + REPATH_TICKS)
    c.actuator:move_to(where, speed)
end

local function ready(c)
    return c.tick >= (c.memory:get('hostile_next_attack') or 0)
end

local function attacked(c)
    c.memory:set('hostile_next_attack', c.tick + c.settings.attack_ticks)
end

return ai.module {
    id = 'fmm:mob/hostile', revision = 1,
    -- targets is player, hostile, passive, living, or an entity type such as zombie. range is how far it notices
    -- them, in blocks. chase_speed multiplies the mob's own movement speed. reach is how close it gets to hit; a
    -- creeper starts its fuse within fuse_distance and gives up past fuse_escape. shoot_distance is how far a mob
    -- that shoots does so from. attack_ticks is the time between two hits or shots. damage is what a hit deals;
    -- 0 leaves it to the mob's own attack, which is 2 for a kind that has none.
    settings = { targets = 'player', range = 16, chase_speed = 1.3, reach = 2.2, fuse_distance = 3, fuse_escape = 7,
                 shoot_distance = 12, attack_ticks = 20, damage = 0 },
    memories = { hostile_repath = 'integer', hostile_next_attack = 'integer', hostile_fuse = 'boolean',
                 hostile_shoots = 'boolean', hostile_look = 'integer', hostile_prey = 'uuid' },
    behaviors = {
        ai.behavior {
            -- Asked every 10 ticks. It runs every tick: a creeper swells only on the ticks it attacks.
            id = 'fmm:mob/hostile_hunt', priority = 10, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look, ai.controls.attack },
            can_start = function(c) return prey(c) ~= nil end,
            start = function(c)
                c.memory:forget('hostile_repath')
                c.memory:forget('hostile_fuse')
            end,
            tick = function(c)
                local target = prey(c)
                if target == nil then return end
                local here, there = c.entity.current_location, target.current_location
                local gap = distance(here, there)
                there.y = there.y + 1
                c.actuator:look_at(there)
                there.y = there.y - 1

                if c.entity.entity_type == 'creeper' then
                    local lit = c.memory:get('hostile_fuse')
                    if gap <= c.settings.fuse_distance then lit = true end
                    if gap > c.settings.fuse_escape or not c.perception:can_see(target) then lit = false end
                    c.memory:set('hostile_fuse', lit or false)
                    if lit then
                        c.actuator:stop_moving()
                        c.actuator:attack(target)
                    else
                        approach(c, there, c.settings.chase_speed)
                    end
                    return
                end

                if gap <= c.settings.reach then
                    c.actuator:stop_moving()
                    if ready(c) then
                        if c.settings.damage > 0 then
                            c.actuator:attack(target, c.settings.damage)
                        else
                            c.actuator:attack(target)
                        end
                        attacked(c)
                    end
                    return
                end

                -- A kind that shoots stands off and shoots while it can see the target; the first shot tells.
                if gap <= c.settings.shoot_distance and c.memory:get('hostile_shoots') ~= false
                    and c.perception:can_see(target) then
                    if not ready(c) then
                        if c.memory:get('hostile_shoots') then
                            c.actuator:stop_moving()
                            return
                        end
                    elseif c.actuator:shoot(target) then
                        c.memory:set('hostile_shoots', true)
                        attacked(c)
                        c.actuator:stop_moving()
                        return
                    else
                        c.memory:set('hostile_shoots', false)
                    end
                end
                approach(c, there, c.settings.chase_speed)
            end,
            stop = function(c)
                c.memory:forget('hostile_fuse')
                c.actuator:stop_moving()
            end
        }
    }
}
