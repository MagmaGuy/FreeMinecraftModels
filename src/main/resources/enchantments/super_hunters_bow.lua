-- =============================================================
-- GET OVER HERE! — Scorpion-style grapple bow
-- Shift + Right-click while aiming at a mob to fire a
-- high-speed harpoon. On impact, a particle chain draws
-- between the target and the player, yanking the mob towards
-- you with a slight upward lift.
-- =============================================================

-------------------------------------------------
-- SETTINGS — tweak these to taste
-------------------------------------------------
local TARGET_RANGE        = 50        -- max targeting range (blocks)
local PULL_STRENGTH       = 1.15      -- pull force per tick (applied to full 3D direction)
local PULL_Y_OFFSET       = 0.2       -- extra upward lift added on top of directional pull
local CHAIN_PARTICLE      = "CRIT"    -- particle type for the chain line
local CHAIN_POINTS        = 10        -- number of particles along the chain
local CHAIN_DURATION      = 8         -- ticks the chain stays visible / pull lasts
local CHAIN_INTERVAL      = 2         -- ticks between chain redraws & pulls
local MAX_PULL_TICKS      = 40        -- hard cap (2s) — force-stop if mob is stuck
local IMPACT_PARTICLE     = "SMOKE"   -- burst particle on impact
local IMPACT_PARTICLE_CT  = 25        -- impact burst count
local COOLDOWN_TICKS      = 1200      -- cooldown amount
local FLIGHT_DELAY        = 4         -- ticks before "arrow" lands (fake travel time)
local LAUNCH_SOUND        = "ENTITY_ARROW_SHOOT"
local LAUNCH_PITCH        = 0.6
local IMPACT_SOUND        = "ENTITY_ARROW_HIT"
local IMPACT_PITCH        = 0.7
local PULL_SOUND          = "ENTITY_FISHING_BOBBER_RETRIEVE"
local PULL_PITCH          = 1.2
local REEL_SOUND          = "ENTITY_FISHING_BOBBER_RETRIEVE"
local REEL_PITCH          = 1.5
local REEL_VOLUME         = 0.6
local DURABILITY_COST     = 0.05      -- 5% durability per use
local MIN_DURABILITY_PCT  = 0.01      -- 1% — EM considers item broken below this

-------------------------------------------------
-- HELPERS
-------------------------------------------------
local function normalize(x, y, z)
    local len = math.sqrt(x * x + y * y + z * z)
    if len == 0 then return 0, 0, 0 end
    return x / len, y / len, z / len
end

local function lerp(a, b, t)
    return a + (b - a) * t
end

local function draw_chain(world, from, to, particle, points)
    for i = 0, points do
        local t = i / points
        local px = lerp(from.x, to.x, t)
        local py = lerp(from.y, to.y, t)
        local pz = lerp(from.z, to.z, t)
        world:spawn_particle(particle, px, py, pz, 1, 0, 0, 0, 0)
    end
end

