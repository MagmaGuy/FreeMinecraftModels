--------------------------------------------------------------------------------
-- Omega Drill — Cave Compendium Pickaxe Power
-- Shift+Left-click to mine a cone-shaped tunnel along the floor.
-- Ores are preserved and pinged when detected. Player structures block firing.
--
-- Sources:
--   Wiki: FreeMinecraftModels/lua_prop_api  (item hooks, item:get_durability, etc.)
--   Wiki: global/lua_scripting_engine       (world, scheduler, zones, entity)
--   Wiki: FreeMinecraftModels/lua_examples  (Frost Shockwave pattern)
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
-- SETTINGS — tweak these to balance
--------------------------------------------------------------------------------
local CONE_LENGTH            = 14      -- how far the cone extends (blocks)
local CONE_END_WIDTH         = 9       -- full width at the far end (blocks)
local CONE_HEIGHT_START      = 2       -- vertical blocks mined at the near end
local CONE_HEIGHT_MID        = 3       -- vertical blocks mined at the midpoint
local CONE_HEIGHT_END        = 4       -- vertical blocks mined at the far end
local DRILL_DURATION_TICKS   = 120     -- total time to mine the full cone (5 s)
local COOLDOWN_TICKS         = 6000     -- cooldown between uses
local DURABILITY_COST        = 0.08    -- fraction of max durability consumed (8 %)
local MIN_DURABILITY_PCT     = 0.10    -- item needs ≥ 10 % durability to fire
local GRIEF_SCAN_RADIUS      = 10      -- radius around player to scan for structures
local GRIEF_BLOCK_THRESHOLD  = 1       -- ANY structure block nearby blocks the drill
local GRIEF_CONE_CHECK       = true    -- also scan inside the cone for structures

local SETTINGS = {
    -- Console message cooldown (ticks). 10 seconds = 200 ticks.
    region_log_cooldown_ticks = 200,
}

-- Blocks that count as "player structures" (grief protection)
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

-- Ore blocks — preserved and pinged when discovered
local ORE_BLOCKS = {
    "coal_ore", "deepslate_coal_ore",
    "iron_ore", "deepslate_iron_ore",
    "copper_ore", "deepslate_copper_ore",
    "gold_ore", "deepslate_gold_ore",
    "redstone_ore", "deepslate_redstone_ore",
    "emerald_ore", "deepslate_emerald_ore",
    "lapis_ore", "deepslate_lapis_ore",
    "diamond_ore", "deepslate_diamond_ore",
    "nether_gold_ore", "nether_quartz_ore",
    "ancient_debris",
}

-- Blocks a diamond pickaxe mines efficiently (stone-family materials)
local MINEABLE_BLOCKS = {
    "stone", "cobblestone", "mossy_cobblestone",
    "granite", "polished_granite",
    "diorite", "polished_diorite",
    "andesite", "polished_andesite",
    "deepslate", "cobbled_deepslate", "polished_deepslate",
    "calcite", "tuff", "dripstone_block", "pointed_dripstone",
    "smooth_stone",
    "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks",
    "chiseled_stone_bricks", "infested_stone",
    "infested_cobblestone", "infested_stone_bricks",
    "infested_mossy_stone_bricks", "infested_cracked_stone_bricks",
    "infested_chiseled_stone_bricks", "infested_deepslate",
    "sandstone", "chiseled_sandstone", "cut_sandstone", "smooth_sandstone",
    "red_sandstone", "chiseled_red_sandstone", "cut_red_sandstone", "smooth_red_sandstone",
    "netherrack", "basalt", "smooth_basalt", "polished_basalt",
    "blackstone", "polished_blackstone", "chiseled_polished_blackstone",
    "gilded_blackstone", "polished_blackstone_bricks",
    "cracked_polished_blackstone_bricks",
    "end_stone", "end_stone_bricks",
    "obsidian", "crying_obsidian",
    "prismarine", "prismarine_bricks", "dark_prismarine",
    "terracotta", "white_terracotta", "orange_terracotta",
    "magenta_terracotta", "light_blue_terracotta",
    "yellow_terracotta", "lime_terracotta", "pink_terracotta",
    "gray_terracotta", "light_gray_terracotta",
    "cyan_terracotta", "purple_terracotta", "blue_terracotta",
    "brown_terracotta", "green_terracotta", "red_terracotta",
    "black_terracotta",
    "gravel", "clay",
    "mud", "packed_mud", "mud_bricks",
    "amethyst_block", "budding_amethyst",
    "raw_iron_block", "raw_copper_block", "raw_gold_block",
    "ice", "packed_ice", "blue_ice",
    "magma_block", "glowstone", "bone_block",
    "purpur_block", "purpur_pillar",
    "white_concrete", "orange_concrete",
    "magenta_concrete", "light_blue_concrete",
    "yellow_concrete", "lime_concrete", "pink_concrete",
    "gray_concrete", "light_gray_concrete",
    "cyan_concrete", "purple_concrete", "blue_concrete",
    "brown_concrete", "green_concrete", "red_concrete",
    "black_concrete",
    "bricks", "nether_bricks", "red_nether_bricks",
    "cracked_nether_bricks", "chiseled_nether_bricks",
    "quartz_block", "chiseled_quartz_block", "quartz_bricks",
    "quartz_pillar", "smooth_quartz",
    "lodestone",
    "copper_block", "cut_copper",
    "exposed_copper", "exposed_cut_copper",
    "weathered_copper", "weathered_cut_copper",
    "oxidized_copper", "oxidized_cut_copper",
    "waxed_copper_block", "waxed_cut_copper",
    "waxed_exposed_copper", "waxed_exposed_cut_copper",
    "waxed_weathered_copper", "waxed_weathered_cut_copper",
    "waxed_oxidized_copper", "waxed_oxidized_cut_copper",
}

