-- ═══════════════════════════════════════════════════════════════
-- Brrrpack — Frost Ring Shield
-- When equipped, each hit taken has a low chance to unleash an
-- expanding ice-frost ring that slows and traps nearby entities
-- in powder snow.
-- ═══════════════════════════════════════════════════════════════

--------------------------------------------------------------
-- SETTINGS — tweak these to taste
--------------------------------------------------------------
local ACTIVATION_CHANCE     = 0.25          -- chance per hit
local COOLDOWN_TICKS        = 100           -- 5 s cooldown between procs

-- Wave shape
local WAVE_MAX_RADIUS       = 10            -- blocks outward
local WAVE_SPEED            = 1.5           -- blocks per tick-step
local WAVE_TICK_INTERVAL    = 2             -- ticks between ring expansions
local RING_POINTS           = 36            -- particle density around ring

-- Effects on hit targets
local SLOW_DURATION         = 100           -- 5 seconds (ticks)
local SLOW_AMPLIFIER        = 3             -- Slowness amplifier
local SNOW_BLOCK_DURATION   = 20           -- ticks powder snow stays

-- Head snow particle loop
local HEAD_SNOW_INTERVAL    = 10             -- ticks between head snow bursts
local HEAD_SNOW_DURATION    = 100           -- ticks the head snow lasts (match slow)

-- Sounds
local ACTIVATE_SOUND        = "BLOCK_GLASS_BREAK"
local ACTIVATE_PITCH        = 0.1
local ACTIVATE_VOLUME       = 0.3
local WAVE_SOUND            = "BLOCK_POWDER_SNOW_STEP"
local WAVE_SOUND_PITCH      = 1.2
local WAVE_SOUND_VOLUME     = 0.6
local HIT_SOUND             = "ENTITY_PLAYER_HURT_FREEZE"
local HIT_SOUND_PITCH       = 0.8
local HIT_SOUND_VOLUME      = 1.5

-- Particles
local RING_PARTICLE         = "SNOWFLAKE"
local RING_PARTICLE_COUNT   = 3
local RING_PARTICLE_SPEED   = 0.02
local FLOOR_PARTICLE        = "CLOUD"
local FLOOR_PARTICLE_COUNT  = 2
local HEAD_PARTICLE         = "SNOWFLAKE"
local HEAD_PARTICLE_COUNT   = 5
local HEAD_PARTICLE_SPREAD  = 0.3

-- Player feedback
local ACTIVATE_MESSAGE      = "&b&l❄ Frost-Wave Triggered! ❄"
local ACTIVATE_MSG_TICKS    = 40
local DURABILITY_COST     = 0.01      -- 1% durability per use
local MIN_DURABILITY_PCT  = 0.01      -- 1% — EM considers item broken below this

--------------------------------------------------------------
-- MOB HEIGHT LOOKUP — blocks of snow to stack at feet
-- Defaults to 2 for unknown mobs.
--------------------------------------------------------------
local MOB_HEIGHT_BLOCKS = {
  -- 2-block tall mobs
  ZOMBIE          = 2,
  SKELETON        = 2,
  CREEPER         = 2,
  DROWNED         = 2,
  HUSK            = 2,
  STRAY           = 2,
  WITHER_SKELETON = 3,  -- tall boi
  ENDERMAN        = 3,
  IRON_GOLEM      = 3,
  RAVAGER         = 2,
  PIGLIN          = 2,
  PIGLIN_BRUTE    = 2,
  ZOMBIFIED_PIGLIN= 2,
  VINDICATOR      = 2,
  PILLAGER        = 2,
  EVOKER          = 2,
  WITCH           = 2,
  ILLUSIONER      = 2,
  BLAZE           = 2,
  WARDEN          = 3,

  -- 1-block tall / short mobs
  ZOMBIE_VILLAGER = 2,
  SPIDER          = 1,
  CAVE_SPIDER     = 1,
  SILVERFISH      = 1,
  ENDERMITE       = 1,
  SLIME           = 1,
  MAGMA_CUBE      = 1,
  RABBIT          = 1,
  CHICKEN         = 1,
  BAT             = 1,
  VEX             = 1,
  PHANTOM         = 1,
  BABY_ZOMBIE     = 1,  -- won't match (entity_type is ZOMBIE)
  WOLF            = 1,
  FOX             = 1,
  CAT             = 1,
  OCELOT          = 1,
  PIG             = 1,
  SHEEP           = 1,
  COW             = 1,
}
local DEFAULT_HEIGHT_BLOCKS = 2

--------------------------------------------------------------
-- HELPER — get snow column height for a mob
--------------------------------------------------------------
local function get_snow_height(entity_type)
  return MOB_HEIGHT_BLOCKS[entity_type] or DEFAULT_HEIGHT_BLOCKS
end

