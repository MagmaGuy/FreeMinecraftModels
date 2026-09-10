------------------------------------------------------------------------
-- Chaos Explosion Sword
-- On hit, random chance to trigger a tiered explosion (minor/normal/big).
-- Drains 1 % durability per trigger. Won't fire if durability < 2 %.
-- Scans for typical player-placed items (beds, chests, doors …) within
-- a configurable radius — if 2+ are found the power is suppressed to
-- prevent griefing.
-- Destroys soft blocks (dirt, sand, flowers …) but leaves hard blocks.
-- Damages, pushes, and ignites mobs.
------------------------------------------------------------------------

------------------------------
-- ★ ALL CONFIGURABLE SETTINGS
------------------------------

-- Chance (0.0 – 1.0) that ANY explosion triggers per hit
local TRIGGER_CHANCE           = 0.05

-- Tier weights (relative, higher = more common)
local WEIGHT_MINOR             = 60
local WEIGHT_NORMAL            = 30
local WEIGHT_BIG               = 10

-- Explosion radii per tier
local RADIUS_MINOR             = 2
local RADIUS_NORMAL            = 4
local RADIUS_BIG               = 6

-- Damage dealt to mobs per tier (players receive NO damage)
local DAMAGE_MINOR             = 1.0
local DAMAGE_NORMAL            = 1.0
local DAMAGE_BIG               = 1.0

-- Knockback strength per tier (Minecraft velocity, 1.0 = very strong)
local PUSH_MINOR               = 2.0
local PUSH_NORMAL              = 4.0
local PUSH_BIG                 = 6.0

-- Upward push component for mobs (same for all tiers)
local PUSH_UP                  = 0.95


-- Fire duration per tier in ticks (20 = 1 second)
local FIRE_TICKS_MINOR         = 40
local FIRE_TICKS_NORMAL        = 80
local FIRE_TICKS_BIG           = 140

-- Durability cost per trigger (fraction of max, 0.01 = 1 %)
local DURABILITY_COST          = 0.01

-- Minimum durability to allow the power (fraction, 0.02 = 2 %)
local DURABILITY_MIN           = 0.02

-- Anti-grief: radius to scan for player-placed indicators
local GRIEF_SCAN_RADIUS        = 15

-- Number of detected player-placed blocks that blocks the power
local GRIEF_THRESHOLD          = 2

-- Cooldown between triggers in ticks (20 = 1 s)
local COOLDOWN_TICKS           = 20

-- Particle counts (tune for performance)
local PARTICLES_MAIN_EXPLOSION = 40
local PARTICLES_SMOKE          = 20
local PARTICLES_FLAME          = 20
local PARTICLES_COLOR_BURST    = 15

-- How many ticks to repeat push (to overcome melee knockback immunity)
local PUSH_REPEAT_TICKS        = 3

-- Action bar messages
local MSG_GRIEF_BLOCKED        = "&c⚠ Explosion suppressed — player area detected"
local MSG_LOW_DURABILITY       = "&c⚠ Sword too damaged to trigger explosion"

-- Console message cooldown (ticks). 10 seconds = 200 ticks.
local REGION_LOG_COOLDOWN_TICKS = 200

------------------------------
-- INTERNAL LOOK-UP TABLES
------------------------------

local SOFT_BLOCKS = {
  dirt = true, grass_block = true, coarse_dirt = true, rooted_dirt = true,
  podzol = true, mycelium = true, sand = true, red_sand = true,
  gravel = true, clay = true, soul_sand = true, soul_soil = true,
  farmland = true, dirt_path = true, mud = true, muddy_mangrove_roots = true,
  snow = true, snow_block = true, moss_block = true,
  short_grass = true, tall_grass = true, fern = true, large_fern = true,
  dead_bush = true, seagrass = true, tall_seagrass = true,
  vine = true, sugar_cane = true, bamboo = true, kelp = true,
  kelp_plant = true, sweet_berry_bush = true, cave_vines = true,
  cave_vines_plant = true, glow_lichen = true, hanging_roots = true,
  moss_carpet = true, pink_petals = true,
  brown_mushroom = true, red_mushroom = true,
  dandelion = true, poppy = true, blue_orchid = true, allium = true,
  azure_bluet = true, red_tulip = true, orange_tulip = true,
  white_tulip = true, pink_tulip = true, oxeye_daisy = true,
  cornflower = true, lily_of_the_valley = true, sunflower = true,
  lilac = true, rose_bush = true, peony = true, torchflower = true,
  pitcher_plant = true, spore_blossom = true,
  cobweb = true, fire = true, soul_fire = true,
}

