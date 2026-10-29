-- On land: walks to a standing spot near its anchor (the owner, or home when wild), rests a while, and goes again.
-- follow_owner takes over whenever the owner gets past the follow distance.

-- Within this many blocks of its spot a walk is over: native navigation stops about a block short of its goal, give
-- or take a waypoint.
local ARRIVED = 1.5

-- How far from its anchor a walk may end: the roam radius around home, and for an owned pet within its follow range
-- of the owner (no farther than 0.8 of it), so follow_owner does not take over the moment it arrives.
local function trip_radius(c)
    return c.pet.roam_range
end

return ai.module {
    id = 'fmm:pet/wander', revision = 1,
    -- speed sets the walk: 4 x speed x the species speed multiplies the body type's own movement speed, never under a
    -- quarter of it, and 0 stands still. A walk gives up after walk_ticks, or after stuck_ticks without moving; a rest
    -- lasts rest_ticks plus a random 0 to rest_random_ticks - 1 more.
    settings = { speed = 0.15, walk_ticks = 120, stuck_ticks = 20, rest_ticks = 80, rest_random_ticks = 120 },
    memories = { wander_to = 'position', wander_next = 'integer', wander_until = 'integer', wander_last = 'position',
                 wander_stuck = 'integer' },
    behaviors = {
        ai.behavior {
            -- Asked to start every 10 ticks: it rests 4 to 10 seconds between walks anyway.
            id = 'fmm:pet/wander', priority = 20, probe_interval = 10,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                if c.pet.staying or c.tick < (c.memory:get('wander_next') or 0) then return false end
                return c.pet.anchor ~= nil
            end,
            can_continue = function(c)
                local to = c.memory:get('wander_to')
                if to == nil or c.tick >= (c.memory:get('wander_until') or 0) or c.pet.staying then return false end
                if c.pet:distance_to(to) <= ARRIVED then return false end
                -- Standing still: no path reaches the spot, or the path ended short of it. Rest, then try another.
                return (c.memory:get('wander_stuck') or 0) < c.settings.stuck_ticks
            end,
            start = function(c)
                local anchor = c.pet.anchor
                if anchor == nil then return end
                -- nil over water or a long drop, or in an unloaded chunk: this walk ends at once, and its rest decides
                -- when the next one looks, so a pet with nowhere to stand does not search every tick.
                local to = c.pet:ground_point(anchor, trip_radius(c))
                if to == nil then return end
                c.memory:set('wander_to', to)
                c.memory:set('wander_until', c.tick + c.settings.walk_ticks)
                c.memory:set('wander_last', c.pet.position)
                c.memory:set('wander_stuck', 0)
            end,
            tick = function(c)
                local to = c.memory:get('wander_to')
                if to == nil then return end
                local here, last = c.pet.position, c.memory:get('wander_last')
                local stuck = 0
                if last ~= nil then
                    local dx, dy, dz = here.x - last.x, here.y - last.y, here.z - last.z
                    if dx * dx + dy * dy + dz * dz < 1e-4 then stuck = (c.memory:get('wander_stuck') or 0) + 1 end
                end
                c.memory:set('wander_stuck', stuck)
                c.memory:set('wander_last', here)
                c.pet:steer_to(to, c.settings.speed * c.pet.species.speed)
            end,
            stop = function(c)
                c.memory:forget('wander_to')
                c.pet:stop()
                local rest = c.settings.rest_ticks + math.floor(math.random() * math.max(0, c.settings.rest_random_ticks))
                c.memory:set('wander_next', c.tick + rest)
            end
        }
    }
}
