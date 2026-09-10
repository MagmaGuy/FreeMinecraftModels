------------------------------------------------------------
-- Chicken Rain — Adjustable Settings
------------------------------------------------------------

-- Cooldown
local COOLDOWN_TICKS        = 600       -- Ticks before power can be used again (20 ticks = 1 second)

-- Egg count & timing
local EGG_COUNT             = 7         -- Number of explosive eggs to drop
local SPAWN_STAGGER_TICKS   = 6         -- Ticks between each egg spawn

-- Spawn ring shape
local RING_MIN_RADIUS       = 3         -- Inner edge of the spawn ring (blocks from player)
local RING_WIDTH            = 5         -- Width of the ring (eggs spawn between min and min+width)

-- Egg flight
local SPAWN_HEIGHT          = 10        -- Height above the PLAYER to spawn eggs
local SPAWN_HEIGHT_VARIANCE = 3         -- Random extra height added on top of base
local MAX_FALL_SCAN         = 40        -- Max distance for raycast downward (blocks)

-- Fall animation
local FALL_STEPS            = 12        -- Number of animation steps for the falling egg
local FALL_INTERVAL         = 2         -- Ticks between each animation step

-- Egg projectile particles (the falling "egg" itself)
local EGG_PARTICLE          = "CLOUD"   -- Particle type for the egg body
local EGG_PARTICLE_COUNT    = 5         -- Particles per step for the egg body
local EGG_SPREAD            = 0.15      -- Tight cluster to look like a solid egg
local EGG_SPEED             = 0.01      -- Particle speed (low = stays together)

-- Egg trail particles (the wake behind the falling egg)
local TRAIL_PARTICLE        = "SMOKE"   -- Particle type for the trail
local TRAIL_PARTICLE_COUNT  = 3         -- Particles per step for the trail
local TRAIL_SPREAD          = 0.2       -- Trail spread
local TRAIL_SPEED           = 0.02      -- Trail particle speed

-- Impact VFX
local EXPLOSION_PARTICLES   = 2         -- EXPLOSION particle count on impact
local LAVA_PARTICLES        = 10        -- LAVA particle count on impact
local FLAME_PARTICLES       = 15        -- FLAME particle count on impact
local FLAME_SPREAD          = 1.0       -- FLAME particle spread
local LAVA_SPREAD           = 1.0       -- LAVA particle spread

-- Feather burst on impact (CHERRY_LEAVES — requires 1.20+)
local FEATHER_PARTICLE      = "CHERRY_LEAVES"  -- Particle type for feather burst
local FEATHER_COUNT         = 20        -- Number of feather particles per impact
local FEATHER_SPREAD        = 1.5       -- How wide the feathers scatter
local FEATHER_SPEED         = 0.15      -- How fast feathers fly outward
local FEATHER_WAVES         = 3         -- Number of feather burst waves after impact
local FEATHER_WAVE_INTERVAL = 4         -- Ticks between each feather wave

-- Impact damage & knockback
local IMPACT_DAMAGE         = 1         -- Damage dealt to nearby entities on impact
local IMPACT_RADIUS         = 4         -- Radius to search for entities to damage
local IMPACT_KNOCKUP        = 1.2       -- Upward velocity applied to damaged entities

-- Sound volumes & pitches
local AMBIENT_VOLUME        = 1.0
local AMBIENT_PITCH         = 1.5
local CLUCK_VOLUME          = 1.0
local CLUCK_PITCH_MIN       = 0.8
local CLUCK_PITCH_VARIANCE  = 0.4
local TRAIL_SOUND_VOLUME    = 0.5
local TRAIL_SOUND_PITCH_MIN = 1.2
local TRAIL_SOUND_PITCH_VAR = 0.6
local EXPLODE_VOLUME        = 1.0
local EXPLODE_PITCH         = 1.2
local DEATH_SOUND_VOLUME    = 1.0
local DEATH_SOUND_PITCH     = 0.6

-- Action bar
local ACTION_BAR_TEXT       = "&6&lEggXplosion!"
local ACTION_BAR_TICKS      = 60
local COOLDOWN_TEXT         = "EggXplosion on cooldown!"
local COOLDOWN_TEXT_TICKS   = 40
local DURABILITY_COST       = 0.05      -- % durability per use
local MIN_DURABILITY_PCT    = 0.02      -- 1% — EM considers item broken below this

------------------------------------------------------------