-------------------------------------------------
-- SCRIPT
-------------------------------------------------
return {
    api_version = 1,

    -- Shift + Right-click: acquire target and fire
    on_right_click =  function(context)
        local player = context.player
        if not context.item or not player then return end
        -- Durability guard — EM treats ≤1% as broken
        if context.item:get_durability_percentage() <= MIN_DURABILITY_PCT then
            context.player:show_action_bar("&c&lBow is broken!", 30)
            return
        end

        -- Cooldown guard
        if not context.cooldowns:local_ready("grapple") then
            local secs = math.ceil(context.cooldowns:local_remaining("grapple") / 20)
            context.player:show_action_bar("&7&lRecharging... &f(" .. secs .. "s)", 20)
            return
        end

        -- Use get_target_entity to find the mob we're looking at.
        -- Unlike world:raycast(), this won't return the player themselves.
        local target = context.player:get_target_entity(TARGET_RANGE)

        if not target then
            context.player:show_action_bar("&c&lNo target in sight!", 30)
            return
        end

        if not target:can_receive_hostile_effect(player.uuid) or target.is_significant_boss
            or not target:has_clear_movement_path(player.current_location) then
            player:show_action_bar("&c&lCannot safely hook this target!", 30)
            return
        end

        -- Deduct 5% durability (can_break = false, item won't be destroyed)
        if not context.item:use_durability_percentage(DURABILITY_COST, false) then return end
        -- Claim this accepted interaction so the bow does not also start drawing.
        context.event:cancel()

        -- Engage cooldown
        context.cooldowns:set_local(COOLDOWN_TICKS, "grapple")

        -- Launch feedback
        local ploc = context.player.current_location
        context.world:play_sound(LAUNCH_SOUND, ploc.x, ploc.y, ploc.z, 1.2, LAUNCH_PITCH)
        context.player:show_action_bar("&e&l⇒ GET OVER HERE! ⇐", 40)

        -- Snapshot the target location for the trail (so it flies to where the
        -- target was when we fired, not where it wanders to)
        local target_snap = target.current_location

        -- Trail particles advancing from player → target during flight delay
        local trail_tick = 0
        local trail_task = context.scheduler:run_repeating(0, 1, function(trail_ctx)
            trail_tick = trail_tick + 1
            local p_loc = trail_ctx.player.current_location
            if not p_loc or not target_snap then return end

            local progress = math.min(1, trail_tick / FLIGHT_DELAY)
            local tx = lerp(p_loc.x, target_snap.x, progress)
            local ty = lerp(p_loc.y + 1.0, target_snap.y + 0.5, progress)
            local tz = lerp(p_loc.z, target_snap.z, progress)
            trail_ctx.world:spawn_particle("CRIT", tx, ty, tz, 3, 0.1, 0.1, 0.1, 0)
        end)

        -- After fake flight time → impact + chain + pull
        context.scheduler:run_later(FLIGHT_DELAY, function(impact_ctx)
            -- Stop the trail
            impact_ctx.scheduler:cancel(trail_task)

            local p_loc = impact_ctx.player.current_location
            if not p_loc or not impact_ctx.player.is_alive then return end

            local current_target = target
            if not current_target:can_receive_hostile_effect(impact_ctx.player.uuid)
                or current_target.is_significant_boss
                or not current_target:has_clear_movement_path(p_loc) then
                impact_ctx.action:stop()
                return
            end

            local t_loc = current_target.current_location
            if not t_loc then return end

            -- Impact burst at the target
            impact_ctx.world:spawn_particle(
                IMPACT_PARTICLE,
                t_loc.x, t_loc.y + 0.5, t_loc.z,
                IMPACT_PARTICLE_CT, 0.4, 0.4, 0.4, 0.05
            )
            impact_ctx.world:play_sound(
                IMPACT_SOUND, t_loc.x, t_loc.y, t_loc.z, 2.0, IMPACT_PITCH
            )

            -- Initial pull whoosh at the player
            impact_ctx.world:play_sound(
                PULL_SOUND, p_loc.x, p_loc.y, p_loc.z, 1.0, PULL_PITCH
            )

            -- Chain + pull over several ticks
            local ticks_elapsed = 0
            impact_ctx.state.chain_task = impact_ctx.scheduler:run_repeating(
                0, CHAIN_INTERVAL,
                function(chain_ctx)
                    ticks_elapsed = ticks_elapsed + CHAIN_INTERVAL

                    local pl = chain_ctx.player.current_location
                    if not pl then
                        chain_ctx.scheduler:cancel(chain_ctx.state.chain_task)
                        chain_ctx.state.chain_task = nil
                        return
                    end

                    local pull_target = target
                    if ticks_elapsed > CHAIN_DURATION or ticks_elapsed > MAX_PULL_TICKS
                        or not pull_target:can_receive_hostile_effect(chain_ctx.player.uuid)
                        or pull_target.is_significant_boss
                        or not pull_target:has_clear_movement_path(pl) then
                        chain_ctx.action:stop()
                        return
                    end

                    local tl = pull_target.current_location
                    if not tl then return end

                    -- Draw the particle chain (player chest → target center)
                    draw_chain(
                        chain_ctx.world,
                        { x = pl.x, y = pl.y + 1.0, z = pl.z },
                        { x = tl.x, y = tl.y + 0.5, z = tl.z },
                        CHAIN_PARTICLE,
                        CHAIN_POINTS
                    )

                    -- Reel-in sound at the midpoint between player and target
                    local mid_x = (pl.x + tl.x) / 2
                    local mid_y = (pl.y + tl.y) / 2
                    local mid_z = (pl.z + tl.z) / 2
                    chain_ctx.world:play_sound(
                        REEL_SOUND, mid_x, mid_y, mid_z,
                        REEL_VOLUME, REEL_PITCH
                    )

                    -- Pull direction: target → player, full 3D normalized
                    local dx = pl.x - tl.x
                    local dy = pl.y - tl.y
                    local dz = pl.z - tl.z
                    local nx, ny, nz = normalize(dx, dy, dz)

                    -- Yank the mob towards the player using the full 3D
                    -- direction (so it pulls UP hills, DOWN cliffs, etc.)
                    -- then add PULL_Y_OFFSET on top for a slight arc lift
                    pull_target:push(
                        nx * PULL_STRENGTH,
                        ny * PULL_STRENGTH + PULL_Y_OFFSET,
                        nz * PULL_STRENGTH
                    )
                end
            )
        end)
    end,

}

