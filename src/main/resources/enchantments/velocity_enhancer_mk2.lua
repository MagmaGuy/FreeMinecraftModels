-- ============================================================
-- Air Piston — Velocity Enhancer Mk2 crossbow power
-- ============================================================
-- 15% chance on crossbow bolt hit to release a massive burst
-- of air pressure that sends the mob flying. If it hits a wall
-- (confirmed horizontal collision) it gets pinned there by the
-- pressure for a few seconds before being released.
--
-- Wall detection only triggers on XZ movement stopping — the
-- mob landing on the floor does NOT count as a wall hit.
-- ============================================================

-- ── Settings ────────────────────────────────────────────────
local SETTINGS = {
  -- Chance to proc per crossbow bolt hit (0.0 – 1.0).
  trigger_chance            = 0.15,

  -- Durability consumed per proc (fraction of max).
  durability_cost           = 0.04,

  -- Minimum durability required to proc.
  minimum_durability        = 0.05,

  -- Max targeting range passed to get_target_entity.
  target_range              = 50,

  -- Horizontal push force — makes the mob fly sideways.
  push_force                = 4.5,

  -- Upward launch force — gives the mob a big arc into the air.
  push_y_force              = 1.2,

  -- Wall detection: ticks between position samples.
  wall_check_interval       = 3,

  -- Horizontal distance (blocks) per sample below which the mob
  -- may have stopped against a wall; a blocked horizontal path must also be observed. Only XZ is checked —
  -- landing on the floor never triggers this.
  wall_detect_threshold     = 0.12,

  -- Give up on wall detection after this many ticks (3 seconds).
  wall_check_max_ticks      = 60,

  -- How long (ticks) the mob is pinned to the wall (3 seconds).
  pin_duration_ticks        = 60,

  -- SLOW amplifier to hold the mob against the wall (200 = full stop).
  pin_slowness_amp          = 200,

  -- Ticks between each corrective push back to the pin position.
  -- 1 = every tick (tightest lock), 2 = every other tick, etc.
  pin_correction_interval   = 1,

  -- Multiplier applied to the delta vector each correction tick.
  -- Higher = snaps back faster but can oscillate; 0.5 is stable.
  pin_correction_force      = 0.5,

  -- How often (ticks) to emit pinned particles and sound.
  pin_particle_interval     = 8,

  -- ── Particles ──────────────────────────────────────────────
  -- Initial air blast at the mob.
  particle_blast            = "CLOUD",
  particle_blast_count      = 25,

  -- Swirling particles while mob is pinned to the wall.
  particle_pinned           = "SNEEZE",
  particle_pinned_count     = 6,

  -- Release burst when the pin expires.
  particle_release          = "POOF",
  particle_release_count    = 20,

  -- ── Sounds ─────────────────────────────────────────────────
  -- Air pressure blast on hit.
  sound_blast               = "ENTITY_WIND_CHARGE_WIND_BURST",
  sound_blast_volume        = 1.2,
  sound_blast_pitch         = 0.7,

  -- Thud when mob hits the wall.
  sound_wall_hit            = "BLOCK_STONE_BREAK",
  sound_wall_hit_volume     = 1.5,
  sound_wall_hit_pitch      = 0.5,

  -- Quiet pressure hiss while pinned.
  sound_pinned_tick         = "ENTITY_WIND_CHARGE_WIND_BURST",
  sound_pinned_tick_volume  = 0.2,
  sound_pinned_tick_pitch   = 1.4,

  -- Pressure release when the pin expires.
  sound_release             = "ENTITY_WIND_CHARGE_WIND_BURST",
  sound_release_volume      = 0.6,
  sound_release_pitch       = 1.2,

  action_bar_ticks          = 60,

  -- Console message cooldown (ticks). 10 seconds = 200 ticks.
  region_log_cooldown_ticks = 200,
}

-- ── Helpers ──────────────────────────────────────────────────

local function normalize(x, y, z)
  local len = math.sqrt(x*x + y*y + z*z)
  if len == 0 then return 0, 0, 0 end
  return x/len, y/len, z/len
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