-- Protected blocks that must never be removed
local PROTECTED_BLOCKS = {
    "air", "bedrock", "barrier",
    "command_block", "chain_command_block", "repeating_command_block",
    "structure_block", "structure_void", "jigsaw",
}

-- Block → drop mapping (approximate vanilla pickaxe drops)
-- Key = block type from get_block_at (lowercase). Value = { material, amount }
-- Blocks not listed here drop themselves (uppercased) with amount 1.
-- False marks an intentional no-drop entry; an absent entry drops itself.
local BLOCK_DROP_MAP = {
    -- Stone family → cobblestone
    ["stone"]           = { "COBBLESTONE", 1 },
    ["deepslate"]       = { "COBBLED_DEEPSLATE", 1 },
    ["infested_stone"]  = { "COBBLESTONE", 1 },
    ["infested_cobblestone"]            = { "COBBLESTONE", 1 },
    ["infested_stone_bricks"]           = { "STONE_BRICKS", 1 },
    ["infested_mossy_stone_bricks"]     = { "MOSSY_STONE_BRICKS", 1 },
    ["infested_cracked_stone_bricks"]   = { "CRACKED_STONE_BRICKS", 1 },
    ["infested_chiseled_stone_bricks"]  = { "CHISELED_STONE_BRICKS", 1 },
    ["infested_deepslate"]              = { "COBBLED_DEEPSLATE", 1 },

    -- Clay drops clay balls
    ["clay"] = { "CLAY_BALL", 4 },

    -- Glowstone drops dust
    ["glowstone"] = { "GLOWSTONE_DUST", 2 },

    -- Ice drops nothing (melts)
    ["ice"] = false,

    -- Budding amethyst drops nothing
    ["budding_amethyst"] = false,
}

--------------------------------------------------------------------------------
-- HELPERS
--------------------------------------------------------------------------------

local function make_set(list)
    local s = {}
    for _, v in ipairs(list) do s[v] = true end
    return s
end

local ore_set       = make_set(ORE_BLOCKS)
local mineable_set  = make_set(MINEABLE_BLOCKS)
local structure_set = make_set(PLAYER_STRUCTURE_BLOCKS)
local protected_set = make_set(PROTECTED_BLOCKS)

local function is_ore(bt)       return ore_set[bt] == true       end
local function is_mineable(bt)  return mineable_set[bt] == true  end
local function is_structure(bt) return structure_set[bt] == true end
local function is_protected(bt) return protected_set[bt] == true end

--- Get the drop material and amount for a mined block.
--- Returns material (string), amount (int) — or nil, 0 if no drop.
local function get_block_drop(block_type)
    local entry = BLOCK_DROP_MAP[block_type]
    if entry == false then return nil, 0 end
    if entry then return entry[1], entry[2] end
    return string.upper(block_type), 1
end

--- Compute the cone height at a given row based on progress (0→1).
--- Linearly interpolates: start → mid at 50%, mid → end at 100%.
local function get_height_at_progress(progress)
    if progress <= 0.5 then
        local t = progress / 0.5
        return math.floor(CONE_HEIGHT_START + (CONE_HEIGHT_MID - CONE_HEIGHT_START) * t + 0.5)
    else
        local t = (progress - 0.5) / 0.5
        return math.floor(CONE_HEIGHT_MID + (CONE_HEIGHT_END - CONE_HEIGHT_MID) * t + 0.5)
    end
