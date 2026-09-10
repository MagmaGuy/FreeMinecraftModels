-- ============================================================
-- Ghost Chopper — Arboreal Terminator Axe
-- Shift + Right-click to summon spectral lumberjacks that
-- chop every tree within range, one block per second,
-- bottom-to-top, tree by tree. Replants saplings when done.
-- ============================================================

-- =====================  SETTINGS  ============================

-- Scan radius (blocks) around the player for trees
local SCAN_RADIUS = 20
local MAX_TREES = 32
local MAX_CHANGED_BLOCKS = 4096

-- Ticks between each block chop (20 = 1 second)
local CHOP_INTERVAL = 10

-- Cooldown in ticks (6000 = 5 minutes)
local COOLDOWN_TICKS = 6000

-- Durability cost as a fraction of max (0.10 = 10%)
local DURABILITY_COST = 0.10

-- Minimum durability fraction required to activate (0.10 = 10%)
local DURABILITY_MINIMUM = 0.10

-- Console message cooldown (ticks). 10 seconds = 200 ticks.
local REGION_LOG_COOLDOWN_TICKS = 200

-- =====================  BRANCH CLEARING  =====================

-- When chopping a log, also clear any logs within this XZ radius
-- on the same Y level (handles branches / non-straight trees).
-- Only the center log drops an item; radius logs are just removed.
local BRANCH_CLEAR_RADIUS = 5

-- When chopping the LAST (topmost) log of a tree trunk, also sweep
-- this many extra Y levels above for branch logs that sit higher
-- than the center column. Catches crown branches on dark oak, etc.
local CROWN_EXTRA_Y = 2

-- =====================  REPLANTING  ==========================

-- Whether the ghost plants a sapling after finishing each tree
local REPLANT_ENABLED = true

-- How many Y levels above the tree base to try placing the sapling.
-- Tries base_y first, then base_y+1, then base_y+2 (for obstructions).
local REPLANT_MAX_OFFSET = 2

-- =====================  SPIRIT ENTITY  =======================

local SPIRIT_ENTITY_TYPE = "vex"
local SPIRIT_Y_OFFSET = 1.5
local SPIRIT_GLOWING = true

-- =====================  ANTI-GRIEF  ==========================

local CIVILIZATION_SCAN_RADIUS = 30
local CIVILIZATION_THRESHOLD = 8
local CIVILIZATION_Y_RANGE = 20
local CIVILIZATION_SAMPLE_STEP = 3

-- How many XZ columns to scan per tick during the civilization check.
-- ~200 columns × 41 Y = ~8,200 get_block_at calls — safely under 50ms.
local CIV_COLUMNS_PER_TICK = 200

-- How many XZ columns to scan per tick during the tree search.
-- ~100 columns × 61 Y = ~6,100 get_block_at calls — safely under 50ms.
local TREE_COLUMNS_PER_TICK = 100