-- ── Script ───────────────────────────────────────────────────
return {
  api_version = 1,

  on_projectile_hit = function(context)
    local player = context.player
    if not player or not context.item then return end

    -- 0. Region / dungeon gate — block the power in protected areas.
    local block_reason = get_block_reason(player)
    if block_reason then
      -- Throttled console warning (once per 10 seconds per player).
      if context.cooldowns:check_local("region_block_log", SETTINGS.region_log_cooldown_ticks) then
        if block_reason == "protected" then
          context.log:warn("[Air Piston] Blocked for player " .. player.name
            .. " — inside a WorldGuard/GriefPrevention protected region.")
        else
          context.log:warn("[Air Piston] Blocked for player " .. player.name
            .. " — inside an EliteMobs dungeon.")
        end
      end
      return
    end

    -- 1. Chance gate.
    if math.random() > SETTINGS.trigger_chance then return end

    -- 2. Target acquisition.
    local target = context.target
    if not target or not target:can_receive_hostile_effect(player.uuid)
      or em.location.is_protected(target.current_location) then return end

    -- 3. Durability gate.
    local dura = context.item:get_durability_percentage()
    if not dura or dura < SETTINGS.minimum_durability then
      player:show_action_bar(
        "&c&lAir Piston &7» &cCrossbow too damaged!",
        SETTINGS.action_bar_ticks
      )
      return
    end

    -- 5. Launch the mob — strong horizontal push in player→mob direction
    --    with a big upward component so it visibly arcs through the air.
    local ploc    = player.current_location
    local mob_loc = target.current_location
    if not ploc or not mob_loc then return end

    local nx, _, nz = normalize(
      mob_loc.x - ploc.x,
      0,               -- ignore vertical for the horizontal direction
      mob_loc.z - ploc.z
    )
    local launch_path = target:movement_path_status({world = player.world,
      x = mob_loc.x + nx * SETTINGS.push_force, y = mob_loc.y + SETTINGS.push_y_force,
      z = mob_loc.z + nz * SETTINGS.push_force})
    if launch_path == "protected" or launch_path == "unavailable" then return end
    if not context.item:use_durability_percentage(SETTINGS.durability_cost, false) then return end
    context.action:replace_previous()
    target:push(
      nx * SETTINGS.push_force,
      SETTINGS.push_y_force,
      nz * SETTINGS.push_force
    )

    -- Air blast at the mob.
    context.world:spawn_particle(
      SETTINGS.particle_blast,
      mob_loc.x, mob_loc.y + 1.0, mob_loc.z,
      SETTINGS.particle_blast_count, 0.5, 0.4, 0.5, 0.15
    )
    context.world:play_sound(
      SETTINGS.sound_blast,
      mob_loc.x, mob_loc.y, mob_loc.z,
      SETTINGS.sound_blast_volume, SETTINGS.sound_blast_pitch
    )
    player:show_action_bar("&b&l💨 Air Piston! &7Pressure released!", SETTINGS.action_bar_ticks)

    -- 6. Wall detection — sample XZ position only.
    --    Vertical movement (falling to floor) is ignored so we only
    --    detect when the mob has been stopped by a horizontal wall.
    local detect_ticks = 0
    local prev_x, prev_z = mob_loc.x, mob_loc.z

    if context.state.detect_task then
      context.scheduler:cancel(context.state.detect_task)
    end

    context.state.detect_task = context.scheduler:run_repeating(
      SETTINGS.wall_check_interval,
      SETTINGS.wall_check_interval,
      function(det_ctx)
        detect_ticks = detect_ticks + SETTINGS.wall_check_interval

        local current = target
        if not current:can_receive_hostile_effect(player.uuid)
          or em.location.is_protected(current.current_location) then
          det_ctx.action:stop()
          return
        end

        local loc = current.current_location
        if not loc then return end

        -- Only check horizontal (XZ) movement.
        local h_moved = math.sqrt(
          (loc.x - prev_x)^2 +
          (loc.z - prev_z)^2
        )

        local path = current:movement_path_status({world = player.world,
          x = loc.x + nx * .5, y = loc.y, z = loc.z + nz * .5})
        if path == "protected" or path == "unavailable" then det_ctx.action:stop(); return end
        if h_moved < SETTINGS.wall_detect_threshold and path == "blocked" then
          -- ── Wall hit — pin the mob ──────────────────────
          det_ctx.scheduler:cancel(det_ctx.state.detect_task)
          det_ctx.state.detect_task = nil

          if not det_ctx.action:temporary_potion(current.uuid, "SLOWNESS",
            SETTINGS.pin_duration_ticks + 20, SETTINGS.pin_slowness_amp) then
            det_ctx.action:stop()
            return
          end

          det_ctx.world:spawn_particle(
            SETTINGS.particle_blast,
            loc.x, loc.y + 1.0, loc.z,
            SETTINGS.particle_blast_count, 0.3, 0.5, 0.3, 0.08
          )
          det_ctx.world:play_sound(
            SETTINGS.sound_wall_hit,
            loc.x, loc.y, loc.z,
            SETTINGS.sound_wall_hit_volume, SETTINGS.sound_wall_hit_pitch
          )
          det_ctx.player:show_action_bar(
            "&b&l💨 PINNED! &7Held by air pressure!",
            SETTINGS.action_bar_ticks
          )

          -- Pin maintenance — corrective push every tick + particles on slower counter.
          local pin_x, pin_y, pin_z = loc.x, loc.y, loc.z
          local pin_elapsed    = 0
          local particle_accum = 0

          if det_ctx.state.pin_task then
            det_ctx.scheduler:cancel(det_ctx.state.pin_task)
          end

          det_ctx.state.pin_task = det_ctx.scheduler:run_repeating(
            SETTINGS.pin_correction_interval,
            SETTINGS.pin_correction_interval,
            function(pin_ctx)
              pin_elapsed    = pin_elapsed    + SETTINGS.pin_correction_interval
              particle_accum = particle_accum + SETTINGS.pin_correction_interval

              if pin_elapsed >= SETTINGS.pin_duration_ticks then
                pin_ctx.scheduler:cancel(pin_ctx.state.pin_task)
                pin_ctx.state.pin_task = nil
                pin_ctx.world:spawn_particle(
                  SETTINGS.particle_release,
                  pin_x, pin_y + 1.0, pin_z,
                  SETTINGS.particle_release_count, 0.5, 0.5, 0.5, 0.12
                )
                pin_ctx.world:play_sound(
                  SETTINGS.sound_release,
                  pin_x, pin_y, pin_z,
                  SETTINGS.sound_release_volume, SETTINGS.sound_release_pitch
                )
                return
              end

              local ent = target
              local destination = {world = player.world, x = pin_x, y = pin_y, z = pin_z}
              if not ent:can_receive_hostile_effect(player.uuid)
                or em.location.is_protected(ent.current_location)
                or not ent:has_clear_movement_path(destination) then
                pin_ctx.action:stop()
                return
              end
              local cur = ent.current_location
              ent:push((pin_x - cur.x) * SETTINGS.pin_correction_force,
                (pin_y - cur.y) * SETTINGS.pin_correction_force,
                (pin_z - cur.z) * SETTINGS.pin_correction_force)

              -- Particles and sound on the slower counter.
              if particle_accum >= SETTINGS.pin_particle_interval then
                particle_accum = 0
                pin_ctx.world:spawn_particle(
                  SETTINGS.particle_pinned,
                  pin_x, pin_y + 1.0, pin_z,
                  SETTINGS.particle_pinned_count, 0.3, 0.4, 0.3, 0.05
                )
                pin_ctx.world:play_sound(
                  SETTINGS.sound_pinned_tick,
                  pin_x, pin_y, pin_z,
                  SETTINGS.sound_pinned_tick_volume, SETTINGS.sound_pinned_tick_pitch
                )
              end
            end
          )
          return
        end

        prev_x, prev_z = loc.x, loc.z

        if detect_ticks >= SETTINGS.wall_check_max_ticks then
          det_ctx.scheduler:cancel(det_ctx.state.detect_task)
          det_ctx.state.detect_task = nil
        end
      end
    )
  end,
}