local PLAYER_PLACED = {
  white_bed = true, orange_bed = true, magenta_bed = true,
  light_blue_bed = true, yellow_bed = true, lime_bed = true,
  pink_bed = true, gray_bed = true, light_gray_bed = true,
  cyan_bed = true, purple_bed = true, blue_bed = true,
  brown_bed = true, green_bed = true, red_bed = true, black_bed = true,
  chest = true, trapped_chest = true, ender_chest = true,
  barrel = true, shulker_box = true,
  white_shulker_box = true, orange_shulker_box = true,
  magenta_shulker_box = true, light_blue_shulker_box = true,
  yellow_shulker_box = true, lime_shulker_box = true,
  pink_shulker_box = true, gray_shulker_box = true,
  light_gray_shulker_box = true, cyan_shulker_box = true,
  purple_shulker_box = true, blue_shulker_box = true,
  brown_shulker_box = true, green_shulker_box = true,
  red_shulker_box = true, black_shulker_box = true,
  oak_door = true, spruce_door = true, birch_door = true,
  jungle_door = true, acacia_door = true, dark_oak_door = true,
  mangrove_door = true, cherry_door = true, bamboo_door = true,
  crimson_door = true, warped_door = true, iron_door = true,
  crafting_table = true, furnace = true, blast_furnace = true,
  smoker = true, anvil = true, chipped_anvil = true, damaged_anvil = true,
  enchanting_table = true, brewing_stand = true, beacon = true,
  lectern = true, smithing_table = true, loom = true,
  cartography_table = true, fletching_table = true, grindstone = true,
  stonecutter = true, composter = true, cauldron = true,
  oak_sign = true, oak_wall_sign = true,
  spruce_sign = true, birch_sign = true, jungle_sign = true,
  acacia_sign = true, dark_oak_sign = true,
  torch = true, wall_torch = true, redstone_torch = true,
  lantern = true, soul_lantern = true,
}

------------------------------
-- HELPER FUNCTIONS
------------------------------

local function pick_tier()
  local total = WEIGHT_MINOR + WEIGHT_NORMAL + WEIGHT_BIG
  local roll  = math.random(1, total)
  if roll <= WEIGHT_MINOR then
    return "minor"
  elseif roll <= WEIGHT_MINOR + WEIGHT_NORMAL then
    return "normal"
  else
    return "big"
  end
end

local function tier_props(tier)
  if tier == "minor" then
    return RADIUS_MINOR, DAMAGE_MINOR, PUSH_MINOR, FIRE_TICKS_MINOR
  elseif tier == "normal" then
    return RADIUS_NORMAL, DAMAGE_NORMAL, PUSH_NORMAL, FIRE_TICKS_NORMAL
  else
    return RADIUS_BIG, DAMAGE_BIG, PUSH_BIG, FIRE_TICKS_BIG
  end
end

local function is_player_area(world, cx, cy, cz)
  local count = 0
  local r = GRIEF_SCAN_RADIUS
  for dx = -r, r do
    for dz = -r, r do
      if dx * dx + dz * dz <= r * r then
        for dy = -6, 6 do
          local bx = math.floor(cx) + dx
          local by = math.floor(cy) + dy
          local bz = math.floor(cz) + dz
          local block = world:get_block_at(bx, by, bz)
          if block and PLAYER_PLACED[block] then
            count = count + 1
            if count >= GRIEF_THRESHOLD then
              return true
            end
          end
        end
      end
    end
  end
  return false