local CIVILIZATION_BLOCKS = {
    -- Doors (strong indicator — 2 doors alone should block)
    ["oak_door"]           = 4,
    ["spruce_door"]        = 4,
    ["birch_door"]         = 4,
    ["jungle_door"]        = 4,
    ["acacia_door"]        = 4,
    ["dark_oak_door"]      = 4,
    ["mangrove_door"]      = 4,
    ["cherry_door"]        = 4,
    ["pale_oak_door"]      = 4,
    ["bamboo_door"]        = 4,
    ["crimson_door"]       = 4,
    ["warped_door"]        = 4,
    ["iron_door"]          = 4,
    ["copper_door"]        = 4,
    ["exposed_copper_door"]    = 4,
    ["weathered_copper_door"]  = 4,
    ["oxidized_copper_door"]   = 4,

    -- Beds (very strong — one bed = someone lives here)
    ["white_bed"]     = 5, ["orange_bed"]     = 5,
    ["magenta_bed"]   = 5, ["light_blue_bed"] = 5,
    ["yellow_bed"]    = 5, ["lime_bed"]       = 5,
    ["pink_bed"]      = 5, ["gray_bed"]       = 5,
    ["light_gray_bed"]= 5, ["cyan_bed"]       = 5,
    ["purple_bed"]    = 5, ["blue_bed"]       = 5,
    ["brown_bed"]     = 5, ["green_bed"]      = 5,
    ["red_bed"]       = 5, ["black_bed"]      = 5,

    -- Storage (moderate indicator)
    ["chest"]          = 3,
    ["trapped_chest"]  = 3,
    ["barrel"]         = 3,
    ["shulker_box"]    = 4,

    -- Workstations (moderate indicator)
    ["furnace"]          = 3,
    ["blast_furnace"]    = 3,
    ["smoker"]           = 3,
    ["crafting_table"]   = 2,
    ["anvil"]            = 3,
    ["chipped_anvil"]    = 3,
    ["damaged_anvil"]    = 3,
    ["brewing_stand"]    = 3,
    ["enchanting_table"] = 4,
    ["smithing_table"]   = 2,
    ["cartography_table"]= 2,
    ["loom"]             = 2,
    ["stonecutter"]      = 2,
    ["grindstone"]       = 2,
    ["lectern"]          = 2,
    ["composter"]        = 1,

    -- Redstone / mechanical (moderate — indicates engineering)
    ["piston"]         = 2,
    ["sticky_piston"]  = 2,
    ["hopper"]         = 3,
    ["dropper"]        = 2,
    ["dispenser"]      = 2,
    ["observer"]       = 2,

    -- Decoration / markers (light indicator — many needed)
    ["torch"]          = 1,
    ["wall_torch"]     = 1,
    ["lantern"]        = 1,
    ["soul_lantern"]   = 1,
    ["campfire"]       = 2,
    ["soul_campfire"]  = 2,

    -- Fence gates (suggests animal pens / gardens)
    ["oak_fence_gate"]      = 2,
    ["spruce_fence_gate"]   = 2,
    ["birch_fence_gate"]    = 2,
    ["jungle_fence_gate"]   = 2,
    ["acacia_fence_gate"]   = 2,
    ["dark_oak_fence_gate"] = 2,
    ["mangrove_fence_gate"] = 2,
    ["cherry_fence_gate"]   = 2,
    ["bamboo_fence_gate"]   = 2,
    ["crimson_fence_gate"]  = 2,
    ["warped_fence_gate"]   = 2,

    -- Farmland (player-tilled soil)
    ["farmland"] = 1,

    -- Glass panes (windows = building)
    ["glass_pane"]            = 1,
    ["white_stained_glass_pane"] = 1,
    ["orange_stained_glass_pane"] = 1,
    ["magenta_stained_glass_pane"] = 1,
    ["light_blue_stained_glass_pane"] = 1,
    ["yellow_stained_glass_pane"] = 1,
    ["lime_stained_glass_pane"] = 1,
    ["pink_stained_glass_pane"] = 1,
    ["gray_stained_glass_pane"] = 1,
    ["light_gray_stained_glass_pane"] = 1,
    ["cyan_stained_glass_pane"] = 1,
    ["purple_stained_glass_pane"] = 1,
    ["blue_stained_glass_pane"] = 1,
    ["brown_stained_glass_pane"] = 1,
    ["green_stained_glass_pane"] = 1,
    ["red_stained_glass_pane"] = 1,
    ["black_stained_glass_pane"] = 1,
}

-- =====================  TREE BLOCKS  =========================

local LOG_TYPES = {
    ["oak_log"] = true,
    ["spruce_log"] = true,
    ["birch_log"] = true,
    ["jungle_log"] = true,
    ["acacia_log"] = true,
    ["dark_oak_log"] = true,
    ["mangrove_log"] = true,
    ["cherry_log"] = true,
    ["pale_oak_log"] = true,
    ["crimson_stem"] = true,
    ["warped_stem"] = true,
    ["stripped_oak_log"] = true,
    ["stripped_spruce_log"] = true,
    ["stripped_birch_log"] = true,
    ["stripped_jungle_log"] = true,
    ["stripped_acacia_log"] = true,
    ["stripped_dark_oak_log"] = true,
    ["stripped_mangrove_log"] = true,
    ["stripped_cherry_log"] = true,
    ["stripped_pale_oak_log"] = true,
    ["stripped_crimson_stem"] = true,
    ["stripped_warped_stem"] = true,
}

local LEAF_TYPES = {
    ["oak_leaves"] = true,
    ["spruce_leaves"] = true,
    ["birch_leaves"] = true,
    ["jungle_leaves"] = true,
    ["acacia_leaves"] = true,
    ["dark_oak_leaves"] = true,
    ["mangrove_leaves"] = true,
    ["cherry_leaves"] = true,
    ["pale_oak_leaves"] = true,
    ["azalea_leaves"] = true,
    ["flowering_azalea_leaves"] = true,
    ["nether_wart_block"] = true,
    ["warped_wart_block"] = true,
}

-- =====================  LOG DROPS  ===========================

