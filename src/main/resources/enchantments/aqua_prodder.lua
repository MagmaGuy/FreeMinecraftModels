-- ============================================================
-- Aqua Dome — Underwater glass hemisphere shelter
-- Shift + Right-click while standing on the ocean floor
-- to create a temporary air-filled glass dome.
--
-- NOTE: The dome persists only while the item is equipped.
-- Unequipping the item will immediately remove the dome.
-- ============================================================

-- ===================== CONFIGURABLE SETTINGS =====================

-- Dome shape
local DOME_RADII             = { 6, 5, 4, 3 }   -- radii to try, largest first

-- Timing (in ticks, 20 ticks = 1 second)
local DOME_DURATION_TICKS    = 1200              -- dome lifetime (60 seconds)
local COOLDOWN_TICKS         = 1400               -- cooldown in ticks

-- Durability
local DURABILITY_COST        = 0.05              -- fraction of max durability (5%)
local CAN_BREAK_ITEM         = false             -- whether the item can break from use

-- Dome block
local DOME_MATERIAL          = "GLASS"           -- material for the dome shell

-- Messages (all shown as action bar)
local ACTIVATE_MSG           = "&aAqua Dome activated! (radius %d)"
local COOLDOWN_MSG           = "&cAqua Dome is on cooldown!"
local NOT_IN_WATER_MSG       = "&cYou must be in water to use this!"
local NOT_ON_GROUND_MSG      = "&cYou must be standing on the ocean floor!"
local OBSTRUCTION_MSG        = "&cCannot place dome, something is in the way!"
local ALREADY_ACTIVE_MSG     = "&cA dome is already active!"
local LOW_DURABILITY_MSG     = "&cItem durability too low to use!"
local DOME_EXPIRED_MSG       = "&7Aqua Dome has expired."
local DOME_REMOVED_MSG       = "&7Aqua Dome removed (item unequipped)."
local PREVIOUS_RESTORED_MSG  = "&7Previous Aqua Dome blocks restored."

-- Sounds
local ACTIVATE_SOUND         = "BLOCK_BEACON_ACTIVATE"
local ACTIVATE_VOLUME        = 1.0
local ACTIVATE_PITCH         = 1.2
local EXPIRE_SOUND           = "BLOCK_GLASS_BREAK"
local EXPIRE_VOLUME          = 1.0
local EXPIRE_PITCH           = 0.8

-- Particles
local ACTIVATE_PARTICLE      = "BUBBLE"
local ACTIVATE_PARTICLE_COUNT = 30
local ACTIVATE_PARTICLE_SPREAD = 1.0

-- Console message cooldown (ticks). 10 seconds = 200 ticks.
local REGION_LOG_COOLDOWN_TICKS = 200

-- ===================== REPLACEABLE BLOCK SET =====================
local REPLACEABLE = {}
for _, name in ipairs({
    "WATER", "AIR", "CAVE_AIR", "VOID_AIR",
    "SEAGRASS", "TALL_SEAGRASS",
    "KELP", "KELP_PLANT",
    "BUBBLE_COLUMN",
    "SEA_PICKLE",
    "TUBE_CORAL", "BRAIN_CORAL", "BUBBLE_CORAL", "FIRE_CORAL", "HORN_CORAL",
    "DEAD_TUBE_CORAL", "DEAD_BRAIN_CORAL", "DEAD_BUBBLE_CORAL", "DEAD_FIRE_CORAL", "DEAD_HORN_CORAL",
    "TUBE_CORAL_FAN", "BRAIN_CORAL_FAN", "BUBBLE_CORAL_FAN", "FIRE_CORAL_FAN", "HORN_CORAL_FAN",
    "DEAD_TUBE_CORAL_FAN", "DEAD_BRAIN_CORAL_FAN", "DEAD_BUBBLE_CORAL_FAN", "DEAD_FIRE_CORAL_FAN", "DEAD_HORN_CORAL_FAN",
    "TUBE_CORAL_WALL_FAN", "BRAIN_CORAL_WALL_FAN", "BUBBLE_CORAL_WALL_FAN", "FIRE_CORAL_WALL_FAN", "HORN_CORAL_WALL_FAN",
    "DEAD_TUBE_CORAL_WALL_FAN", "DEAD_BRAIN_CORAL_WALL_FAN", "DEAD_BUBBLE_CORAL_WALL_FAN", "DEAD_FIRE_CORAL_WALL_FAN", "DEAD_HORN_CORAL_WALL_FAN",
}) do
    REPLACEABLE[name] = true
end

local function is_replaceable(block_type)
    return REPLACEABLE[string.upper(block_type)] == true
end

-- ===================== NON-SOLID CHECK =====================
local NON_SOLID = { WATER = true, AIR = true, CAVE_AIR = true, VOID_AIR = true }
local function is_non_solid(block_type)
    return NON_SOLID[string.upper(block_type)] == true
end

-- ===================== HEMISPHERE GEOMETRY =====================
local function compute_dome_offsets(radius)
    local shell = {}
    local interior = {}
    for dx = -radius, radius do
        for dy = 0, radius do
            for dz = -radius, radius do
                local dist = math.sqrt(dx * dx + dy * dy + dz * dz)
                if dist <= radius and dist >= radius - 1 then
                    table.insert(shell, { dx = dx, dy = dy, dz = dz })
                elseif dist < radius - 1 then
                    table.insert(interior, { dx = dx, dy = dy, dz = dz })
                end
            end
        end
    end
    return shell, interior
end