end

local function spawn_color_bursts(world, ex, ey, ez, radius)
  local spread = radius * 0.35
  world:spawn_particle("FIREWORK", ex, ey + 0.5, ez,
    PARTICLES_COLOR_BURST * 2, spread, spread, spread, 0.15)
  world:spawn_particle("ENCHANT", ex, ey + 0.8, ez,
    PARTICLES_COLOR_BURST, spread * 0.8, spread * 0.6, spread * 0.8, 0.8)
  world:spawn_particle("TOTEM_OF_UNDYING", ex, ey + 0.3, ez,
    PARTICLES_COLOR_BURST, spread * 0.5, spread * 0.4, spread * 0.5, 0.6)
end

local function destroy_soft_blocks(context, cx, cy, cz, radius)
  local world = context.world
  local r = math.ceil(radius)
  for dx = -r, r do
    for dy = -r, r do
      for dz = -r, r do
        if dx*dx + dy*dy + dz*dz <= radius * radius then
          local bx = math.floor(cx) + dx
          local by = math.floor(cy) + dy
          local bz = math.floor(cz) + dz
          local block = world:get_block_at(bx, by, bz)
          if block and SOFT_BLOCKS[block] then
            local at = {world = context.player.world, x = bx, y = by, z = bz}
            if not em.location.is_protected(at) and not em.location.is_in_dungeon(at) then
              context.action:break_block(bx, by, bz, block)
            end
          end
        end
      end
    end
  end
end

local function blast_entities(context, world, ex, ey, ez, radius, damage, push_str, fire_ticks, player)
  local entities = world:get_nearby_entities(ex, ey, ez, radius + 1)
  if not entities then return end

  for _, ent in ipairs(entities) do
    if ent:can_receive_hostile_effect(player.uuid) then
      local eloc = ent.current_location
      local dx, dz = eloc.x - ex, eloc.z - ez
      local dist_h = math.sqrt(dx * dx + dz * dz)
      local dist = math.sqrt(dx * dx + (eloc.y - ey)^2 + dz * dz)
      if dist <= radius and not em.location.is_protected(eloc)
        and not em.location.is_in_dungeon(eloc) then
        local falloff = 1 - dist / radius
        if context.action:damage_target(ent.uuid, damage * (0.3 + 0.7 * falloff)) then
          local angle = math.random() * 2 * math.pi
          local nx = dist_h < .1 and math.cos(angle) or dx / dist_h
          local nz = dist_h < .1 and math.sin(angle) or dz / dist_h
          local strength = push_str * (.4 + .6 * falloff)
          local vx, vy, vz = nx * strength, PUSH_UP * (.5 + .5 * falloff), nz * strength
          local function push()
            if not ent:can_receive_hostile_effect(player.uuid) then return end
            local at = ent.current_location
            if em.location.is_protected(at) or em.location.is_in_dungeon(at) then return end
            local path = ent:movement_path_status({world = player.world,
              x = at.x + vx, y = at.y + vy, z = at.z + vz})
            if path ~= "protected" and path ~= "unavailable" then ent:push(vx, vy, vz) end
          end
          push()
          for tick = 1, PUSH_REPEAT_TICKS do context.scheduler:run_later(tick, push) end
          local fx, fy, fz = math.floor(eloc.x), math.floor(eloc.y), math.floor(eloc.z)
          for offset = 0, 1 do
            local at = {world = player.world, x = fx, y = fy + offset, z = fz}
            local block = world:get_block_at(fx, fy + offset, fz)
            if (offset == 0 or block == "air" or block == "cave_air")
              and not em.location.is_protected(at) and not em.location.is_in_dungeon(at) then
              context.action:temporary_block(fx, fy + offset, fz, "minecraft:fire", fire_ticks)
            end
          end
        end
      end
    end
  end
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

------------------------------
-- POWER DEFINITION
------------------------------