local LOG_DROP_MAP = {
    ["oak_log"]              = "OAK_LOG",
    ["spruce_log"]           = "SPRUCE_LOG",
    ["birch_log"]            = "BIRCH_LOG",
    ["jungle_log"]           = "JUNGLE_LOG",
    ["acacia_log"]           = "ACACIA_LOG",
    ["dark_oak_log"]         = "DARK_OAK_LOG",
    ["mangrove_log"]         = "MANGROVE_LOG",
    ["cherry_log"]           = "CHERRY_LOG",
    ["pale_oak_log"]         = "PALE_OAK_LOG",
    ["crimson_stem"]         = "CRIMSON_STEM",
    ["warped_stem"]          = "WARPED_STEM",
    ["stripped_oak_log"]     = "STRIPPED_OAK_LOG",
    ["stripped_spruce_log"]  = "STRIPPED_SPRUCE_LOG",
    ["stripped_birch_log"]   = "STRIPPED_BIRCH_LOG",
    ["stripped_jungle_log"]  = "STRIPPED_JUNGLE_LOG",
    ["stripped_acacia_log"]  = "STRIPPED_ACACIA_LOG",
    ["stripped_dark_oak_log"]= "STRIPPED_DARK_OAK_LOG",
    ["stripped_mangrove_log"]= "STRIPPED_MANGROVE_LOG",
    ["stripped_cherry_log"]  = "STRIPPED_CHERRY_LOG",
    ["stripped_pale_oak_log"]= "STRIPPED_PALE_OAK_LOG",
    ["stripped_crimson_stem"]= "STRIPPED_CRIMSON_STEM",
    ["stripped_warped_stem"] = "STRIPPED_WARPED_STEM",
}

-- =====================  SAPLING MAP  =========================
-- Maps log block types → the sapling/fungus block to plant.
-- Stripped logs map to the same sapling as their unstripped variant.

local LOG_SAPLING_MAP = {
    ["oak_log"]              = "oak_sapling",
    ["spruce_log"]           = "spruce_sapling",
    ["birch_log"]            = "birch_sapling",
    ["jungle_log"]           = "jungle_sapling",
    ["acacia_log"]           = "acacia_sapling",
    ["dark_oak_log"]         = "dark_oak_sapling",
    ["mangrove_log"]         = "mangrove_propagule",
    ["cherry_log"]           = "cherry_sapling",
    ["pale_oak_log"]         = "pale_oak_sapling",
    ["crimson_stem"]         = "crimson_fungus",
    ["warped_stem"]          = "warped_fungus",
    ["stripped_oak_log"]     = "oak_sapling",
    ["stripped_spruce_log"]  = "spruce_sapling",
    ["stripped_birch_log"]   = "birch_sapling",
    ["stripped_jungle_log"]  = "jungle_sapling",
    ["stripped_acacia_log"]  = "acacia_sapling",
    ["stripped_dark_oak_log"]= "dark_oak_sapling",
    ["stripped_mangrove_log"]= "mangrove_propagule",
    ["stripped_cherry_log"]  = "cherry_sapling",
    ["stripped_pale_oak_log"]= "pale_oak_sapling",
    ["stripped_crimson_stem"]= "crimson_fungus",
    ["stripped_warped_stem"] = "warped_fungus",
}

-- Blocks that saplings / fungus can be planted on top of.
local PLANTABLE_GROUND = {
    ["dirt"]            = true,
    ["grass_block"]     = true,
    ["podzol"]          = true,
    ["mycelium"]        = true,
    ["coarse_dirt"]     = true,
    ["rooted_dirt"]     = true,
    ["mud"]             = true,
    ["muddy_mangrove_roots"] = true,
    ["moss_block"]      = true,
    -- Nether fungi grow on nylium
    ["crimson_nylium"]  = true,
    ["warped_nylium"]   = true,
}

-- =====================  SOUNDS  ==============================

local CHOP_SOUND = "BLOCK_WOOD_BREAK"
local CHOP_SOUND_VOLUME = 1.0
local CHOP_SOUND_PITCH = 0.8

local ACTIVATE_SOUND = "ENTITY_GHAST_AMBIENT"
local ACTIVATE_SOUND_VOLUME = 1.2
local ACTIVATE_SOUND_PITCH = 0.6

local COMPLETE_SOUND = "ENTITY_GHAST_HURT"
local COMPLETE_SOUND_VOLUME = 1.0
local COMPLETE_SOUND_PITCH = 1.2

local INHABITED_SOUND = "ENTITY_GHAST_WARN"
local INHABITED_SOUND_VOLUME = 0.8
local INHABITED_SOUND_PITCH = 1.4

local REPLANT_SOUND = "BLOCK_GRASS_PLACE"
local REPLANT_SOUND_VOLUME = 1.0
local REPLANT_SOUND_PITCH = 1.2

-- =====================  PARTICLES  ===========================

local GHOST_PARTICLE = "SOUL"
local GHOST_PARTICLE_COUNT = 3
local GHOST_PARTICLE_SPREAD = 0.4

local CHOP_PARTICLE = "CAMPFIRE_COSY_SMOKE"
local CHOP_PARTICLE_COUNT = 6
local CHOP_PARTICLE_SPREAD = 0.3

local AMBIENT_PARTICLE = "SOUL_FIRE_FLAME"
local AMBIENT_PARTICLE_COUNT = 2
local AMBIENT_PARTICLE_SPREAD = 0.5

local REPLANT_PARTICLE = "HAPPY_VILLAGER"
local REPLANT_PARTICLE_COUNT = 8
local REPLANT_PARTICLE_SPREAD = 0.4

-- =====================  MESSAGES  ============================

