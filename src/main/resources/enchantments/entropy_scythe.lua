--------------------------------------------------------------------------------
-- Cyclone Tiller — Hoe Power
-- Shift+Left-click to spin the player and launch an expanding tornado that
-- damages and launches nearby entities, and tills land in its path.
--
-- Sources:
--   Wiki: global/lua_scripting_engine       (world, scheduler, zones, cooldowns)
--   Wiki: FreeMinecraftModels/lua_prop_api  (item hooks, durability, etc.)
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
-- SETTINGS
--------------------------------------------------------------------------------
local COOLDOWN_TICKS         = 1200       -- cooldown in ticks
local DURABILITY_COST        = 0.03     -- 3% of max durability
local MIN_DURABILITY_PCT     = 0.05     -- need at least 5% to fire
local SPIN_DURATION_TICKS    = 20       -- 1 second spin
local TORNADO_DURATION_TICKS = 40       -- 2 seconds to expand
local TORNADO_MAX_RADIUS     = 10       -- max expansion radius (blocks)
local TILL_RADIUS            = 3        -- radius of tilling around player
local DAMAGE_AMOUNT          = 2.0      -- 1 heart = 2 HP
local LAUNCH_UP              = 0.7      -- upward push strength
local LAUNCH_OUT             = 1.2      -- outward push strength
local GRIEF_SCAN_RADIUS      = 5        -- radius to scan for player structures
local GRIEF_BLOCK_THRESHOLD  = 3        -- need more than 3 structure blocks to block

-- Console message cooldown (ticks). 10 seconds = 200 ticks.
local REGION_LOG_COOLDOWN_TICKS = 200

-- Particle counts
local TILL_PARTICLE_COUNT      = 1      -- cloud puff when a block is tilled
local SPIN_PARTICLE_COUNT      = 1      -- swirl cloud per tick during spin
local TORNADO_CLOUD_COUNT      = 1      -- cloud per ring point each tick
local TORNADO_SWEEP_COUNT      = 1      -- sweep_attack at cardinal points
local TORNADO_RING_MAX_POINTS  = 16     -- max particle points around the ring

-- Blocks that count as player structures (grief protection)
local PLAYER_STRUCTURE_BLOCKS = {
    "white_bed", "orange_bed", "magenta_bed", "light_blue_bed",
    "yellow_bed", "lime_bed", "pink_bed", "gray_bed",
    "light_gray_bed", "cyan_bed", "purple_bed", "blue_bed",
    "brown_bed", "green_bed", "red_bed", "black_bed",
    "crafting_table", "furnace", "blast_furnace", "smoker",
    "chest", "trapped_chest", "barrel", "anvil",
    "chipped_anvil", "damaged_anvil", "enchanting_table",
    "brewing_stand", "cauldron", "lectern", "loom",
    "cartography_table", "fletching_table", "smithing_table",
    "stonecutter", "grindstone", "composter", "bee_nest",
    "beehive", "campfire", "soul_campfire", "respawn_anchor",
    "jukebox", "note_block", "bell", "ladder",
    "scaffolding", "torch", "wall_torch", "soul_torch",
    "soul_wall_torch", "lantern", "soul_lantern",
    "oak_door", "spruce_door", "birch_door", "jungle_door",
    "acacia_door", "dark_oak_door", "mangrove_door",
    "cherry_door", "bamboo_door", "crimson_door", "warped_door",
    "iron_door", "oak_fence_gate", "spruce_fence_gate",
    "birch_fence_gate", "jungle_fence_gate", "acacia_fence_gate",
    "dark_oak_fence_gate", "mangrove_fence_gate",
    "cherry_fence_gate", "bamboo_fence_gate",
    "crimson_fence_gate", "warped_fence_gate",
    "oak_sign", "spruce_sign", "birch_sign", "jungle_sign",
    "acacia_sign", "dark_oak_sign", "mangrove_sign",
    "cherry_sign", "bamboo_sign", "crimson_sign", "warped_sign",
    "oak_wall_sign", "spruce_wall_sign", "birch_wall_sign",
}

-- Blocks that can be tilled into farmland
local TILLABLE_BLOCKS = {
    ["grass_block"] = true,
    ["dirt"] = true,
    ["dirt_path"] = true,
    ["coarse_dirt"] = true,
    ["rooted_dirt"] = true,
}

--------------------------------------------------------------------------------
-- LOOKUP SETS
--------------------------------------------------------------------------------
local STRUCTURE_SET = {}
for _, b in ipairs(PLAYER_STRUCTURE_BLOCKS) do STRUCTURE_SET[b] = true end

--------------------------------------------------------------------------------
-- HELPER: Check for player structures nearby
--------------------------------------------------------------------------------
local function has_player_structures_nearby(world, cx, cy, cz, radius, threshold)
    local count = 0
    for x = cx - radius, cx + radius do
        for y = cy - radius, cy + radius do
            for z = cz - radius, cz + radius do
                local block = world:get_block_at(x, y, z)
                if STRUCTURE_SET[block] then
                    count = count + 1
                    if count > threshold then
                        return true
                    end
                end
            end
        end
    end
    return false