end

--- Scan for player-placed structures within a radius. Returns count.
local function count_structures_nearby(world, cx, cy, cz, radius)
    local count = 0
    local r = math.floor(radius)
    for dx = -r, r do
        for dy = -2, 4 do
            for dz = -r, r do
                if dx * dx + dz * dz <= r * r then
                    local bt = world:get_block_at(
                        math.floor(cx) + dx,
                        math.floor(cy) + dy,
                        math.floor(cz) + dz
                    )
                    if is_structure(bt) then
                        count = count + 1
                        if count >= GRIEF_BLOCK_THRESHOLD then return count end
                    end
                end
            end
        end
    end
    return count
end

--- Build the cone as a list of rows (one per block-distance from the player).
--- Each row = { blocks = { {x,y,z}, ... }, distance = d, height = h }
--- Height ramps: CONE_HEIGHT_START → CONE_HEIGHT_MID → CONE_HEIGHT_END
---
--- FIX: Uses a dedup set and fills the full bounding rectangle per row slice
---      so that diagonal facing directions can't skip block positions, which
---      previously left "pillars" of unmined stone inside the cone.
local function build_cone_rows(ox, oy, oz, dir_x, dir_z, length, end_width)
    local rows = {}
    local perp_x = -dir_z   -- perpendicular direction on XZ plane
    local perp_z =  dir_x
    local floor_oy = math.floor(oy)

    for d = 1, length do
        local progress   = d / length
        local half_width = (end_width * progress) / 2.0
        local height     = get_height_at_progress(progress)
        local row        = { blocks = {}, distance = d, height = height }

        -- Compute centre of this row slice in world space
        local cx = ox + dir_x * d
        local cz = oz + dir_z * d

        -- Sample enough points along the perpendicular to find every
        -- integer block position the slice covers.  We over-sample by 2×
        -- to guarantee no gaps regardless of facing angle.
        local samples  = math.max(3, math.floor(half_width * 2) + 1) * 2
        local seen     = {}   -- dedup: "x,z" → true

        for i = 0, samples do
            local t  = -half_width + (half_width * 2) * (i / samples)
            local bx = math.floor(cx + perp_x * t)
            local bz = math.floor(cz + perp_z * t)
            local key = bx .. "," .. bz

            if not seen[key] then
                seen[key] = true
                for dy = 0, height - 1 do
                    table.insert(row.blocks, { x = bx, y = floor_oy + dy, z = bz })
                end
            end
        end

        -- Second pass: fill the integer bounding box of the sampled points
        -- and keep any position that's geometrically inside the half-width.
        -- This catches any block the sampling step may have jumped over.
        local min_x, max_x, min_z, max_z = math.huge, -math.huge, math.huge, -math.huge
        for k, _ in pairs(seen) do
            local sx, sz = k:match("^(-?%d+),(-?%d+)$")
            sx = tonumber(sx); sz = tonumber(sz)
            if sx < min_x then min_x = sx end
            if sx > max_x then max_x = sx end
            if sz < min_z then min_z = sz end
            if sz > max_z then max_z = sz end
        end

        for ix = min_x, max_x do
            for iz = min_z, max_z do
                local key = ix .. "," .. iz
                if not seen[key] then
                    -- Check if this integer position is within the row's
                    -- half-width from the row centre line
                    local rel_x = ix + 0.5 - cx
                    local rel_z = iz + 0.5 - cz
                    -- Project onto perpendicular axis
                    local proj = math.abs(rel_x * perp_x + rel_z * perp_z)
                    if proj <= half_width + 0.5 then   -- +0.5 for block centre tolerance
                        seen[key] = true
                        for dy = 0, height - 1 do
                            table.insert(row.blocks, { x = ix, y = floor_oy + dy, z = iz })
                        end
                    end
                end
            end
        end

        table.insert(rows, row)
    end
    return rows
end

--- Scan a set of cone rows for structure blocks. Returns count found.
local function count_structures_in_cone(world, rows)
    local count = 0
    for _, row in ipairs(rows) do
        for _, b in ipairs(row.blocks) do
            local bt = world:get_block_at(b.x, b.y, b.z)
            if bt and is_structure(bt) then
                count = count + 1
                if count >= GRIEF_BLOCK_THRESHOLD then return count end
            end
        end
    end
    return count
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

