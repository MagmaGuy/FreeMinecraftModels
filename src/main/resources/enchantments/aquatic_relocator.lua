-- ============================================================
-- Relocate Fish — Aquatic Relocator Fishing Rod
-- Shift + Left-click to scan for nearby fish, push them
-- out of the water, then pull them toward the player.
-- ============================================================

-- ── Settings ────────────────────────────────────────────────
local POWER_NAME           = "&b&l⚓ Relocate Fish"

-- Scan & targeting
local SCAN_RADIUS          = 30       -- blocks to scan for fish
local FISH_TYPES = {                  -- entity types to target
  ["cod"]            = true,
  ["salmon"]         = true,
  ["tropical_fish"]  = true,
  ["pufferfish"]     = true,
  ["tadpole"]        = true,
}

-- Levitation phase (push-based, no potion effect)
local LEVITATE_SECONDS     = 5
local LEVITATE_TICKS       = LEVITATE_SECONDS * 20
local LEVITATE_PUSH_UP     = 0.05    -- upward push strength per impulse
local LEVITATE_PUSH_INTERVAL = 3     -- ticks between each upward push impulse

-- Pull phase
local PULL_STRENGTH        = 0.3     -- velocity per tick toward player
local PULL_Y_BOOST         = 0.10    -- extra upward velocity per pull tick
local PULL_DURATION_TICKS  = 40      -- how long the pull phase lasts
local PULL_INTERVAL_TICKS  = 4       -- ticks between each pull impulse

-- Durability
local DURABILITY_COST      = 0.10    -- fraction of max durability consumed per use
local MIN_DURABILITY       = 0.10    -- minimum remaining % to activate

-- Cooldown
local COOLDOWN_SECONDS     = 300     -- seconds before the power can be used again
local COOLDOWN_TICKS       = COOLDOWN_SECONDS * 20  -- 5 minutes = 6000 ticks

-- Action-bar messages
local MSG_ACTIVATE         = "&b&l⚓ Relocate Fish &7— Scanning..."
local MSG_FOUND            = "&b&l⚓ Relocate Fish &a— Found %d fish!"
local MSG_NO_FISH          = "&7No fish found within range."
local MSG_LOW_DURABILITY   = "&c&lNot enough durability to use this power!"
local MSG_COOLDOWN         = "&eRelocate Fish is recharging..."
local MSG_PULLING          = "&b&l⚓ Pulling fish toward you..."

-- Particles & sounds
local SCAN_PARTICLE        = "FISHING"
local LEVITATE_PARTICLE    = "BUBBLE_COLUMN_UP"
local PULL_PARTICLE        = "DOLPHIN"
local ACTIVATE_SOUND       = "ENTITY_FISHING_BOBBER_SPLASH"
local PULL_SOUND           = "ENTITY_DOLPHIN_SWIM"
local FAIL_SOUND           = "BLOCK_NOTE_BLOCK_BASS"

local SETTINGS = {
  -- Console message cooldown (ticks). 10 seconds = 200 ticks.
  region_log_cooldown_ticks = 200,
}

-- ── Helpers ─────────────────────────────────────────────────

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

