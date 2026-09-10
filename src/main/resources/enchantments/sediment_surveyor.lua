-- ============================================
-- Power Excavator — FreeMinecraftModels Item Script
-- ============================================
-- Crouch + right-click to excavate 3 rows of shovel-efficient blocks
-- forward from the player. Includes grief protection, durability cost,
-- and a 5-minute cooldown. Blocks are removed column-by-column for a
-- wave animation effect.
-- ============================================

-- ============================================
-- SETTINGS
-- ============================================

local POWER_NAME            = "Power Excavator"

-- Cooldown
local COOLDOWN_KEY          = "excavate"   -- key used with context.cooldowns
local COOLDOWN_TICKS        = 3000         -- (20 ticks = 1 second)

-- Excavation shape
local MIDDLE_ROW_LENGTH     = 14      -- columns forward for the centre row
local SIDE_ROW_LENGTH       = 12      -- columns forward for each side row
local COLUMN_DIG_INTERVAL   = 3       -- ticks between each column (lower = faster wave)

-- Durability  (scale: 0.0–1.0  where 1.0 = 100%)
-- NOTE: if get/use_durability_percentage uses 0–100 scale instead, multiply these by 100
local DURABILITY_DRAIN_PCT  = 0.02    -- 2% drained per use
local MIN_DURABILITY_PCT    = 0.03    -- refuse activation at or below 3%

-- Grief protection
local GRIEF_CHECK_RADIUS    = 20      -- horizontal block radius
local GRIEF_MAX_BLOCKS      = 2       -- refuse if MORE than this many player-home blocks found
local GRIEF_SCAN_Y_MIN      = -1      -- Y offset relative to player's floor block
local GRIEF_SCAN_Y_MAX      = 3

-- Console message cooldown (ticks). 10 seconds = 200 ticks.
local REGION_LOG_COOLDOWN_TICKS = 200

-- Sounds (Bukkit Sound enum names)
local SOUND_ACTIVATE_SWEEP  = "ENTITY_PLAYER_ATTACK_SWEEP"
local SOUND_ACTIVATE_SHOVEL = "ITEM_SHOVEL_FLATTEN"
local SOUND_DIG_BLOCK       = "BLOCK_GRAVEL_BREAK"
local SOUND_DIG_VOLUME      = 0.4
local SOUND_DIG_PITCH_MIN   = 0.8
local SOUND_DIG_PITCH_MAX   = 1.1
local SOUND_COMPLETE        = "ENTITY_EXPERIENCE_ORB_PICKUP"
local SOUND_BLOCKED_GRIEF   = "BLOCK_STONE_HIT"
local SOUND_BLOCKED_DURA    = "BLOCK_ANVIL_LAND"

-- Particles per dug block
local PARTICLE_DIG          = "POOF"
local PARTICLE_DIG_COUNT    = 4
local PARTICLE_DIG_SPREAD   = 0.25
local PARTICLE_DIG_SPEED    = 0.04

-- Activation particle (swept forward along the dig path at cast time)
local PARTICLE_ACTIVATE     = "SWEEP_ATTACK"
local PARTICLE_ACT_COUNT    = 2

-- Completion particle burst
local PARTICLE_COMPLETE     = "HAPPY_VILLAGER"
local PARTICLE_COMP_COUNT   = 15

-- ============================================
-- SHOVEL-EFFICIENT BLOCKS
-- ============================================

local SHOVEL_BLOCKS = {
    ["grass_block"]          = true,
    ["dirt"]                 = true,
    ["coarse_dirt"]          = true,
    ["rooted_dirt"]          = true,
    ["podzol"]               = true,
    ["mycelium"]             = true,
    ["gravel"]               = true,
    ["sand"]                 = true,
    ["red_sand"]             = true,
    ["clay"]                 = true,
    ["snow_block"]           = true,
    ["snow"]                 = true,
    ["soul_sand"]            = true,
    ["soul_soil"]            = true,
    ["mud"]                  = true,
    ["muddy_mangrove_roots"] = true,
    ["suspicious_sand"]      = true,
    ["suspicious_gravel"]    = true,
    ["farmland"]             = true,
    ["dirt_path"]            = true,
}

-- ============================================
-- GRIEF-PROTECTION BLOCKS
-- Beds (all 16 colours) + chest variants
-- ============================================

local PLAYER_HOME_BLOCKS = {
    -- Storage
    ["chest"]          = true,
    ["trapped_chest"]  = true,
    ["ender_chest"]    = true,
    ["barrel"]         = true,
    -- Beds (all 16 colours)
    ["white_bed"]      = true,
    ["orange_bed"]     = true,
    ["magenta_bed"]    = true,
    ["light_blue_bed"] = true,
    ["yellow_bed"]     = true,
    ["lime_bed"]       = true,
    ["pink_bed"]       = true,
    ["gray_bed"]       = true,
    ["light_gray_bed"] = true,
    ["cyan_bed"]       = true,
    ["purple_bed"]     = true,
    ["blue_bed"]       = true,
    ["brown_bed"]      = true,
    ["green_bed"]      = true,
    ["red_bed"]        = true,
    ["black_bed"]      = true,
    -- Doors (both halves counted by get_block_at; one door = 2 blocks)
    ["oak_door"]       = true,
    ["spruce_door"]    = true,
    ["birch_door"]     = true,
    ["jungle_door"]    = true,
    ["acacia_door"]    = true,
    ["dark_oak_door"]  = true,
    ["mangrove_door"]  = true,
    ["cherry_door"]    = true,
    ["bamboo_door"]    = true,
    ["crimson_door"]   = true,
    ["warped_door"]    = true,
    ["iron_door"]      = true,
}