--------------------------------------------------------------
-- SCRIPT
--------------------------------------------------------------
return {
  api_version = 1,

  on_take_damage = function(context)
    local player = context.player
    if not player or not context.item or em.location.is_protected(player.current_location) then return end
    context.state.head_tasks = {}
    -- Don't stack waves
    if context.state.wave_active then return end
		-- Durability guard — EM treats ≤1% as broken
		if context.item:get_durability_percentage() <= MIN_DURABILITY_PCT then
				context.player:show_action_bar("&c&lBackpack is broken!", 30)
				return
		end
    -- Respect cooldown
    if not context.cooldowns:local_ready("frost_wave") then return end
    -- RNG roll
    if math.random() > ACTIVATION_CHANCE then return end

    -- ──── ACTIVATE ────
		-- Deduct 1% durability (can_break = false, item won't be destroyed)
		if not context.item:use_durability_percentage(DURABILITY_COST, false) then return end

    context.state.wave_active  = true
    context.cooldowns:set_local(COOLDOWN_TICKS, "frost_wave")
    context.state.hit_entities = {}
    context.state.snow_blocks  = {}

    local player   = context.player
    local loc      = player.current_location
    local ox, oy, oz = loc.x, loc.y, loc.z

    -- Activation feedback
    player:show_action_bar(ACTIVATE_MESSAGE, ACTIVATE_MSG_TICKS)
    context.world:play_sound(
      ACTIVATE_SOUND, ox, oy, oz,
      ACTIVATE_VOLUME, ACTIVATE_PITCH)

    -- Initial burst at player feet
    context.world:spawn_particle(
      "CLOUD", ox, oy + 0.2, oz, 15, 0.5, 0.1, 0.5, 0.05)

    -- ── Expanding ring ──
    local current_radius = 1

    local task_id = context.scheduler:run_repeating(
      0, WAVE_TICK_INTERVAL, function()

      -- Done?
      if current_radius > WAVE_MAX_RADIUS then
        context.state.wave_active = false
        if context.state.wave_task then
          context.scheduler:cancel(context.state.wave_task)
        end
        return
      end

      -- ── Draw ring particles ──
      local step = (2 * math.pi) / RING_POINTS
      for i = 0, RING_POINTS - 1 do
        local a  = i * step
        local px = ox + math.cos(a) * current_radius
        local pz = oz + math.sin(a) * current_radius
        local gy = context.world:get_highest_block_y(
                     math.floor(px), math.floor(pz))

        -- Frost snowflake ring
        context.world:spawn_particle(
          RING_PARTICLE, px, gy + 1.0, pz,
          RING_PARTICLE_COUNT, 0.15, 0.1, 0.15, RING_PARTICLE_SPEED)

        -- Low mist
        context.world:spawn_particle(
          FLOOR_PARTICLE, px, gy + 0.4, pz,
          FLOOR_PARTICLE_COUNT, 0.2, 0.05, 0.2, 0.01)
      end

      -- Advancing wave sound
      context.world:play_sound(
        WAVE_SOUND, ox, oy, oz,
        WAVE_SOUND_VOLUME, WAVE_SOUND_PITCH)

      -- ── Hit entities in the ring band ──
      local ents = context.world:get_nearby_entities(
        ox, oy, oz, current_radius + 2)

      for _, ent in ipairs(ents) do
        if ent.uuid ~= player.uuid
           and not context.state.hit_entities[ent.uuid]
           and ent:can_receive_hostile_effect(player.uuid)
           and not em.location.is_protected(ent.current_location) then

          local el = ent.current_location
          local dx = el.x - ox
          local dz = el.z - oz
          local dist = math.sqrt(dx * dx + dz * dz)

          if dist >= (current_radius - WAVE_SPEED)
             and dist <= (current_radius + 1) then

            context.state.hit_entities[ent.uuid] = true

            -- Slowness
            ent:add_potion_effect("SLOWNESS", SLOW_DURATION, SLOW_AMPLIFIER)

            -- Freeze sound on target
            context.world:play_sound(
              HIT_SOUND, el.x, el.y, el.z,
              HIT_SOUND_VOLUME, HIT_SOUND_PITCH)

            -- ── Powder snow column at feet ──
            local fx = math.floor(el.x)
            local fy = math.floor(el.y)
            local fz = math.floor(el.z)
            local snow_height = get_snow_height(ent.entity_type)

            for yOff = 0, snow_height - 1 do
              local by = fy + yOff
              local key = fx .. "," .. by .. "," .. fz

              if not context.state.snow_blocks[key] then
                local cur = context.world:get_block_at(fx, by, fz)
                if cur == "air" or cur == "cave_air" then
                  local location = {world = player.world, x = fx, y = by, z = fz}
                  if not em.location.is_protected(location)
                    and context.action:temporary_block(fx, by, fz, "minecraft:powder_snow", SNOW_BLOCK_DURATION) then
                    context.state.snow_blocks[key] = true
                  end
                end
              end
            end

            -- Snowflake burst on target body
            context.world:spawn_particle(
              "SNOWFLAKE", el.x, el.y + 1.0, el.z,
              8, 0.3, 0.3, 0.3, 0.05)

            -- ── Lingering snow above head for full slow duration ──
            local ent_uuid = ent.uuid
            local head_task = context.scheduler:run_repeating(
              0, HEAD_SNOW_INTERVAL, function()
              if not ent:can_receive_hostile_effect(player.uuid) then
                local handle = context.state.head_tasks[ent_uuid]
                if handle then context.scheduler:cancel(handle) end
                context.state.head_tasks[ent_uuid] = nil
                return
              end
              local eloc = ent.current_location
              local head_y = eloc.y + snow_height + 0.3
              context.world:spawn_particle(HEAD_PARTICLE, eloc.x, head_y, eloc.z,
                HEAD_PARTICLE_COUNT, HEAD_PARTICLE_SPREAD, 0.1, HEAD_PARTICLE_SPREAD, 0.01)
              context.world:spawn_particle("SNOWFLAKE", eloc.x, head_y - 0.3, eloc.z,
                2, 0.2, 0.4, 0.2, 0.005)
            end)

            -- Store task and auto-cancel after slow wears off
            context.state.head_tasks[ent_uuid] = head_task
            context.scheduler:run_later(HEAD_SNOW_DURATION, function()
              if context.state.head_tasks[ent_uuid] then
                context.scheduler:cancel(context.state.head_tasks[ent_uuid])
                context.state.head_tasks[ent_uuid] = nil
              end
            end)
          end
        end
      end

      current_radius = current_radius + WAVE_SPEED
    end)

    context.state.wave_task = task_id

  end,
}