--------------------------------------------------------------------------------
-- SCRIPT
--------------------------------------------------------------------------------
return {
    api_version = 1,

    on_shift_left_click = function(context)
        local player = context.player
        if not player or not context.item then return end

        -- Region / dungeon gate — block the power in protected areas.
        local block_reason = get_block_reason(player)
        if block_reason then
          -- Throttled console warning (once per 10 seconds per player).
          if context.cooldowns:check_local("region_block_log", SETTINGS.region_log_cooldown_ticks) then
            if block_reason == "protected" then
              context.log:warn("[Omega Drill] Blocked for player " .. player.name
                .. " — inside a WorldGuard/GriefPrevention protected region.")
            else
              context.log:warn("[Omega Drill] Blocked for player " .. player.name
                .. " — inside an EliteMobs dungeon.")
            end
          end
          return
        end

        -- Guard: already running
        if context.state.drill_active then
            player:send_message("&c&l⛏ Omega Drill is already active!")
            return
        end

        -- Guard: cooldown (global cooldown persists across unequip/re-equip)
        if not context.cooldowns:global_ready() then
            player:send_message("&e⛏ Omega Drill is recharging...")
            return
        end

        -- Guard: durability
        local dur = context.item:get_durability()
        if not dur then
            player:send_message("&cThis item has no durability.")
            return
        end
        local pct = dur.current / dur.max
        if pct < MIN_DURABILITY_PCT then
            player:send_message("&c&lPickaxe too damaged! Repair before using Omega Drill.")
            context.world:play_sound("ITEM_SHIELD_BREAK", player.current_location.x,
                player.current_location.y, player.current_location.z, 0.8, 1.5)
            return
        end
        local cost_points = math.ceil(dur.max * DURABILITY_COST)
        if dur.current < cost_points then
            player:send_message("&c&lNot enough durability! Need at least " .. cost_points .. " points.")
            return
        end

        -- Cancel the underlying interact event
        if context.event then context.event.cancel() end

        -- Direction: horizontal only, from player yaw
        local loc   = player.current_location
        local yaw_r = math.rad(loc.yaw)
        local dir_x = -math.sin(yaw_r)
        local dir_z =  math.cos(yaw_r)
        local dlen  = math.sqrt(dir_x * dir_x + dir_z * dir_z)
        if dlen > 0 then dir_x = dir_x / dlen; dir_z = dir_z / dlen end

        local origin_x = loc.x
        local origin_y = loc.y
        local origin_z = loc.z

        -- Build cone geometry
        local rows = build_cone_rows(
            origin_x, origin_y, origin_z,
            dir_x, dir_z,
            CONE_LENGTH, CONE_END_WIDTH
        )
        local total_rows = #rows
        if total_rows == 0 then return end

        -- =================================================================
        -- GRIEF PROTECTION — two-pass check
        -- =================================================================
        local structs = count_structures_nearby(
            context.world, origin_x, origin_y, origin_z, GRIEF_SCAN_RADIUS
        )

        if structs < GRIEF_BLOCK_THRESHOLD and GRIEF_CONE_CHECK then
            structs = structs + count_structures_in_cone(context.world, rows)
        end

        if structs >= GRIEF_BLOCK_THRESHOLD then
            player:send_message("&c&l⛏ Player structures detected nearby! (" .. structs .. " found)")
            player:send_message("&7Omega Drill refuses to fire to protect builds.")
            context.world:play_sound("ENTITY_VILLAGER_NO",
                loc.x, loc.y, loc.z, 1.0, 1.0)
            return
        end

        local ticks_per_row = math.max(1, math.floor(DRILL_DURATION_TICKS / total_rows))

        -- Consume durability (don't break the item)
        if not context.item:use_durability(cost_points, false) then return end
        context.event:cancel()

        -- Lock state & set cooldown
        context.state.drill_active = true
        context.cooldowns:set_global(COOLDOWN_TICKS)

        -- === Activation effects ===
        player:show_action_bar("&b&l⛏ OMEGA DRILL ACTIVATED ⛏", DRILL_DURATION_TICKS + 20)
        context.world:play_sound("ENTITY_WITHER_BREAK_BLOCK",
            loc.x, loc.y, loc.z, 1.5, 1.8)
        context.world:spawn_particle("FLAME",
            loc.x, loc.y + 0.5, loc.z, 15, 0.3, 0.2, 0.3, 0.05)

        -- === Mining loop — processes one row per interval ===
        local row_index  = 0
        local ores_found = 0

        context.state.drill_task = context.scheduler:run_repeating(
            0, ticks_per_row,
            function(tick_ctx)
                row_index = row_index + 1

                -- Finished all rows
                if row_index > total_rows then
                    tick_ctx.scheduler:cancel(tick_ctx.state.drill_task)
                    tick_ctx.state.drill_active = false
                    tick_ctx.state.drill_task   = nil

                    -- Completion effects at cone tip
                    local tip_x = origin_x + dir_x * CONE_LENGTH
                    local tip_z = origin_z + dir_z * CONE_LENGTH
                    tick_ctx.world:play_sound("ENTITY_PLAYER_LEVELUP",
                        tip_x, origin_y, tip_z, 1.2, 1.5)
                    tick_ctx.world:spawn_particle("HAPPY_VILLAGER",
                        tip_x, origin_y + 1, tip_z, 20, 1.0, 0.5, 1.0, 0.02)

                    -- Summary
                    if ores_found > 0 then
                        local nearby = tick_ctx.world:get_nearby_players(
                            origin_x, origin_y, origin_z, CONE_LENGTH + 10)
                        if nearby then
                            for _, p in ipairs(nearby) do
                                p:send_message("&a&l⛏ Omega Drill complete! &e" ..
                                    ores_found .. " ore(s) &adiscovered!")
                            end
                        end
                    end
                    return
                end

                local row      = rows[row_index]
                local progress = row_index / total_rows

                -- Sound: pitch drops, volume rises as the drill advances
                local snd_pitch  = 2.0 - (progress * 1.5)
                local snd_volume = 0.6 + (progress * 1.0)

                local row_cx = origin_x + dir_x * row.distance
                local row_cz = origin_z + dir_z * row.distance

                -- Primary drilling sound
                tick_ctx.world:play_sound("BLOCK_STONE_BREAK",
                    row_cx, origin_y + 1, row_cz, snd_volume, snd_pitch)
                -- Low rumble undertone
                tick_ctx.world:play_sound("ENTITY_WITHER_BREAK_BLOCK",
                    row_cx, origin_y + 1, row_cz, snd_volume * 0.35, snd_pitch * 0.6)

                -- === Process blocks in this row ===
                local found_ore_this_row = false

                for _, b in ipairs(row.blocks) do
                    local bt = tick_ctx.world:get_block_at(b.x, b.y, b.z)

                    if bt and not is_protected(bt) then
                        if is_ore(bt) then
                            found_ore_this_row = true
                            ores_found = ores_found + 1
                            tick_ctx.world:spawn_particle("ENCHANT",
                                b.x + 0.5, b.y + 0.5, b.z + 0.5,
                                12, 0.3, 0.3, 0.3, 0.1)

                        elseif is_mineable(bt) then
                            local drop_mat, drop_amt = get_block_drop(bt)
                            tick_ctx.action:break_block(b.x, b.y, b.z, bt, drop_mat, drop_amt)
                        end
                    end
                end

                -- Ore discovery pling!
                if found_ore_this_row then
                    tick_ctx.world:play_sound("ENTITY_EXPERIENCE_ORB_PICKUP",
                        row_cx, origin_y + 1, row_cz, 1.2, 1.8)
                end

                -- === Advancing particle curtain along the drill front ===
                local half_w = (CONE_END_WIDTH * progress) / 2.0
                local perp_x = -dir_z
                local perp_z =  dir_x
                local p_steps = math.max(3, math.floor(half_w * 2) + 1)

                for i = 0, p_steps do
                    local t  = -half_w + (half_w * 2) * (i / p_steps)
                    local px = row_cx + perp_x * t
                    local pz = row_cz + perp_z * t

                    local row_height = row.height or CONE_HEIGHT_START
                    for h = 0, row_height - 1 do
                        tick_ctx.world:spawn_particle("CLOUD",
                            px, origin_y + h + 0.5, pz, 2, 0.1, 0.15, 0.1, 0.01)
                    end
                    tick_ctx.world:spawn_particle("FLAME",
                        px, origin_y + 0.3, pz, 1, 0.05, 0.05, 0.05, 0.02)
                end

                -- Boss bar progress for nearby players
                local bar_fill = 1.0 - progress
                local nearby = tick_ctx.world:get_nearby_players(
                    origin_x, origin_y, origin_z, CONE_LENGTH + 10)
                if nearby then
                    for _, p in ipairs(nearby) do
                        p:show_boss_bar(
                            "&b&l⛏ Omega Drill &7" .. math.floor(progress * 100) .. "%",
                            "BLUE", bar_fill, ticks_per_row + 5)
                    end
                end
            end
        )
    end,

}