return {
  api_version = 1,

  on_attack_entity = function(context)
    local player = context.player
    if not player then return end

    -- Region / dungeon gate — block the power in protected areas.
    local block_reason = get_block_reason(player)
    if block_reason then
      -- Throttled console warning (once per 10 seconds per player).
      if context.cooldowns:check_local("region_block_log", REGION_LOG_COOLDOWN_TICKS) then
        if block_reason == "protected" then
          context.log:warn("[Chaos Explosion Sword] Blocked for player " .. player.name
            .. " — inside a WorldGuard/GriefPrevention protected region.")
        else
          context.log:warn("[Chaos Explosion Sword] Blocked for player " .. player.name
            .. " — inside an EliteMobs dungeon.")
        end
      end
      return
    end

    local target = context.target
    if not target or not target:can_receive_hostile_effect(player.uuid)
      or em.location.is_protected(target.current_location)
      or em.location.is_in_dungeon(target.current_location) then return end

    -- Cooldown
    if not context.cooldowns:local_ready("chaos_explosion") then
      return
    end

    -- Roll the dice
    if math.random() > TRIGGER_CHANCE then return end

    local item  = context.item
    local world = context.world

    -- Durability gate
    if item:get_durability_percentage() < DURABILITY_MIN then
      player:show_action_bar(MSG_LOW_DURABILITY, 40)
      return
    end

    local tloc = target.current_location
    local ex, ey, ez = tloc.x, tloc.y, tloc.z

    -- Anti-grief check at explosion center
    if is_player_area(world, ex, ey, ez) then
      player:show_action_bar(MSG_GRIEF_BLOCKED, 40)
      return
    end

    -- Pick tier & properties
    local tier = pick_tier()
    local radius, damage, push_str, fire_ticks = tier_props(tier)

    -- Drain durability
    if not item:use_durability_percentage(DURABILITY_COST, false) then return end
    context.cooldowns:set_local(COOLDOWN_TICKS, "chaos_explosion")

    -- === VFX ===
    world:spawn_particle("EXPLOSION_EMITTER", ex, ey + 0.5, ez,
      1, 0, 0, 0, 0)
    world:spawn_particle("EXPLOSION", ex, ey + 0.5, ez,
      PARTICLES_MAIN_EXPLOSION, radius * 0.4, radius * 0.3, radius * 0.4, 0.05)
    world:spawn_particle("CAMPFIRE_COSY_SMOKE", ex, ey + 1.0, ez,
      PARTICLES_SMOKE, radius * 0.3, 0.6, radius * 0.3, 0.02)
    world:spawn_particle("FLAME", ex, ey + 0.3, ez,
      PARTICLES_FLAME, radius * 0.35, 0.4, radius * 0.35, 0.08)
    world:spawn_particle("LAVA", ex, ey + 0.5, ez,
      PARTICLES_FLAME, radius * 0.3, 0.3, radius * 0.3, 0)
    spawn_color_bursts(world, ex, ey, ez, radius)

    -- === SFX ===
    world:play_sound("ENTITY_GENERIC_EXPLODE", ex, ey, ez, 1.5, 0.8)
    world:play_sound("ENTITY_GENERIC_EXPLODE", ex, ey, ez, 1.0, 1.3)

    -- Clear terrain before placing owned fire, so this action does not destroy
    -- its own replacements and prevent their conditional restoration.
    destroy_soft_blocks(context, ex, ey, ez, radius)

    blast_entities(context, world, ex, ey, ez, radius, damage, push_str, fire_ticks, player)

    -- === Action bar flair ===
    local tag
    if tier == "minor" then
      tag = "&e✦ Minor Explosion ✦"
    elseif tier == "normal" then
      tag = "&6✦✦ Explosion ✦✦"
    else
      tag = "&c&k!&r &4✦✦✦ BIG EXPLOSION ✦✦✦ &c&k!&r"
    end
    player:show_action_bar(tag, 40)
  end,
}