end

--------------------------------------------------------------------------------
-- HELPER: Till a single column at (bx, bz), scanning 3 Y levels around cy.
--------------------------------------------------------------------------------
local function try_till_column(context, bx, cy, bz)
    local world = context.world
    for by = cy + 1, cy - 1, -1 do
        local block = world:get_block_at(bx, by, bz)
        if TILLABLE_BLOCKS[block] then
            local above = world:get_block_at(bx, by + 1, bz)
            local at = {world = context.player.world, x = bx, y = by, z = bz}
            if (above == "air" or above == "cave_air")
              and not em.location.is_protected(at) and not em.location.is_in_dungeon(at)
              and context.action:place_block(bx, by, bz, block, "minecraft:farmland") then
                world:spawn_particle("CLOUD", bx + 0.5, by + 1.0, bz + 0.5, TILL_PARTICLE_COUNT, 0.3, 0.1, 0.3, 0)
                return true
            end
        end
    end
    return false
end

--------------------------------------------------------------------------------
-- HELPER: Convert yaw (degrees) to a unit direction vector on the XZ plane
--------------------------------------------------------------------------------
local function yaw_to_direction(yaw)
    local rad = math.rad(yaw)
    return -math.sin(rad), math.cos(rad)
end

--------------------------------------------------------------------------------
-- HELPER: Region / dungeon block check
-- Returns "protected", "dungeon", or nil.
-- Checks the player's location against WorldGuard / GriefPrevention
-- regions and EliteMobs dungeons.
--------------------------------------------------------------------------------
local function get_block_reason(player)
    local loc = player.current_location
    if not loc then return nil end
    if em.location.is_protected(loc) then return "protected" end
    if em.location.is_in_dungeon(loc) then return "dungeon" end
    return nil
end