local MSG_ACTIVATE   = "&5&lGhost Chopper &8» &dSpectral lumberjacks emerge from the axe..."
local MSG_NO_TREES   = "&5&lGhost Chopper &8» &7The spirits find no trees nearby."
local MSG_LOW_DUR    = "&5&lGhost Chopper &8» &cThe axe is too damaged to channel spirits!"
local MSG_COOLDOWN   = "&5&lGhost Chopper &8» &7The spirits are still resting..."
local MSG_COMPLETE   = "&5&lGhost Chopper &8» &aThe spirits have finished their work."
local MSG_BUSY       = "&5&lGhost Chopper &8» &7The spirits are already chopping!"
local MSG_INHABITED  = "&5&lGhost Chopper &8» &cThe spirits sense dwellers nearby and refuse to chop."
local MSG_SCANNING   = "&5&lGhost Chopper &8» &7The spirits are surveying the area..."

-- =====================  TREE SCAN Y RANGE  ===================

local TREE_SCAN_Y_RANGE = 30

-- =====================  HELPERS  =============================

local function is_log(block_type)
    return LOG_TYPES[block_type] == true
end

local function is_leaf(block_type)
    return LEAF_TYPES[block_type] == true
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

--- Pre-compute the list of XZ columns for a circular scan.
--- Returns a flat list of {x=, z=} tables. Pure math — no block lookups.
local function build_column_list(cx, cz, radius, step)
    local columns = {}
    local r2 = radius * radius
    local fcx = math.floor(cx)
    local fcz = math.floor(cz)
    for bx = fcx - radius, fcx + radius, step do
        for bz = fcz - radius, fcz + radius, step do
            local dx = bx - cx
            local dz = bz - cz
            if dx * dx + dz * dz <= r2 then
                columns[#columns + 1] = { x = bx, z = bz }
            end
        end
    end
    return columns
end

-- Supplied content uses the optional authorized operations; direct Lua remains available.
local function allowed(context, x, y, z)
    local location = {world = context.world.name, x = x, y = y, z = z}
    return not em.location.is_protected(location) and not em.location.is_in_dungeon(location)
end

local function remove_block(context, x, y, z, expected, drop)
    if context.state.changed_blocks >= MAX_CHANGED_BLOCKS or not allowed(context, x, y, z) then return false end
    if context.action:break_block(x, y, z, expected, drop, 1) then
        context.state.changed_blocks = context.state.changed_blocks + 1
        return true
    end
    return false
end

--- Scan a single log column upward from (bx, by, bz).
local function scan_tree_column(world, bx, by, bz)
    local blocks = {}
    local y = by
    while true do
        local block = world:get_block_at(bx, y, bz)
        if is_log(block) then
            blocks[#blocks + 1] = { x = bx, y = y, z = bz }
            y = y + 1
        else
            break
        end
    end
    return blocks
end

--- Collect leaf blocks in a 5×4×5 box around a position.
local function collect_nearby_leaves(world, lx, ly, lz)
    local leaves = {}
    for ox = -2, 2 do
        for oy = -1, 2 do
            for oz = -2, 2 do
                local block = world:get_block_at(lx + ox, ly + oy, lz + oz)
                if is_leaf(block) then
                    leaves[#leaves + 1] = { x = lx + ox, y = ly + oy, z = lz + oz }
                end
            end
        end
    end
    return leaves
end

--- Clear any log blocks within BRANCH_CLEAR_RADIUS on the given Y level.
--- The center block (cx, cy, cz) is excluded (already handled by caller).
--- No item drops — just set to air with a small particle puff.
local function clear_branch_logs(context, cx, cy, cz)
    local world = context.world
    local r = BRANCH_CLEAR_RADIUS
    local r2 = r * r
    for ox = -r, r do
        for oz = -r, r do
            if not (ox == 0 and oz == 0) then
                if ox * ox + oz * oz <= r2 then
                    local bx = cx + ox
                    local bz = cz + oz
                    local block = world:get_block_at(bx, cy, bz)
                    if is_log(block) and remove_block(context, bx, cy, bz, block) then
                        world:spawn_particle(
                            CHOP_PARTICLE,
                            bx + 0.5, cy + 0.5, bz + 0.5,
                            3, 0.2, 0.2, 0.2, 0.01
                        )
                    end
                end
            end
        end
    end
end

--- Clear branch logs on the center Y level AND extra Y levels above.
--- Used for the topmost trunk block of each tree to catch crown branches
--- that sit higher than the center column (dark oak, jungle, etc.).
local function clear_crown_logs(context, cx, cy, cz)
    local world = context.world
    -- Clear the current Y level (same as normal branch clear)
    clear_branch_logs(context, cx, cy, cz)

    -- Clear extra Y levels above for crown branches
    local r = BRANCH_CLEAR_RADIUS
    local r2 = r * r
    for extra_y = 1, CROWN_EXTRA_Y do
        local check_y = cy + extra_y
        for ox = -r, r do
            for oz = -r, r do
                if ox * ox + oz * oz <= r2 then
                    local bx = cx + ox
                    local bz = cz + oz
                    local block = world:get_block_at(bx, check_y, bz)
                    if is_log(block) and remove_block(context, bx, check_y, bz, block) then
                        world:spawn_particle(
                            CHOP_PARTICLE,
                            bx + 0.5, check_y + 0.5, bz + 0.5,
                            3, 0.2, 0.2, 0.2, 0.01
                        )
                    end
                end
            end
        end
    end
end

--- Try to plant a sapling at the tree's base position.
--- Checks base_y, base_y+1, … up to base_y+REPLANT_MAX_OFFSET until
--- it finds a valid spot where:
---   • the candidate block is air
---   • the block below is in PLANTABLE_GROUND
--- Returns true if a sapling was placed.
local function try_plant_sapling(context, base_x, base_y, base_z, log_type)
    local world = context.world
    if not REPLANT_ENABLED then return false end

    local sapling = LOG_SAPLING_MAP[log_type]
    if not sapling then return false end

    for offset = 0, REPLANT_MAX_OFFSET do
        local py = base_y + offset
        local here  = world:get_block_at(base_x, py, base_z)
        local below = world:get_block_at(base_x, py - 1, base_z)

        if here == "air" and PLANTABLE_GROUND[below]
            and context.state.changed_blocks < MAX_CHANGED_BLOCKS
            and allowed(context, base_x, py, base_z)
            and context.action:place_block(base_x, py, base_z, here, "minecraft:" .. sapling) then
            context.state.changed_blocks = context.state.changed_blocks + 1
            world:play_sound(
                REPLANT_SOUND,
                base_x + 0.5, py + 0.5, base_z + 0.5,
                REPLANT_SOUND_VOLUME, REPLANT_SOUND_PITCH
            )
            world:spawn_particle(
                REPLANT_PARTICLE,
                base_x + 0.5, py + 0.5, base_z + 0.5,
                REPLANT_PARTICLE_COUNT,
                REPLANT_PARTICLE_SPREAD, REPLANT_PARTICLE_SPREAD, REPLANT_PARTICLE_SPREAD,
                0.02
            )
            return true
        end
    end

    return false
end

--- Spawn the spirit entity at a location.
local function spawn_spirit(context, x, y, z)
    local world = context.world
    if not is_log(world:get_block_at(math.floor(x), math.floor(y), math.floor(z))) then return nil end
    local spirit = world:spawn_entity(SPIRIT_ENTITY_TYPE, x, y + SPIRIT_Y_OFFSET, z)
    if spirit then
        if not context.action:own_entity(spirit.uuid) then spirit:remove(); return nil end
        spirit:set_ai(false)
        spirit:set_silent(true)
        spirit:set_invulnerable(true)
        spirit:set_gravity(false)
        if SPIRIT_GLOWING then
            spirit:set_glowing(true)
        end
        spirit:add_potion_effect("INVISIBILITY", 1200, 0)
    end
    return spirit
end

--- Teleport the spirit to hover above a target block.
local function move_spirit(spirit, world, x, y, z)
    if spirit and spirit.is_valid and not spirit.is_dead and is_log(world:get_block_at(x, y, z)) then
        spirit:teleport({
            x = x + 0.5,
            y = y + SPIRIT_Y_OFFSET,
            z = z + 0.5,
            world = world.name,
        })
    end
end

--- Remove the spirit entity from the world.
local function remove_spirit(spirit)
    if spirit and spirit.is_valid and not spirit.is_dead then
        spirit:remove()
    end
end

--- Mark nearby columns as visited so multi-trunk trees (jungle 2×2,
--- dark oak 2×2, etc.) are not queued multiple times.
--- Called after discovering a tree trunk at (tx, base_y, tz).
--- Checks columns within BRANCH_CLEAR_RADIUS — any that also have a
--- log at base_y with no log below are clearly part of the same tree
--- and get marked visited so the scan skips them.
local function mark_nearby_trunk_columns(world, visited, tx, base_y, tz)
    local r = BRANCH_CLEAR_RADIUS
    local r2 = r * r
    for ox = -r, r do
        for oz = -r, r do
            if not (ox == 0 and oz == 0) then
                if ox * ox + oz * oz <= r2 then
                    local nx = tx + ox
                    local nz = tz + oz
                    local k = nx .. "," .. nz
                    if not visited[k] then
                        local block = world:get_block_at(nx, base_y, nz)
                        if is_log(block) then
                            local below = world:get_block_at(nx, base_y - 1, nz)
                            if not is_log(below) then
                                visited[k] = true
                            end
                        end
                    end
                end
            end
        end
    end
end

-- =========================================================================
--  Chunked scanning pipeline
-- =========================================================================
-- Both the civilization check and the tree scan are split across ticks so
-- no single callback exceeds the 50 ms execution limit.
--
-- Flow:
--   on_shift_right_click  (instant — just guards + kicks off pipeline)
--     → chunked civilization scan  (run_repeating, N columns/tick)
--       → if inhabited: abort
--       → if clear: chunked tree scan  (run_repeating, N columns/tick)
--         → if no trees: abort
--         → start chopping  (existing run_repeating logic, unchanged)
-- =========================================================================

--- Start the civilization scan. Calls on_done(inhabited) when finished.
local function start_civ_scan(context, cx, cy, cz, on_done)
    local columns = build_column_list(cx, cz, CIVILIZATION_SCAN_RADIUS, CIVILIZATION_SAMPLE_STEP)
    local col_idx = 1
    local score = 0
    local min_y = math.floor(cy) - CIVILIZATION_Y_RANGE
    local max_y = math.floor(cy) + CIVILIZATION_Y_RANGE

    context.state.scan_task = context.scheduler:run_repeating(0, 1, function(ctx)
        local limit = math.min(col_idx + CIV_COLUMNS_PER_TICK - 1, #columns)
        for i = col_idx, limit do
            local col = columns[i]
            for by = min_y, max_y do
                local block = ctx.world:get_block_at(col.x, by, col.z)
                local weight = CIVILIZATION_BLOCKS[block]
                if weight then
                    score = score + weight
                    if score >= CIVILIZATION_THRESHOLD then
                        ctx.scheduler:cancel(ctx.state.scan_task)
                        ctx.state.scan_task = nil
                        on_done(true)
                        return
                    end
                end
            end
        end
        col_idx = limit + 1
        if col_idx > #columns then
            ctx.scheduler:cancel(ctx.state.scan_task)
            ctx.state.scan_task = nil
            on_done(false)
        end
    end)
end

--- Start the tree scan. Calls on_done(trees) when finished.
--- Each tree entry stores its base log type for sapling replanting.
--- After finding a tree trunk, marks all nearby columns within
--- BRANCH_CLEAR_RADIUS as visited so multi-trunk trees (jungle 2×2,
--- dark oak 2×2) are only queued once.
local function start_tree_scan(context, cx, cy, cz, on_done)
    local columns = build_column_list(cx, cz, SCAN_RADIUS, 1)
    local col_idx = 1
    local trees = {}
    local visited = {}
    local min_y = math.floor(cy) - TREE_SCAN_Y_RANGE
    local max_y = math.floor(cy) + TREE_SCAN_Y_RANGE

    context.state.scan_task = context.scheduler:run_repeating(0, 1, function(ctx)
        local limit = math.min(col_idx + TREE_COLUMNS_PER_TICK - 1, #columns)
        for i = col_idx, limit do
            local col = columns[i]
            local k = col.x .. "," .. col.z
            if not visited[k] then
                for by = min_y, max_y do
                    local block = ctx.world:get_block_at(col.x, by, col.z)
                    if is_log(block) then
                        local below = ctx.world:get_block_at(col.x, by - 1, col.z)
                        if not is_log(below) then
                            visited[k] = true
                            local tree = scan_tree_column(ctx.world, col.x, by, col.z)
                            if #tree >= 2 then
                                -- Store the base log type for sapling replanting
                                tree.base_log_type = block
                                trees[#trees + 1] = tree
                                if #trees >= MAX_TREES then
                                    ctx.scheduler:cancel(ctx.state.scan_task)
                                    ctx.state.scan_task = nil
                                    on_done(trees)
                                    return
                                end
                                -- Mark nearby trunk columns as visited so
                                -- multi-trunk trees are only queued once
                                mark_nearby_trunk_columns(ctx.world, visited, col.x, by, col.z)
                            end
                            break
                        end
                    end
                end
            end
        end
        col_idx = limit + 1
        if col_idx > #columns then
            ctx.scheduler:cancel(ctx.state.scan_task)
            ctx.state.scan_task = nil
            on_done(trees)
        end
    end)
end

--- Begin the chopping phase after scanning is complete.
local function start_chopping(context, trees)
    local player = context.player

    if not context.cooldowns:global_ready()
        or not context.item:use_durability_percentage(DURABILITY_COST, false) then context.action:stop(); return end
    context.cooldowns:set_global(COOLDOWN_TICKS)

    -- Activation feedback
    player:send_message(MSG_ACTIVATE)
    local loc = player.current_location
    context.world:play_sound(ACTIVATE_SOUND, loc.x, loc.y, loc.z, ACTIVATE_SOUND_VOLUME, ACTIVATE_SOUND_PITCH)
    context.world:spawn_particle(GHOST_PARTICLE, loc.x, loc.y + 1.5, loc.z, 15, 2, 1, 2, 0.02)
    player:show_title("&5Ghost Chopper", "&dThe spirits awaken...", 5, 30, 10)


    -- Build a flat queue: all trees, each tree bottom-to-top.
    -- Tag each block with is_tree_top and carry the base position +
    -- log type on the tree-top entry so we can replant when done.
    local queue = {}
    for _, tree in ipairs(trees) do
        local base = tree[1]
        local base_log_type = tree.base_log_type
        for j, block in ipairs(tree) do
            queue[#queue + 1] = {
                x = block.x,
                y = block.y,
                z = block.z,
                is_tree_top  = (j == #tree),
                -- Replanting info (only used on tree-top entries)
                base_x        = base.x,
                base_y        = base.y,
                base_z        = base.z,
                base_log_type = base_log_type,
            }
        end
    end

    context.state.chopping = true
    local index = 1

    -- Spawn the spirit entity at the first target
    local first = queue[1]
    context.state.spirit = spawn_spirit(
        context,
        first.x + 0.5, first.y, first.z + 0.5
    )

    -- Ambient ghost particles swirling around the current target
    context.state.ambient_task = context.scheduler:run_repeating(0, 10, function(amb_ctx)
        if not amb_ctx.state.chopping then return end
        if index > #queue then return end
        local target = queue[index]

        move_spirit(amb_ctx.state.spirit, amb_ctx.world, target.x, target.y, target.z)

        for angle = 0, 300, 60 do
            local rad = math.rad(angle + (index * 30))
            local px = target.x + 0.5 + math.cos(rad) * 1.2
            local pz = target.z + 0.5 + math.sin(rad) * 1.2
            amb_ctx.world:spawn_particle(
                AMBIENT_PARTICLE,
                px, target.y + 0.5, pz,
                AMBIENT_PARTICLE_COUNT,
                AMBIENT_PARTICLE_SPREAD, AMBIENT_PARTICLE_SPREAD, AMBIENT_PARTICLE_SPREAD,
                0.01
            )
        end
    end)

    -- Chop one block per CHOP_INTERVAL
    context.state.chop_task = context.scheduler:run_repeating(10, CHOP_INTERVAL, function(chop_ctx)
        if index > #queue or chop_ctx.state.changed_blocks >= MAX_CHANGED_BLOCKS then
            -- Completed work is retained on the approved block cap — clean up
            chop_ctx.state.chopping = false
            if chop_ctx.state.chop_task then
                chop_ctx.scheduler:cancel(chop_ctx.state.chop_task)
                chop_ctx.state.chop_task = nil
            end
            if chop_ctx.state.ambient_task then
                chop_ctx.scheduler:cancel(chop_ctx.state.ambient_task)
                chop_ctx.state.ambient_task = nil
            end

            local sp = chop_ctx.state.spirit
            if sp and sp.is_valid and not sp.is_dead then
                local sp_loc = sp.current_location
                chop_ctx.world:spawn_particle(
                    GHOST_PARTICLE,
                    sp_loc.x, sp_loc.y, sp_loc.z,
                    12, 0.5, 0.5, 0.5, 0.04
                )
            end
            remove_spirit(chop_ctx.state.spirit)
            chop_ctx.state.spirit = nil

            local p = chop_ctx.player
            if p and p.is_alive then
                p:send_message(MSG_COMPLETE)
                local ploc = p.current_location
                chop_ctx.world:play_sound(COMPLETE_SOUND, ploc.x, ploc.y, ploc.z, COMPLETE_SOUND_VOLUME, COMPLETE_SOUND_PITCH)
                chop_ctx.world:spawn_particle(GHOST_PARTICLE, ploc.x, ploc.y + 1.5, ploc.z, 20, 1.5, 1, 1.5, 0.03)
            end
            return
        end

        local target = queue[index]
        local tx, ty, tz = target.x, target.y, target.z

        local current_block = chop_ctx.world:get_block_at(tx, ty, tz)
        if not is_log(current_block) then
            -- Block already gone (cleared by branch sweep or another source).
            -- If this was a tree-top, still attempt replanting at the base.
            if target.is_tree_top then
                try_plant_sapling(
                    chop_ctx,
                    target.base_x, target.base_y, target.base_z,
                    target.base_log_type
                )
            end
            index = index + 1
            return
        end

        -- Emit a trunk drop only after the unchanged block was actually removed.
        if not remove_block(chop_ctx, tx, ty, tz, current_block, LOG_DROP_MAP[current_block]) then
            index = index + 1
            return
        end

        chop_ctx.world:play_sound(
            CHOP_SOUND,
            tx + 0.5, ty + 0.5, tz + 0.5,
            CHOP_SOUND_VOLUME, CHOP_SOUND_PITCH
        )

        chop_ctx.world:spawn_particle(
            CHOP_PARTICLE,
            tx + 0.5, ty + 0.5, tz + 0.5,
            CHOP_PARTICLE_COUNT,
            CHOP_PARTICLE_SPREAD, CHOP_PARTICLE_SPREAD, CHOP_PARTICLE_SPREAD,
            0.02
        )

        chop_ctx.world:spawn_particle(
            GHOST_PARTICLE,
            tx + 0.5, ty + 1.0, tz + 0.5,
            GHOST_PARTICLE_COUNT,
            GHOST_PARTICLE_SPREAD, 0.6, GHOST_PARTICLE_SPREAD,
            0.02
        )

        -- Branch / crown clearing + replanting:
        -- For most logs, clear matching logs on the same Y level.
        -- For the TOPMOST log of each tree, also sweep extra Y levels
        -- above to catch crown branches, then plant a sapling at the base.
        if target.is_tree_top then
            clear_crown_logs(chop_ctx, tx, ty, tz)
            try_plant_sapling(
                chop_ctx,
                target.base_x, target.base_y, target.base_z,
                target.base_log_type
            )
        else
            clear_branch_logs(chop_ctx, tx, ty, tz)
        end

        -- Leaf cleanup: if no log above, clear nearby leaves
        local above_block = chop_ctx.world:get_block_at(tx, ty + 1, tz)
        if not is_log(above_block) then
            local leaves = collect_nearby_leaves(chop_ctx.world, tx, ty, tz)
            for _, leaf in ipairs(leaves) do
                local current_leaf = chop_ctx.world:get_block_at(leaf.x, leaf.y, leaf.z)
                if is_leaf(current_leaf) and remove_block(chop_ctx, leaf.x, leaf.y, leaf.z, current_leaf) then
                chop_ctx.world:spawn_particle(
                    "HAPPY_VILLAGER",
                    leaf.x + 0.5, leaf.y + 0.5, leaf.z + 0.5,
                    2, 0.3, 0.3, 0.3, 0
                )
                end
            end
        end

        index = index + 1
    end)
end

-- =============================================================

return {
    api_version = 1,

    -- Main activation: Shift + Right-click
    -- This hook returns almost instantly. Heavy scanning is
    -- deferred to scheduled callbacks that process a limited
    -- number of block columns per tick.
    on_shift_right_click = function(context)
        local player = context.player
        if not player or not context.item then return end

        -- Region / dungeon gate — block the power in protected areas.
        local block_reason = get_block_reason(player)
        if block_reason then
          -- Throttled console warning (once per 10 seconds per player).
          if context.cooldowns:check_local("region_block_log", REGION_LOG_COOLDOWN_TICKS) then
            if block_reason == "protected" then
              context.log:warn("[Ghost Chopper] Blocked for player " .. player.name
                .. " — inside a WorldGuard/GriefPrevention protected region.")
            else
              context.log:warn("[Ghost Chopper] Blocked for player " .. player.name
                .. " — inside an EliteMobs dungeon.")
            end
          end
          return
        end

        if context.event then
            context.event:cancel()
        end

        if context.action:has_previous() then
            player:send_message(MSG_BUSY)
            return
        end
        context.state.changed_blocks = 0

        -- Guard: cooldown
        if not context.cooldowns:global_ready() then
            player:send_message(MSG_COOLDOWN)
            return
        end

        -- Guard: durability
        local dur_pct = context.item:get_durability_percentage()
        if not dur_pct or dur_pct < DURABILITY_MINIMUM then
            player:send_message(MSG_LOW_DUR)
            local ploc = player.current_location
            context.world:play_sound("ENTITY_ITEM_BREAK", ploc.x, ploc.y, ploc.z, 1.0, 1.0)
            return
        end

        -- ====  Begin async scanning pipeline  ====
        context.state.scanning = true
        local loc = player.current_location
        player:send_message(MSG_SCANNING)

        -- Phase 1: civilization scan (chunked across ticks)
        start_civ_scan(context, loc.x, loc.y, loc.z, function(inhabited)
            if inhabited then
                context.state.scanning = false
                player:send_message(MSG_INHABITED)
                context.world:play_sound(INHABITED_SOUND, loc.x, loc.y, loc.z, INHABITED_SOUND_VOLUME, INHABITED_SOUND_PITCH)
                context.world:spawn_particle(GHOST_PARTICLE, loc.x, loc.y + 1.5, loc.z, 8, 1, 1, 1, 0.05)
                return
            end

            -- Phase 2: tree scan (chunked across ticks)
            start_tree_scan(context, loc.x, loc.y, loc.z, function(trees)
                context.state.scanning = false

                if #trees == 0 then
                    player:send_message(MSG_NO_TREES)
                    context.world:play_sound("ENTITY_GHAST_AMBIENT", loc.x, loc.y, loc.z, 0.6, 1.5)
                    context.world:spawn_particle(GHOST_PARTICLE, loc.x, loc.y + 1, loc.z, 5, 1, 1, 1, 0.01)
                    return
                end

                -- Phase 3: chop!
                start_chopping(context, trees)
            end)
        end)
    end,
}