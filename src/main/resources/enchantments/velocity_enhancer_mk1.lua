-- ============================================================
-- Velocity Enhancer Mk1 — Dipped Tip
-- ============================================================
-- On bow projectile hit, has a chance to roll a random negative
-- potion effect on the hit MOB (never on players). The effect is
-- chosen by weight: light effects roll often, the most destructive
-- ones roll rarely. Each effect plays its own sound + particle and
-- the particles repeat around the mob until the effect expires.
-- Each successful trigger drains 1% durability. The power is
-- locked out below the minimum durability threshold.
-- ============================================================

-- ── Settings ────────────────────────────────────────────────
local SETTINGS = {
  -- Roll chance to even attempt the proc (0.0 – 1.0).
  trigger_chance = 0.05,

  -- Effect duration in seconds (also drives the particle loop length).
  effect_duration_seconds = 5,

  -- Durability cost per successful trigger (fraction of max, 0.0 – 1.0).
  durability_cost = 0.01,

  -- Power refuses to fire when remaining durability is at or below this.
  minimum_durability = 0.02,

  -- How often (in ticks) the lingering particle ring re-emits around the mob.
  particle_interval_ticks = 5,

  -- Action-bar lifetime for notification messages, in ticks.
  action_bar_ticks = 60,

  -- Console message cooldown (ticks). 10 seconds = 200 ticks.
  region_log_cooldown_ticks = 200,

  -- Weighted negative-effect table.
  -- Higher weight = more likely to be picked. Mild effects are heavy,
  -- destructive effects are very light. One IS always chosen on a proc.
  -- Only effects that actually do something to mobs are listed — things
  -- like SLOW_DIGGING (mobs don't mine) and HUNGER (mobs have no hunger
  -- bar) are deliberately omitted.
  effects = {
    { name = "WEAKNESS",   weight = 30, amplifier = 0,
      particle = "SMOKE",     sound = "ENTITY_ZOMBIE_VILLAGER_CURE" },
    { name = "SLOWNESS",       weight = 24, amplifier = 0,
      particle = "SNOWBALL",    sound = "BLOCK_SNOW_STEP" },
    { name = "BLINDNESS",  weight = 15, amplifier = 0,
      particle = "SQUID_INK",        sound = "ENTITY_SQUID_SQUIRT" },
    { name = "NAUSEA",  weight = 12, amplifier = 0,
      particle = "PORTAL",           sound = "ENTITY_ENDERMAN_AMBIENT" },
    { name = "POISON",     weight =  8, amplifier = 0,
      particle = "HAPPY_VILLAGER",   sound = "ENTITY_WITCH_DRINK" },
    { name = "LEVITATION", weight =  5, amplifier = 0,
      particle = "END_ROD",          sound = "ENTITY_SHULKER_SHOOT" },
    { name = "WITHER",     weight =  3, amplifier = 0,
      particle = "LARGE_SMOKE",      sound = "ENTITY_WITHER_SHOOT" },
    { name = "INSTANT_DAMAGE",       weight =  1, amplifier = 0,
      particle = "DAMAGE_INDICATOR", sound = "ENTITY_GENERIC_EXPLODE" },
  },
}

-- ── Helpers ─────────────────────────────────────────────────

-- Picks one entry from SETTINGS.effects using weighted random selection.
-- One entry is ALWAYS returned (assumes the table has at least one).
local function pick_weighted_effect()
  local total = 0
  for _, effect in ipairs(SETTINGS.effects) do
    total = total + effect.weight
  end
  local roll = math.random() * total
  local accumulated = 0
  for _, effect in ipairs(SETTINGS.effects) do
    accumulated = accumulated + effect.weight
    if roll <= accumulated then
      return effect
    end
  end
  return SETTINGS.effects[#SETTINGS.effects]
end

-- Pretty-prints an effect name for the action bar (POISON -> Poison).
local function format_effect_name(raw)
  local lowered = string.lower(raw):gsub("_", " ")
  return (lowered:gsub("^%l", string.upper):gsub(" %l", string.upper))
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

  on_projectile_hit = function(context)
    local player = context.player
    if not player then return end

    -- Region / dungeon gate — block the power in protected areas.
    local block_reason = get_block_reason(player)
    if block_reason then
      -- Throttled console warning (once per 10 seconds per player).
      if context.cooldowns:check_local("region_block_log", SETTINGS.region_log_cooldown_ticks) then
        if block_reason == "protected" then
          context.log:warn("[Velocity Enhancer Mk1] Blocked for player " .. player.name
            .. " — inside a WorldGuard/GriefPrevention protected region.")
        else
          context.log:warn("[Velocity Enhancer Mk1] Blocked for player " .. player.name
            .. " — inside an EliteMobs dungeon.")
        end
      end
      return
    end

    -- 1. Random chance gate.
    if math.random() > SETTINGS.trigger_chance then return end

    -- The accepted shot supplies the actual target and original source item.
    local target = context.target
    if not target or not target:can_receive_hostile_effect(player.uuid)
      or em.location.is_protected(target.current_location) then return end

    -- 3. Durability gate. Bail BEFORE consuming anything if too damaged.
    local durability_percent = context.item:get_durability_percentage()
    if durability_percent == nil or durability_percent <= SETTINGS.minimum_durability then
      player:show_action_bar(
        "&c&lBrew Bow &7» &cToo damaged to channel a brew",
        SETTINGS.action_bar_ticks
      )
      return
    end

    -- 4. Drain durability for this trigger.
    if not context.item:use_durability_percentage(SETTINGS.durability_cost, false) then return end

    -- 5. Roll the weighted negative effect.
    local effect = pick_weighted_effect()
    local duration_ticks = SETTINGS.effect_duration_seconds * 20

    -- 6. Apply the potion effect to the mob.
    target:add_potion_effect(effect.name, duration_ticks, effect.amplifier)

    -- 7. Notification on the action bar.
    player:show_action_bar(
      "&5&lBrew Bow &7» &dApplied &f" .. format_effect_name(effect.name),
      SETTINGS.action_bar_ticks
    )

    -- 8. Distinct one-shot impact sound for this effect.
    local impact_loc = target.current_location
    if impact_loc then
      context.world:play_sound(
        effect.sound,
        impact_loc.x, impact_loc.y, impact_loc.z,
        1.0, 1.0
      )
    end

    -- 9. Lingering particle aura around the mob until the effect expires.
    --    The repeating callback receives its own fresh `tick_context`
    --    (see MagmaCore ScriptInstance.runCallback) — capture from there,
    --    not from the outer event-time `context`.
    if impact_loc == nil then return end
    local particle_name  = effect.particle
    local elapsed_ticks  = 0

    context.scheduler:run_repeating(0, SETTINGS.particle_interval_ticks, function(tick_context)
      elapsed_ticks = elapsed_ticks + SETTINGS.particle_interval_ticks
      if elapsed_ticks > duration_ticks or not target.is_valid or target.is_dead
        or not target:can_receive_hostile_effect(player.uuid) then
        tick_context.action:stop()
        return
      end
      local loc = target.current_location
      if not loc then tick_context.action:stop(); return end

      tick_context.world:spawn_particle(
        particle_name,
        loc.x, loc.y + 1.0, loc.z,
        8,                  -- count
        0.35, 0.5, 0.35,    -- spread (dx, dy, dz)
        0.01                -- speed
      )
    end)
  end,
}