-- Precompute offsets for all candidate radii
local DOME_DATA = {}
for _, r in ipairs(DOME_RADII) do
    local shell, interior = compute_dome_offsets(r)
    DOME_DATA[r] = { shell = shell, interior = interior }
end

-- ===================== OBSTRUCTION CHECK =====================
local function can_place_dome(context, cx, cy, cz, data)
    for _, group in ipairs({data.shell, data.interior}) do
        for _, off in ipairs(group) do
            if not is_replaceable(context.world:get_block_at(cx + off.dx, cy + off.dy, cz + off.dz)) then
                return false
            end
        end
    end
    return true
end

local function can_own_dome(context, cx, cy, cz, data)
    for _, group in ipairs({data.shell, data.interior}) do
        for _, off in ipairs(group) do
            local x, y, z = cx + off.dx, cy + off.dy, cz + off.dz
            local location = {world = context.player.world, x = x, y = y, z = z}
            if not context.action:can_own_block(x, y, z) or em.location.is_protected(location)
                or em.location.is_in_dungeon(location) then return false end
        end
    end
    return true
end

-- ===================== REGION / DUNGEON CHECK =====================
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

-- ===================== MAIN SCRIPT =====================
return {
    api_version = 1,

    on_shift_right_click = function(context)
        local player = context.player
        if not player or not context.item then return end

        -- Region / dungeon gate — block the power in protected areas.
        local block_reason = get_block_reason(player)
        if block_reason then
            -- Throttled console warning (once per 10 seconds per player).
            if context.cooldowns:check_local("region_block_log", REGION_LOG_COOLDOWN_TICKS) then
                if block_reason == "protected" then
                    context.log:warn("[Aqua Dome] Blocked for player " .. player.name
                        .. " — inside a WorldGuard/GriefPrevention protected region.")
                else
                    context.log:warn("[Aqua Dome] Blocked for player " .. player.name
                        .. " — inside an EliteMobs dungeon.")
                end
            end
            return
        end

        if context.event then context.event:cancel() end

        -- Check cooldown
        if not context.cooldowns:global_ready() then
            player:show_action_bar(COOLDOWN_MSG)
            return
        end

        -- Prevent stacking domes
        if context.state.dome_active then
            player:show_action_bar(ALREADY_ACTIVE_MSG)
            return
        end

        -- Check durability
        local dur_info = context.item:get_durability()
        if dur_info then
            local pct = dur_info.current / dur_info.max
            if pct < DURABILITY_COST + 0.01 then
                player:show_action_bar(LOW_DURABILITY_MSG)
                return
            end
        end

        -- Player location
        local loc = player.current_location
        local px = math.floor(loc.x)
        local py = math.floor(loc.y)
        local pz = math.floor(loc.z)

        -- Must be in water
        local feet_block = context.world:get_block_at(px, py, pz)
        if string.upper(feet_block) ~= "WATER" then
            player:show_action_bar(NOT_IN_WATER_MSG)
            return
        end

        -- Must be on the ocean floor (solid block below)
        local below = context.world:get_block_at(px, py - 1, pz)
        if is_non_solid(below) then
            player:show_action_bar(NOT_ON_GROUND_MSG)
            return
        end

        -- Try each radius largest→smallest
        local chosen_r = nil
        local chosen_data = nil
        for _, r in ipairs(DOME_RADII) do
            local data = DOME_DATA[r]
            if can_place_dome(context, px, py, pz, data) then
                chosen_r = r
                chosen_data = data
                break
            end
        end

        if not chosen_r then
            player:show_action_bar(OBSTRUCTION_MSG)
            return
        end

        -- Select geometry first. A denied, unloaded or already-owned block aborts
        -- the selected dome instead of silently choosing a smaller protected footprint.
        if not can_own_dome(context, px, py, pz, chosen_data) then
            player:show_action_bar(OBSTRUCTION_MSG)
            return
        end

        -- Acquire every conditional lease before charging. An error or failed lease
        -- closes the action and restores the partial dome through the normal owner.
        for _, group in ipairs({chosen_data.shell, chosen_data.interior}) do
            local replacement = group == chosen_data.shell and string.lower(DOME_MATERIAL) or "minecraft:air"
            for _, off in ipairs(group) do
                if not context.action:temporary_block(px + off.dx, py + off.dy, pz + off.dz, replacement) then
                    context.action:stop()
                    return
                end
            end
        end
        if not context.item:use_durability_percentage(DURABILITY_COST, CAN_BREAK_ITEM) then
            context.action:stop()
            return
        end
        context.cooldowns:set_global(COOLDOWN_TICKS)

        -- Sound & particles
        context.world:play_sound(ACTIVATE_SOUND, loc.x, loc.y, loc.z,
            ACTIVATE_VOLUME, ACTIVATE_PITCH)
        context.world:spawn_particle(ACTIVATE_PARTICLE,
            loc.x, loc.y + 1, loc.z,
            ACTIVATE_PARTICLE_COUNT,
            ACTIVATE_PARTICLE_SPREAD, ACTIVATE_PARTICLE_SPREAD, ACTIVATE_PARTICLE_SPREAD, 0)

        -- Action bar
        player:show_action_bar(string.format(ACTIVATE_MSG, chosen_r))

        context.scheduler:run_later(DOME_DURATION_TICKS, function()
            context.world:play_sound(EXPIRE_SOUND, loc.x, loc.y, loc.z, EXPIRE_VOLUME, EXPIRE_PITCH)
            player:show_action_bar(DOME_EXPIRED_MSG)
            context.action:stop()
        end)
    end,
}