return {
    api_version = 1,

    on_right_click = function(context)

        local player = context.player
        local loc = player.current_location
        if em.location.is_protected(loc) or em.location.is_in_dungeon(loc) then return end
				
				-- Durability guard — EM treats ≤1% as broken
				if context.item:get_durability_percentage() <= MIN_DURABILITY_PCT then
						context.player:show_action_bar("&c&lThe Staff is broken!", 30)
						return
				end

        -- Cooldown check
        if not context.cooldowns:local_ready("chicken_rain") then
            player:show_action_bar(COOLDOWN_TEXT, COOLDOWN_TEXT_TICKS)
            return
        end

        if not context.item:use_durability_percentage(DURABILITY_COST, false) then return end
        context.event:cancel()
        context.cooldowns:set_local(COOLDOWN_TICKS, "chicken_rain")

        local loc = player.current_location
        local px, py, pz = loc.x, loc.y, loc.z

        player:show_action_bar(ACTION_BAR_TEXT, ACTION_BAR_TICKS)
        context.world:play_sound("ENTITY_CHICKEN_AMBIENT", px, py, pz, AMBIENT_VOLUME, AMBIENT_PITCH)

        -- Drop explosive eggs in a ring around the player
        for i = 1, EGG_COUNT do
            local angle = math.random() * math.pi * 2
            local dist = RING_MIN_RADIUS + math.random() * RING_WIDTH
            local sx = px + math.cos(angle) * dist
            local sz = pz + math.sin(angle) * dist
            local start_y = py + SPAWN_HEIGHT + math.random() * SPAWN_HEIGHT_VARIANCE

            -- Raycast straight down from spawn point to find the first solid block
            local hit = context.world:raycast(sx, start_y, sz, 0, -1, 0, MAX_FALL_SCAN)
            local end_y
            if hit and hit.hit_location then
                end_y = hit.hit_location.y
            else
                -- No block found within scan range, fall to player Y level
                end_y = py
            end

            local spawn_delay = i * SPAWN_STAGGER_TICKS

            context.scheduler:run_later(spawn_delay, function()
                -- Initial cluck as the egg appears
                context.world:play_sound("ENTITY_CHICKEN_AMBIENT", sx, start_y, sz,
                    CLUCK_VOLUME, CLUCK_PITCH_MIN + math.random() * CLUCK_PITCH_VARIANCE)

                -- Animate the falling egg with particles
                local tick_count = 0
                local fall_task = context.scheduler:run_repeating(0, FALL_INTERVAL, function()
                    tick_count = tick_count + 1
                    if tick_count > FALL_STEPS then return end

                    local progress = tick_count / FALL_STEPS
                    local current_y = start_y + (end_y - start_y) * progress

                    -- Egg body — tight white cloud cluster
                    context.world:spawn_particle(EGG_PARTICLE, sx, current_y, sz,
                        EGG_PARTICLE_COUNT, EGG_SPREAD, EGG_SPREAD, EGG_SPREAD, EGG_SPEED)

                    -- Smoke trail behind the egg (slightly above)
                    context.world:spawn_particle(TRAIL_PARTICLE, sx, current_y + 0.5, sz,
                        TRAIL_PARTICLE_COUNT, TRAIL_SPREAD, TRAIL_SPREAD, TRAIL_SPREAD, TRAIL_SPEED)

                    -- Panicked clucking every other step
                    if tick_count % 2 == 0 then
                        context.world:play_sound("ENTITY_CHICKEN_AMBIENT", sx, current_y, sz,
                            TRAIL_SOUND_VOLUME, TRAIL_SOUND_PITCH_MIN + math.random() * TRAIL_SOUND_PITCH_VAR)
                    end
                end)

                -- Impact when the egg reaches the ground
                local impact_delay = FALL_STEPS * FALL_INTERVAL + 1
                context.scheduler:run_later(impact_delay, function()
                    context.scheduler:cancel(fall_task)

                    -- Explosion VFX
                    context.world:spawn_particle("EXPLOSION", sx, end_y, sz,
                        EXPLOSION_PARTICLES, 0.5, 0.5, 0.5, 0.01)
                    context.world:spawn_particle("LAVA", sx, end_y, sz,
                        LAVA_PARTICLES, LAVA_SPREAD, 0.5, LAVA_SPREAD, 0.1)
                    context.world:spawn_particle("FLAME", sx, end_y, sz,
                        FLAME_PARTICLES, FLAME_SPREAD, 0.3, FLAME_SPREAD, 0.1)

                    -- Feather burst — cherry leaves scattering like feathers
                    context.world:spawn_particle(FEATHER_PARTICLE, sx, end_y + 0.5, sz,
                        FEATHER_COUNT, FEATHER_SPREAD, FEATHER_SPREAD, FEATHER_SPREAD, FEATHER_SPEED)

                    -- Additional feather waves that linger and drift
                    for wave = 1, FEATHER_WAVES do
                        context.scheduler:run_later(wave * FEATHER_WAVE_INTERVAL, function()
                            context.world:spawn_particle(FEATHER_PARTICLE, sx, end_y + 0.3, sz,
                                math.floor(FEATHER_COUNT / 2),
                                FEATHER_SPREAD * 1.5, FEATHER_SPREAD, FEATHER_SPREAD * 1.5,
                                FEATHER_SPEED * 0.5)
                        end)
                    end

                    -- Impact sounds
                    context.world:play_sound("ENTITY_GENERIC_EXPLODE", sx, end_y, sz,
                        EXPLODE_VOLUME, EXPLODE_PITCH)
                    context.world:play_sound("ENTITY_CHICKEN_DEATH", sx, end_y, sz,
                        DEATH_SOUND_VOLUME, DEATH_SOUND_PITCH)

                    -- Damage nearby entities
                    local entities = context.world:get_nearby_entities(sx, end_y, sz, IMPACT_RADIUS)
                    for _, entity in ipairs(entities) do
                        if entity:can_receive_hostile_effect(player.uuid) then
                            local at = entity.current_location
                            local distance2 = (at.x - sx)^2 + (at.y - end_y)^2 + (at.z - sz)^2
                            if distance2 <= IMPACT_RADIUS^2
                              and not em.location.is_protected(at) and not em.location.is_in_dungeon(at)
                              and context.action:damage_target(entity.uuid, IMPACT_DAMAGE) then
                                local path = entity:movement_path_status({world = player.world,
                                    x = at.x, y = at.y + IMPACT_KNOCKUP, z = at.z})
                                if path ~= "protected" and path ~= "unavailable" then
                                    entity:push(0, IMPACT_KNOCKUP, 0)
                                end
                            end
                        end
                    end
                end)
            end)
        end
    end,
}