--------------------------------------------------------------------------------
-- RETURN SCRIPT TABLE
--------------------------------------------------------------------------------
return {
    api_version = 1,

    on_shift_left_click = function(context)
        local player = context.player
        local world  = context.world
        local item   = context.item

        -- Region / dungeon gate — block the power in protected areas.
        local block_reason = get_block_reason(player)
        if block_reason then
            -- Throttled console warning (once per 10 seconds per player).
            if context.cooldowns:check_local("region_block_log", REGION_LOG_COOLDOWN_TICKS) then
                if block_reason == "protected" then
                    context.log:warn("[Cyclone Tiller] Blocked for player " .. player.name
                        .. " — inside a WorldGuard/GriefPrevention protected region.")
                else
                    context.log:warn("[Cyclone Tiller] Blocked for player " .. player.name
                        .. " — inside an EliteMobs dungeon.")
                end
            end
            return
        end

        -- Already running?
        if context.action:has_previous() then return end

        -- Cooldown check
        if not context.cooldowns:global_ready() then
            player:send_message("&eCyclone Tiller is recharging...")
            return
        end

        -- Durability check
        local dur_pct = item:get_durability_percentage()
        if dur_pct and dur_pct < MIN_DURABILITY_PCT then
            player:send_message("&cHoe is too damaged to use!")
            return
        end

        local loc = player.current_location
        local cx = loc.x
        local cy = loc.y
        local cz = loc.z

        -- Grief protection
        if has_player_structures_nearby(world, math.floor(cx), math.floor(cy), math.floor(cz), GRIEF_SCAN_RADIUS, GRIEF_BLOCK_THRESHOLD) then
            player:send_message("&cToo close to player structures!")
            return
        end

        -- All checks passed — activate
        if not item:use_durability_percentage(DURABILITY_COST, false) then return end
        context.event:cancel()
        context.cooldowns:set_global(COOLDOWN_TICKS)

        -- Play activation sound
        world:play_sound("ENTITY_PLAYER_ATTACK_SWEEP", cx, cy, cz, 1.5, 0.6)

        -- Track which XZ columns have already been tilled so we never re-scan them
        local tilled_columns = {}
        local last_hit = {}

        ----------------------------------------------------------------
        -- PHASE 1: Spin the player for SPIN_DURATION_TICKS
        ----------------------------------------------------------------
        local spin_tick = 0
        local start_yaw = loc.yaw or 0

        local spin_task = context.scheduler:run_repeating(0, 1, function()
            spin_tick = spin_tick + 1
            if spin_tick > SPIN_DURATION_TICKS then return end

            local progress = spin_tick / SPIN_DURATION_TICKS
            local angle = math.rad(start_yaw) + (progress * math.pi * 2)
            local dir_x = -math.sin(angle)
            local dir_z = math.cos(angle)
            player:set_facing(dir_x, 0, dir_z)

            -- Swirl particles around player during spin
            local px = cx + math.cos(angle) * 1.5
            local pz = cz + math.sin(angle) * 1.5
            world:spawn_particle("CLOUD", px, cy + 1.0, pz, SPIN_PARTICLE_COUNT, 0.1, 0.3, 0.1, 0.02)

            if spin_tick % 5 == 0 then
                world:play_sound("ENTITY_PLAYER_ATTACK_SWEEP", cx, cy, cz, 0.8, 1.0 + progress * 0.5)
            end
        end)

        ----------------------------------------------------------------
        -- PHASE 2: After spin, launch expanding tornado
        ----------------------------------------------------------------
        context.scheduler:run_later(SPIN_DURATION_TICKS + 1, function()
            -- Cancel spin task
            context.scheduler:cancel(spin_task)

            -- Launch sound
            world:play_sound("ENTITY_ENDER_DRAGON_FLAP", cx, cy, cz, 2.0, 0.5)

            local tornado_tick = 0
            local tornado_task = context.scheduler:run_repeating(0, 1, function()
                tornado_tick = tornado_tick + 1
                if tornado_tick > TORNADO_DURATION_TICKS then
                        return
                end

                local progress = tornado_tick / TORNADO_DURATION_TICKS
                local current_radius = progress * TORNADO_MAX_RADIUS

                --------------------------------------------------------
                -- PARTICLES: Batched — fewer calls, higher count+spread
                --------------------------------------------------------
                local num_points = math.floor(math.min(current_radius * 3, TORNADO_RING_MAX_POINTS))
                for i = 1, num_points do
                    local a = (i / num_points) * math.pi * 2
                    local px = cx + math.cos(a) * current_radius
                    local pz = cz + math.sin(a) * current_radius
                    world:spawn_particle("CLOUD", px, cy + 0.5, pz, TORNADO_CLOUD_COUNT, 0.4, 0.5, 0.4, 0.01)
                end
                -- Sweep particles at 4 cardinal points every 3rd tick
                if tornado_tick % 3 == 0 then
                    for i = 0, 3 do
                        local a = (i / 4) * math.pi * 2
                        local px = cx + math.cos(a) * current_radius
                        local pz = cz + math.sin(a) * current_radius
                        world:spawn_particle("SWEEP_ATTACK", px, cy + 0.8, pz, TORNADO_SWEEP_COUNT, 0.3, 0.2, 0.3, 0)
                    end
                end

                -- Wind sound every few ticks
                if tornado_tick % 8 == 0 then
                    world:play_sound("ENTITY_ENDER_DRAGON_FLAP", cx, cy, cz, 1.0, 0.8 + progress * 0.4)
                end

                --------------------------------------------------------
                -- DAMAGE / LAUNCH: Only every 4 ticks (was every tick)
                --------------------------------------------------------
                if tornado_tick % 4 == 0 then
                    local entities = world:get_nearby_entities(cx, cy, cz, current_radius + 1)
                    if entities then
                        for _, entity in ipairs(entities) do
                            if entity:can_receive_hostile_effect(player.uuid) then
                                local eloc = entity.current_location
                                if eloc then
                                    local ex = eloc.x - cx
                                    local ez = eloc.z - cz
                                    local edist = math.sqrt(ex * ex + ez * ez)

                                    if edist >= current_radius - 1.5 and edist <= current_radius + 1.5 then
                                        if tornado_tick - (last_hit[entity.uuid] or -10) >= 10
                                          and not em.location.is_protected(eloc)
                                          and not em.location.is_in_dungeon(eloc) then
                                            last_hit[entity.uuid] = tornado_tick
                                            if context.action:damage_target(entity.uuid, DAMAGE_AMOUNT) then
                                                local norm = edist > 0 and edist or 1
                                                local vx, vz = ex / norm * LAUNCH_OUT, ez / norm * LAUNCH_OUT
                                                local path = entity:movement_path_status({world = player.world,
                                                    x = eloc.x + vx, y = eloc.y + LAUNCH_UP, z = eloc.z + vz})
                                                if path ~= "protected" and path ~= "unavailable" then
                                                    entity:push(vx, LAUNCH_UP, vz)
                                                end
                                            end
                                        end
                                    end
                                end
                            end
                        end
                    end
                end

                --------------------------------------------------------
                -- TILL: Every 4 ticks, skip already-processed columns
                --------------------------------------------------------
                if tornado_tick % 4 == 0 then
                    local till_outer = math.min(current_radius, TILL_RADIUS)
                    local icy = math.floor(cy)
                    local min_x = math.floor(cx - till_outer)
                    local max_x = math.ceil(cx + till_outer)
                    local min_z = math.floor(cz - till_outer)
                    local max_z = math.ceil(cz + till_outer)

                    for bx = min_x, max_x do
                        for bz = min_z, max_z do
                            local key = bx .. "," .. bz
                            if not tilled_columns[key] then
                                local dx = bx - cx
                                local dz = bz - cz
                                local dist = math.sqrt(dx * dx + dz * dz)
                                if dist <= till_outer then
                                    tilled_columns[key] = true
                                    try_till_column(context, bx, icy, bz)
                                end
                            end
                        end
                    end
                end
            end)

            -- Cleanup after tornado finishes
            context.scheduler:run_later(TORNADO_DURATION_TICKS + 2, function()
                context.scheduler:cancel(tornado_task)
                world:play_sound("ENTITY_GENERIC_EXTINGUISH_FIRE", cx, cy, cz, 1.5, 0.5)
            end)
        end)
    end,
}