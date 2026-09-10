-- =========================================================================
-- Slam — on-hit AoE shockwave
-- Triggers when the wielder strikes a mob. Pushes nearby mobs away,
-- shrinks them, and slows them. Players are never affected.
-- =========================================================================

-- ---- Tunables -----------------------------------------------------------
local TRIGGER_CHANCE        = 0.05   -- 0.05 for production, 0.99 for testing
local PUSH_RADIUS           = 3.0    -- blocks; mobs inside are launched
local PARTICLE_RADIUS       = 5.0    -- blocks; visual ring max radius
local PUSH_STRENGTH         = 2.2    -- horizontal launch power
local PUSH_LIFT             = 0.45   -- small upward kick

local SHRINK_SCALE          = 0.5    -- 0.5 = half size; 1.0 = no change (1.20.5+ only)
local SLOW_DURATION_TICKS   = 100    -- ticks (20 ticks/sec)
local SLOW_AMPLIFIER        = 2      -- Slowness III

-- Real vanilla item durability (the damage bar on the mace).
-- Values are fractions: 0.02 = 2%, 0.03 = 3%.
local DURABILITY_COST        = 0.02  -- consumed per successful trigger
local DURABILITY_MIN_TO_FIRE = 0.03  -- below this, the power refuses to fire

-- Particle ring animation
local RING_STEPS             = 5     -- expansion frames
local RING_STEP_DELAY_TICKS  = 2     -- ticks between frames

-- Particle density & coverage (the two knobs for "how many particles")
--   RING_POINTS_PER_BLOCK: candidate positions per block of ring circumference.
--     Higher = denser ring. Lower = sparser. 1..6 is a sane range.
--   RING_COVERAGE: fraction of those positions that actually emit a particle.
--     0.0 = none, 1.0 = all of them. Combine with density for fine control.
--   RING_PARTICLES_PER_POINT: count argument passed to spawn_particle at each
--     emitted position. Usually 1 is plenty; bump to 2-3 for a thicker puff.
--   RING_MIN_POINTS: floor so tiny rings still look like a ring, not a dot.
local RING_POINTS_PER_BLOCK  = 2
local RING_COVERAGE          = 0.25
local RING_PARTICLES_PER_POINT = 1
local RING_MIN_POINTS        = 6

-- Sounds layered to evoke an earthy mace slam
-- Format: { sound_id, volume, pitch }
local SLAM_SOUNDS = {
  { "minecraft:entity.warden.sonic_boom",   0.6, 0.8 },
  { "minecraft:entity.generic.explode",     0.8, 0.7 },
  { "minecraft:block.anvil.land",           0.9, 0.6 },
  { "minecraft:entity.iron_golem.attack",   1.0, 0.8 },
  { "minecraft:block.gravel.break",         1.0, 0.7 },
  { "minecraft:entity.ravager.stunned",     0.7, 0.9 },
}

-- Dust ring particles. Layer two for a thicker grey/dust look.
-- Must be exact Bukkit Particle enum names.
local DUST_PARTICLE_PRIMARY   = "POOF"        -- grey explosion puff
local DUST_PARTICLE_SECONDARY = "LARGE_SMOKE" -- thickening haze

-- Console message cooldown (ticks). 10 seconds = 200 ticks.
local REGION_LOG_COOLDOWN_TICKS = 200
-- =========================================================================

local function chance(p)
  return math.random() < p
end

local function build_player_uuid_set(world, x, y, z, radius)
  local set = {}
  local players = world:get_nearby_players(x, y, z, radius)
  if players then
    for _, p in ipairs(players) do
      if p and p.uuid then set[p.uuid] = true end
    end
  end
  return set
end

local function play_slam_sounds(world, x, y, z)
  for _, s in ipairs(SLAM_SOUNDS) do
    world:play_sound(s[1], x, y, z, s[2], s[3])
  end
end

local function spawn_dust_ring(world, cx, cy, cz, radius)
  local circumference = 2 * math.pi * radius
  local points = math.max(RING_MIN_POINTS, math.floor(circumference * RING_POINTS_PER_BLOCK))
  for i = 0, points - 1 do
    -- Coverage gate: skip this candidate position with probability (1 - coverage)
    if math.random() < RING_COVERAGE then
      local angle = (i / points) * math.pi * 2
      local px = cx + math.cos(angle) * radius
      local pz = cz + math.sin(angle) * radius
      world:spawn_particle(DUST_PARTICLE_PRIMARY,   px, cy + 0.1, pz, RING_PARTICLES_PER_POINT, 0.05, 0.05, 0.05, 0)
      world:spawn_particle(DUST_PARTICLE_SECONDARY, px, cy + 0.1, pz, RING_PARTICLES_PER_POINT, 0.05, 0.05, 0.05, 0)
    end
  end