-- ============================================
-- HELPERS
-- ============================================

-- Round float to nearest integer (for converting world-space offsets to block coords)
local function round(x)
    return math.floor(x + 0.5)
end

-- Returns true if MORE than GRIEF_MAX_BLOCKS player-home blocks are found
local function grief_check(world, ox, oy, oz)
    local count = 0
    local r2    = GRIEF_CHECK_RADIUS * GRIEF_CHECK_RADIUS
    for dx = -GRIEF_CHECK_RADIUS, GRIEF_CHECK_RADIUS do
        for dz = -GRIEF_CHECK_RADIUS, GRIEF_CHECK_RADIUS do
            if dx * dx + dz * dz <= r2 then
                for dy = GRIEF_SCAN_Y_MIN, GRIEF_SCAN_Y_MAX do
                    local bt = world:get_block_at(ox + dx, oy + dy, oz + dz)
                    if bt and PLAYER_HOME_BLOCKS[bt] then
                        count = count + 1
                        if count > GRIEF_MAX_BLOCKS then
                            return true
                        end
                    end
                end
            end
        end
    end
    return false
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

-- ============================================
-- SCRIPT
-- ============================================

return {
    api_version = 1,

    -- Trigger: crouch + right-click
    on_shift_right_click = function(context)

        local player = context.player
        if not player or not context.item then return end

        -- Region / dungeon gate — block the power in protected areas.
        local block_reason = get_block_reason(player)
        if block_reason then
            -- Throttled console warning (once per 10 seconds per player).
            if context.cooldowns:check_local("region_block_log", REGION_LOG_COOLDOWN_TICKS) then
                if block_reason == "protected" then
                    context.log:warn("[Power Excavator] Blocked for player " .. player.name
                        .. " — inside a WorldGuard/GriefPrevention protected region.")
                else
                    context.log:warn("[Power Excavator] Blocked for player " .. player.name
                        .. " — inside an EliteMobs dungeon.")
                end
            end
            return
        end

        -- Suppress default block interaction
        if context.event then context.event.cancel() end

        -- Guard: already excavating
        if context.state.is_active then
            context.player:show_action_bar("&e⛏ " .. POWER_NAME .. " is already in progress!", 40)
            return
        end

        -- Guard: cooldown active — check_local returns true only when ready, false when on cooldown
        if not context.cooldowns:local_ready(COOLDOWN_KEY) then
            local secs = math.ceil(context.cooldowns:local_remaining(COOLDOWN_KEY) / 20)
            context.player:show_action_bar(
                "&c⛏ " .. POWER_NAME .. " cooling down! &7(" .. secs .. "s)", 40)
            return
        end

        -- Guard: durability too low
        local dura_pct = context.item:get_durability_percentage()
        if dura_pct <= MIN_DURABILITY_PCT then
            context.player:show_action_bar(
                "&4⛏ Shovel too damaged! Repair before using " .. POWER_NAME .. ".", 60)
            local ploc = context.player.current_location
            context.world:play_sound(SOUND_BLOCKED_DURA, ploc.x, ploc.y, ploc.z, 0.8, 1.5)
            return
        end

        -- Resolve position and facing
        local loc    = context.player.current_location
        local base_x = math.floor(loc.x)
        local base_y = math.floor(loc.y)        -- block at the player's feet level (1 above floor)
        local base_z = math.floor(loc.z)

        local yaw_rad = math.rad(loc.yaw)
        local fwd_x   = -math.sin(yaw_rad)      -- raw forward unit vector (no cardinal snap)
        local fwd_z   =  math.cos(yaw_rad)
        local rgt_x   = -fwd_z                  -- 90° clockwise right in Minecraft's left-handed XZ
        local rgt_z   =  fwd_x

        -- Grief protection scan
        if grief_check(context.world, base_x, base_y, base_z) then
            context.player:show_action_bar(
                "&c⛏ Too close to a player structure! " .. POWER_NAME .. " blocked.", 80)
            context.world:play_sound(SOUND_BLOCKED_GRIEF, loc.x, loc.y, loc.z, 1.0, 0.7)
            return
        end

        -- All checks passed — commit ------------------------------------

        if not context.item:use_durability_percentage(DURABILITY_DRAIN_PCT, false) then return end
        context.cooldowns:set_local(COOLDOWN_TICKS, COOLDOWN_KEY)
        context.event:cancel()

        -- Activation audio
        context.world:play_sound(SOUND_ACTIVATE_SWEEP,  loc.x, loc.y, loc.z, 1.5, 0.55)
        context.world:play_sound(SOUND_ACTIVATE_SHOVEL, loc.x, loc.y, loc.z, 1.0, 1.0)

        -- Activation particle wave along the dig path
        for i = 1, 3 do
            context.world:spawn_particle(
                PARTICLE_ACTIVATE,
                loc.x + fwd_x * i, base_y + 1.0, loc.z + fwd_z * i,
                PARTICLE_ACT_COUNT, 0.3, 0.1, 0.3, 0.0)
        end

        -- Action bar: persists for the full dig duration
        local bar_ticks = MIDDLE_ROW_LENGTH * COLUMN_DIG_INTERVAL + 60
        context.player:show_action_bar(
            "&6&l⛏ " .. POWER_NAME .. "! &eDigging...", bar_ticks)

        -- Sequential column dig -------------------------------------------
        context.state.is_active = true
        local col = 0   -- closed-over counter; incremented by each callback

        -- Pre-compute last-column block centre for the completion burst
        local end_cx = round(base_x + fwd_x * MIDDLE_ROW_LENGTH)
        local end_cz = round(base_z + fwd_z * MIDDLE_ROW_LENGTH)

        local task = context.scheduler:run_repeating(1, COLUMN_DIG_INTERVAL, function()
            col = col + 1

            -- All columns done?
            if col > MIDDLE_ROW_LENGTH then
                context.state.is_active = false
                context.scheduler:cancel(context.state.dig_task)
                context.player:show_action_bar(
                    "&a&l⛏ " .. POWER_NAME .. " complete!", 60)
                context.world:play_sound(
                    SOUND_COMPLETE, end_cx + 0.5, base_y + 1, end_cz + 0.5, 1.0, 1.8)
                context.world:spawn_particle(
                    PARTICLE_COMPLETE,
                    end_cx + 0.5, base_y + 1, end_cz + 0.5,
                    PARTICLE_COMP_COUNT, 1.2, 0.6, 1.2, 0.05)
                return
            end

            -- Block coords for this column (raw float offset → rounded to nearest block)
            local cx = round(base_x + fwd_x * col)
            local cz = round(base_z + fwd_z * col)
            local any_dug = false

            -- Left side row (columns 1–SIDE_ROW_LENGTH only)
            if col <= SIDE_ROW_LENGTH then
                local lx      = round(base_x + fwd_x * col - rgt_x)
                local lz      = round(base_z + fwd_z * col - rgt_z)
                local left_bt = context.world:get_block_at(lx, base_y, lz)
                if left_bt and SHOVEL_BLOCKS[left_bt]
                    and context.action:break_block(lx, base_y, lz, left_bt) then
                    context.world:spawn_particle(
                        PARTICLE_DIG,
                        lx + 0.5, base_y + 0.7, lz + 0.5,
                        PARTICLE_DIG_COUNT,
                        PARTICLE_DIG_SPREAD, PARTICLE_DIG_SPREAD, PARTICLE_DIG_SPREAD,
                        PARTICLE_DIG_SPEED)
                    any_dug = true
                end
            end

            -- Centre row (all MIDDLE_ROW_LENGTH columns)
            local mid_bt = context.world:get_block_at(cx, base_y, cz)
            if mid_bt and SHOVEL_BLOCKS[mid_bt]
                    and context.action:break_block(cx, base_y, cz, mid_bt) then
                context.world:spawn_particle(
                    PARTICLE_DIG,
                    cx + 0.5, base_y + 0.7, cz + 0.5,
                    PARTICLE_DIG_COUNT + 1,
                    PARTICLE_DIG_SPREAD, PARTICLE_DIG_SPREAD, PARTICLE_DIG_SPREAD,
                    PARTICLE_DIG_SPEED)
                any_dug = true
            end

            -- Right side row (columns 1–SIDE_ROW_LENGTH only)
            if col <= SIDE_ROW_LENGTH then
                local rx       = round(base_x + fwd_x * col + rgt_x)
                local rz       = round(base_z + fwd_z * col + rgt_z)
                local right_bt = context.world:get_block_at(rx, base_y, rz)
                if right_bt and SHOVEL_BLOCKS[right_bt]
                    and context.action:break_block(rx, base_y, rz, right_bt) then
                    context.world:spawn_particle(
                        PARTICLE_DIG,
                        rx + 0.5, base_y + 0.7, rz + 0.5,
                        PARTICLE_DIG_COUNT,
                        PARTICLE_DIG_SPREAD, PARTICLE_DIG_SPREAD, PARTICLE_DIG_SPREAD,
                        PARTICLE_DIG_SPEED)
                    any_dug = true
                end
            end

            -- Randomised dig sound per column (only when something was actually removed)
            if any_dug then
                local pitch = SOUND_DIG_PITCH_MIN
                    + math.random() * (SOUND_DIG_PITCH_MAX - SOUND_DIG_PITCH_MIN)
                context.world:play_sound(
                    SOUND_DIG_BLOCK, cx, base_y, cz, SOUND_DIG_VOLUME, pitch)
            end
        end)

        context.state.dig_task = task
    end,
}