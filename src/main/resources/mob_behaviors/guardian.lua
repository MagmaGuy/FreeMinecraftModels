-- A mob that serves its owner: it stays near them, and goes for whoever hurts them, whoever they hurt, and whoever
-- hurts it. It fights the way its kind fights: a creeper runs up and blows itself up, a skeleton or pillager shoots,
-- anything else hits. It never hurts its owner, and without an owner it only defends itself.
--
-- Put it on a mob from a script with mob:override_ai('guardian', { owner = player, duration = 600 }), or in game
-- with /fmm mobbehavior apply guardian.

-- How often it plans a new path to someone who keeps moving, in ticks.
local REPATH_TICKS = 10

local function distance(a, b)
    local dx, dy, dz = a.x - b.x, a.y - b.y, a.z - b.z
    return math.sqrt(dx * dx + dy * dy + dz * dz)
end

-- Whom it fights now: the owner's attacker first, then its own, then the owner's target. Each is remembered for ten
-- seconds after the hit, and never is the owner or another mob serving them.
local function threat(c)
    local found = c.overlay.owner_attacker
    if found == nil and c.settings.defend_itself then found = c.overlay.attacker end
    if found == nil and c.settings.assist then found = c.overlay.owner_target end
    return found
end

-- Walks toward a location, planning a new path only every REPATH_TICKS.
local function approach(c, where, speed)
    if c.tick < (c.memory:get('guardian_repath') or 0) then return end
    c.memory:set('guardian_repath', c.tick + REPATH_TICKS)
    c.actuator:move_to(where, speed)
end

-- Its attack is ready again attack_ticks after the last one.
local function ready(c)
    return c.tick >= (c.memory:get('guardian_next_attack') or 0)
end

local function attacked(c)
    c.memory:set('guardian_next_attack', c.tick + c.settings.attack_ticks)
end

return ai.module {
    id = 'fmm:mob/guardian', revision = 1,
    -- Speeds multiply the mob's own movement speed. follow_distance is how far the owner gets before it comes after
    -- them, and follow_stop how close it comes. reach is how close it gets to hit; a creeper starts its fuse within
    -- fuse_distance and gives up past fuse_escape. shoot_distance is how far a mob that shoots does so from.
    -- attack_ticks is the time between two hits or shots. assist also sends it after whoever the owner hurts, and
    -- defend_itself after whoever hurts it.
    settings = { follow_distance = 6, follow_stop = 3, follow_speed = 1.2, chase_speed = 1.3, reach = 2.2,
                 fuse_distance = 3, fuse_escape = 7, shoot_distance = 12, attack_ticks = 20, assist = true,
                 defend_itself = true },
    memories = { guardian_repath = 'integer', guardian_next_attack = 'integer', guardian_fuse = 'boolean',
                 guardian_shoots = 'boolean' },
    behaviors = {
        ai.behavior {
            -- Asked every 4 ticks, so it turns on a threat within a fifth of a second. It runs every tick: a
            -- creeper swells only on the ticks it attacks.
            id = 'fmm:mob/guardian_fight', priority = 5, probe_interval = 4,
            controls = { ai.controls.move, ai.controls.look, ai.controls.attack },
            can_start = function(c) return threat(c) ~= nil end,
            start = function(c)
                c.memory:forget('guardian_repath')
                c.memory:forget('guardian_fuse')
            end,
            tick = function(c)
                local target = threat(c)
                if target == nil then return end
                local here, there = c.entity.current_location, target.current_location
                local gap = distance(here, there)
                there.y = there.y + 1
                c.actuator:look_at(there)
                there.y = there.y - 1

                if c.entity.entity_type == 'creeper' then
                    -- Its fuse starts close up and holds while the target stays within the escape distance.
                    local lit = c.memory:get('guardian_fuse')
                    if gap <= c.settings.fuse_distance then lit = true end
                    if gap > c.settings.fuse_escape or not c.perception:can_see(target) then lit = false end
                    c.memory:set('guardian_fuse', lit or false)
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
                        c.actuator:attack(target)
                        attacked(c)
                    end
                    return
                end

                -- A kind that shoots stands off and shoots while it can see the target; the first shot tells.
                if gap <= c.settings.shoot_distance and c.memory:get('guardian_shoots') ~= false
                    and c.perception:can_see(target) then
                    if not ready(c) then
                        if c.memory:get('guardian_shoots') then
                            c.actuator:stop_moving()
                            return
                        end
                    elseif c.actuator:shoot(target) then
                        c.memory:set('guardian_shoots', true)
                        attacked(c)
                        c.actuator:stop_moving()
                        return
                    else
                        c.memory:set('guardian_shoots', false)
                    end
                end
                approach(c, there, c.settings.chase_speed)
            end,
            stop = function(c)
                c.memory:forget('guardian_fuse')
                c.actuator:stop_moving()
            end
        },
        ai.behavior {
            -- Comes after its owner once they are follow_distance away, and stops follow_stop from them.
            id = 'fmm:mob/guardian_follow', priority = 20, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                local owner = c.overlay.owner
                return owner ~= nil
                    and distance(c.entity.current_location, owner.current_location) > c.settings.follow_distance
            end,
            can_continue = function(c)
                local owner = c.overlay.owner
                return owner ~= nil
                    and distance(c.entity.current_location, owner.current_location) > c.settings.follow_stop
            end,
            start = function(c) c.memory:forget('guardian_repath') end,
            tick = function(c)
                local owner = c.overlay.owner
                if owner == nil then return end
                local there = owner.current_location
                approach(c, there, c.settings.follow_speed)
                there.y = there.y + 1
                c.actuator:look_at(there)
            end,
            stop = function(c) c.actuator:stop_moving() end
        }
    }
}
