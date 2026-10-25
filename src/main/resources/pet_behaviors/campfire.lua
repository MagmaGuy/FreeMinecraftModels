-- At night, wild pets and pets settled near their owner drift to the nearest lit campfire and circle slowly in its
-- smoke, a little further round each time, as moths go round a lamp. An owned pet only takes a fire close enough
-- to its owner to stay on its leash.

-- A point on the ring round the smoke at this angle, give or take 0.3 blocks in height.
local function ring_point(c, fire, angle)
    local ring = math.max(0, c.settings.ring)
    return { world = fire.world, x = fire.x + 0.5 + math.cos(angle) * ring,
             y = fire.y + c.settings.height + (math.random() - 0.5) * 0.6, z = fire.z + 0.5 + math.sin(angle) * ring }
end

-- search_seconds in ticks: at least a second, and finite however large the setting.
local function search_ticks(c)
    return math.floor(math.min(math.max(20, c.settings.search_seconds * 20), 2147483647))
end

return ai.module {
    id = 'fmm:pet/campfire', revision = 1,
    -- radius is how far it looks for a fire, in blocks (1 to 32), every search_seconds. It circles height blocks
    -- above the fire, ring blocks out from the middle of the smoke. blocks lists the fire kinds it takes; the
    -- variants in soul_variants then take only soul campfires and every other variant only plain ones. speed is in
    -- blocks per tick at species speed 1. wild and pets switch it on for wild and for owned pets.
    settings = { radius = 12, height = 2.5, ring = 0.8, night_only = true, wild = true, pets = true,
                 blocks = { 'CAMPFIRE', 'SOUL_CAMPFIRE' }, soul_variants = {}, search_seconds = 5, speed = 0.06 },
    memories = { campfire_at = 'position', campfire_point = 'position', campfire_angle = 'number',
                 campfire_next_search = 'integer', campfire_next_check = 'integer', campfire_next_point = 'integer' },
    behaviors = {
        ai.behavior {
            id = 'fmm:pet/campfire', priority = 15,
            controls = { ai.controls.move, ai.controls.look },
            can_start = function(c)
                local s = c.settings
                local allowed = s.pets
                if c.pet.wild then allowed = s.wild end
                if not allowed or c.tick < (c.memory:get('campfire_next_search') or 0) or not c.pet.settled then
                    return false
                end
                if s.night_only and not c.pet.night then return false end
                local search = search_ticks(c)
                c.memory:set('campfire_next_search', c.tick + search)
                local fire = c.pet:nearest_campfire(s.radius, search, s.blocks, s.soul_variants, s.height)
                if fire == nil then return false end
                c.memory:set('campfire_at', fire)
                return true
            end,
            can_continue = function(c)
                local s = c.settings
                local fire = c.memory:get('campfire_at')
                if fire == nil or fire.world ~= c.pet.position.world or not c.pet.settled then return false end
                if s.night_only and not c.pet.night then return false end
                if c.tick < (c.memory:get('campfire_next_check') or 0) then return true end
                -- Once a second: still lit, and still close enough to the owner?
                c.memory:set('campfire_next_check', c.tick + 20)
                return c.pet:campfire_usable(fire, s.blocks, s.soul_variants, s.height)
            end,
            start = function(c)
                local angle = math.random() * math.pi * 2
                c.memory:set('campfire_angle', angle)
                local fire = c.memory:get('campfire_at')
                if fire ~= nil then c.memory:set('campfire_point', ring_point(c, fire, angle)) end
                c.memory:set('campfire_next_point', c.tick + 60)
                c.memory:set('campfire_next_check', c.tick + 20)
            end,
            tick = function(c)
                local fire = c.memory:get('campfire_at')
                if fire == nil then return end
                local point = c.memory:get('campfire_point')
                local due = c.tick >= (c.memory:get('campfire_next_point') or 0)
                if point == nil or due or c.pet:distance_to(point) < 0.3 then
                    local angle = (c.memory:get('campfire_angle') or 0) + 0.5 + math.random() * 0.7
                    c.memory:set('campfire_angle', angle)
                    point = ring_point(c, fire, angle)
                    c.memory:set('campfire_point', point)
                    c.memory:set('campfire_next_point', c.tick + 60 + math.random(0, 39))
                end
                c.pet:steer_to(point, c.settings.speed * c.pet.species.speed)
            end,
            stop = function(c)
                c.memory:forget('campfire_at')
                c.memory:forget('campfire_point')
            end
        }
    }
}