end

local function animate_expanding_ring(context, cx, cy, cz)
  for step = 1, RING_STEPS do
    local r = (step / RING_STEPS) * PARTICLE_RADIUS
    context.scheduler:run_later(step * RING_STEP_DELAY_TICKS, function()
      spawn_dust_ring(context.world, cx, cy, cz, r)
    end)
  end
end

-- Apply one effect on an entity, swallowing errors so a missing
-- method on an exotic entity type doesn't break the whole AoE loop.
local function safe_call(fn)
  local ok, err = pcall(fn)
  return ok, err
end

--- Returns "protected", "dungeon", or nil.
--- Checks the player's location against WorldGuard / GriefPrevention
--- regions and EliteMobs dungeons.
local function get_block_reason(player)
  local loc = player.current_location
  if not loc then return nil end
  if em.location.is_protected(loc) then return "protected" end
  if em.location.is_in_dungeon(loc) then return "dungeon" end
  return nil
end

return {
  api_version = 1,

  on_attack_entity = function(context)
    local player = context.player
    local item   = context.item
    if not player or not item then return end

    -- Region / dungeon gate — block the power in protected areas.
    local block_reason = get_block_reason(player)
    if block_reason then
      -- Throttled console warning (once per 10 seconds per player).
      if context.cooldowns:check_local("region_block_log", REGION_LOG_COOLDOWN_TICKS) then
        if block_reason == "protected" then
          context.log:warn("[Slam] Blocked for player " .. player.name
            .. " — inside a WorldGuard/GriefPrevention protected region.")
        else
          context.log:warn("[Slam] Blocked for player " .. player.name
            .. " — inside an EliteMobs dungeon.")
        end
      end
      return
    end

    -- Don't trigger when striking the wielder (defensive guard)
    local victim = context.target
    if not victim or not victim:can_receive_hostile_effect(player.uuid) then return end

    -- Roll the trigger chance
    if not chance(TRIGGER_CHANCE) then return end

    -- Durability gate (vanilla item damage bar)
    local dura_pct = item:get_durability_percentage()
    if dura_pct and dura_pct < DURABILITY_MIN_TO_FIRE then
      player:show_action_bar("§7The mace feels too brittle to slam...", 30)
      return
    end

    -- Resolve player position
    local loc = player.current_location
    if not loc then return end
    local cx, cy, cz = loc.x, loc.y, loc.z
    local world = context.world

    -- Charge the captured source slot before starting optional effects.
    if not item:use_durability_percentage(DURABILITY_COST, false) then return end

    -- Announce the trigger
    player:show_action_bar("§6§lSlam triggered!", 30)

    -- Build the player exclusion set so we never affect humans
    local excluded = build_player_uuid_set(world, cx, cy, cz, PUSH_RADIUS + 1)

    -- Apply effects to mobs in range
    local nearby = world:get_nearby_entities(cx, cy, cz, PUSH_RADIUS)
    if nearby then
      for _, ent in ipairs(nearby) do
        if ent and ent.uuid and not excluded[ent.uuid]
          and ent:can_receive_hostile_effect(player.uuid)
          and not em.location.is_protected(ent.current_location)
          and context.action:temporary_scale(ent.uuid, SHRINK_SCALE) then
          local el = ent.current_location
          if el then
            -- Outward push vector from player to entity
            local dx = el.x - cx
            local dz = el.z - cz
            local dist = math.sqrt(dx * dx + dz * dz)
            if dist < 0.0001 then dx, dz, dist = 1, 0, 1 end
            local nx = (dx / dist) * PUSH_STRENGTH
            local nz = (dz / dist) * PUSH_STRENGTH

            -- Knockback
            safe_call(function() ent:push(nx, PUSH_LIFT, nz) end)

            -- Slow
            safe_call(function()
              ent:add_potion_effect("SLOWNESS", SLOW_DURATION_TICKS, SLOW_AMPLIFIER)
            end)
          end
        end
      end
    end

    -- FX: layered slam sounds at the player's feet
    play_slam_sounds(world, cx, cy, cz)

    -- FX: expanding dust ring from the feet outward
    animate_expanding_ring(context, cx, cy, cz)

  end,
}