-- ── Script ──────────────────────────────────────────────────
return {
  api_version = 1,

  -- Main power: Shift + Left-click
  on_shift_left_click = function(context)
    local player = context.player

    -- Region / dungeon gate — block the power in protected areas.
    local block_reason = get_block_reason(player)
    if block_reason then
      -- Throttled console warning (once per 10 seconds per player).
      if context.cooldowns:check_local("region_block_log", SETTINGS.region_log_cooldown_ticks) then
        if block_reason == "protected" then
          context.log:warn("[Relocate Fish] Blocked for player " .. player.name
            .. " — inside a WorldGuard/GriefPrevention protected region.")
        else
          context.log:warn("[Relocate Fish] Blocked for player " .. player.name
            .. " — inside an EliteMobs dungeon.")
        end
      end
      return
    end

    -- Guard: already running or on cooldown
    if context.state.active then return end
    if not context.cooldowns:global_ready() then
      context.player:show_action_bar(MSG_COOLDOWN, 40)
      return
    end

    -- Guard: durability check
    local dur_pct = context.item:get_durability_percentage()
    if dur_pct == nil or dur_pct <= MIN_DURABILITY then
      context.player:show_action_bar(MSG_LOW_DURABILITY, 40)
      local ploc = context.player.current_location
      context.world:play_sound(FAIL_SOUND, ploc.x, ploc.y, ploc.z, 0.8, 0.5)
      return
    end

    -- Notify player
    context.player:show_action_bar(MSG_ACTIVATE, 60)
    local ploc = context.player.current_location
    context.world:play_sound(ACTIVATE_SOUND, ploc.x, ploc.y, ploc.z, 0.2, 0.1)

    -- ── Scan for fish ───────────────────────────────────────
    local all_entities = context.world:get_nearby_entities(
      ploc.x, ploc.y, ploc.z, SCAN_RADIUS
    )

    local fish = {}
    for _, entity in ipairs(all_entities) do
      if FISH_TYPES[entity.entity_type] and entity:can_receive_hostile_effect(player.uuid)
        and not em.location.is_protected(entity.current_location)
        and context.action:temporary_gravity(entity.uuid, false) then
        table.insert(fish, entity)
      end
    end

    if #fish == 0 then
      context.player:show_action_bar(MSG_NO_FISH, 40)
      context.world:play_sound(FAIL_SOUND, ploc.x, ploc.y, ploc.z, 0.6, 1.2)
      -- No fish found — no durability cost, no cooldown
      return
    end

    -- ── Fish found — drain durability and activate ──────────
    if not context.item:use_durability_percentage(DURABILITY_COST, false) then
      context.action:stop()
      return
    end
    context.cooldowns:set_global(COOLDOWN_TICKS)

    context.state.active = true
    context.player:show_action_bar(string.format(MSG_FOUND, #fish), 60)

    -- Visual scan-ring burst around the player
    for angle = 0, 350, 15 do
      local rad = math.rad(angle)
      local px = ploc.x + math.cos(rad) * SCAN_RADIUS * 0.3
      local pz = ploc.z + math.sin(rad) * SCAN_RADIUS * 0.3
      context.world:spawn_particle(
        SCAN_PARTICLE, px, ploc.y + 0.5, pz, 2, 0.2, 0.2, 0.2, 0
      )
    end

    -- ── Phase 1: Push fish upward (simulated levitation) ────
    -- Repeatedly push fish upward + show bubble particles
    local lev_elapsed = 0
    local lev_task = context.scheduler:run_repeating(0, LEVITATE_PUSH_INTERVAL, function()
      lev_elapsed = lev_elapsed + LEVITATE_PUSH_INTERVAL

      for _, f in ipairs(fish) do
        if f.is_valid and not f.is_dead and f:can_receive_hostile_effect(player.uuid)
          and not em.location.is_protected(f.current_location) then
          -- Push upward to simulate levitation
          f:push(0, LEVITATE_PUSH_UP, 0)

          -- Bubble particles
          local fl = f.current_location
          context.world:spawn_particle(
            LEVITATE_PARTICLE, fl.x, fl.y, fl.z,
            5, 0.3, 0.5, 0.3, 0.02
          )
        end
      end

      -- Auto-stop if we somehow overshoot (safety net)
      if lev_elapsed >= LEVITATE_TICKS then
        if context.state.lev_task then
          context.scheduler:cancel(context.state.lev_task)
          context.state.lev_task = nil
        end
      end
    end)
    context.state.lev_task = lev_task

    -- ── Phase 2: Pull toward player (after levitation) ──────
    context.scheduler:run_later(LEVITATE_TICKS, function()
      -- Clean up levitation task
      if context.state.lev_task then
        context.scheduler:cancel(context.state.lev_task)
        context.state.lev_task = nil
      end

      -- Restore gravity for the pull phase
      for _, f in ipairs(fish) do
        context.action:restore_gravity(f.uuid)
      end

      context.player:show_action_bar(MSG_PULLING, 60)
      local pull_loc = context.player.current_location
      context.world:play_sound(PULL_SOUND, pull_loc.x, pull_loc.y, pull_loc.z, 1.0, 1.2)

      -- Repeatedly push fish toward the player over PULL_DURATION_TICKS
      local elapsed = 0
      local pull_task = context.scheduler:run_repeating(0, PULL_INTERVAL_TICKS, function()
        elapsed = elapsed + PULL_INTERVAL_TICKS

        if elapsed > PULL_DURATION_TICKS then
          -- Phase complete — clean up
          if context.state.pull_task then
            context.scheduler:cancel(context.state.pull_task)
            context.state.pull_task = nil
          end
          context.state.active = false
          return
        end

        -- Pull each fish toward the player's current position
        local target = context.player.current_location
        for _, f in ipairs(fish) do
          if f.is_valid and not f.is_dead and f:can_receive_hostile_effect(player.uuid)
          and not em.location.is_protected(f.current_location) then
            local fl = f.current_location
            local dx = target.x - fl.x
            local dy = target.y - fl.y
            local dz = target.z - fl.z
            local dist = math.sqrt(dx * dx + dy * dy + dz * dz)
            if dist > 0.5 then
              f:push(
                (dx / dist) * PULL_STRENGTH,
                (dy / dist) * PULL_STRENGTH + PULL_Y_BOOST,
                (dz / dist) * PULL_STRENGTH
              )
            end
            -- Trail particles
            context.world:spawn_particle(
              PULL_PARTICLE, fl.x, fl.y + 0.3, fl.z,
              3, 0.2, 0.2, 0.2, 0
            )
          end
        end
      end)
      context.state.pull_task = pull_task
    end)
  end,
}