package com.magmaguy.freeminecraftmodels.pets.behavior;

import org.junit.jupiter.api.Test;
import com.magmaguy.shaded.luaj.vm2.Globals;
import com.magmaguy.shaded.luaj.vm2.LuaValue;
import com.magmaguy.shaded.luaj.vm2.lib.jse.JsePlatform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shipped strike_back module run as Lua, tick by tick, against a wild pet a player hit: what it calls and when,
 * with and without delay_ticks. Each call is written as its tick and its name, with the animation it was given.
 */
class StrikeBackModuleTest {
    /**
     * Runs the module's sensor from tick 1 to {@code ticks}. The pet was hit once before tick 1 and again at
     * {@code secondHit} (0 for never); its touch reaches the attacker in the ticks {@code touches} lists; the sensor
     * does not run in the ticks {@code idle} lists, as when the hit gate holds it back. Returns the calls made.
     */
    private static String run(String resource, int delay, String animation, int ticks, String touches, int secondHit,
                              String idle) throws IOException {
        Globals globals = JsePlatform.standardGlobals();
        String module;
        try (InputStream stream = StrikeBackModuleTest.class.getResourceAsStream(resource)) {
            module = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        globals.set("MODULE", LuaValue.valueOf(module));
        globals.set("DELAY", LuaValue.valueOf(delay));
        globals.set("ANIMATION", LuaValue.valueOf(animation));
        globals.set("TICKS", LuaValue.valueOf(ticks));
        globals.set("TOUCHES", LuaValue.valueOf("," + touches + ","));
        globals.set("IDLE", LuaValue.valueOf("," + idle + ","));
        globals.set("SECOND", LuaValue.valueOf(secondHit));
        return globals.load("""
                ai = { module = function(m) return m end, sensor = function(s) return s end }
                local module = load(MODULE, 'strike_back.lua')()
                local settings = {}
                for key, value in pairs(module.settings) do settings[key] = value end
                settings.delay_ticks = DELAY
                settings.animation = ANIMATION
                local memory, calls, now = {}, {}, 0
                local function touching() return TOUCHES:find(',' .. now .. ',', 1, true) ~= nil end
                local c = {
                    settings = settings,
                    memory = {
                        get = function(_, key) return memory[key] end,
                        set = function(_, key, value) memory[key] = value end,
                        forget = function(_, key) memory[key] = nil end },
                    pet = {
                        wild = true, hurt_count = 1, attacker = { x = 0, y = 0, z = 0 },
                        touching_attacker = function() return touching() end,
                        play_animation = function(_, name)
                            calls[#calls + 1] = now .. ' play ' .. name
                            return true
                        end,
                        strike_attacker = function(_, reach, effect, duration, amplifier, damage, cooldown, sound,
                                                   volume, pitch, particle, animation)
                            local landed = touching()
                            calls[#calls + 1] = now .. (landed and ' strike ' or ' miss ') .. tostring(animation)
                            return landed
                        end } }
                for tick = 1, TICKS do
                    now = tick
                    c.tick = tick
                    if tick == SECOND then c.pet.hurt_count = 2 end
                    if IDLE:find(',' .. tick .. ',', 1, true) == nil then module.sensors[1].sense(c) end
                end
                return table.concat(calls, '; ')
                """, "strike-back-test").call().tojstring();
    }

    private static String shipped(int delay, String animation, int ticks, String touches) throws IOException {
        return run("/pet_behaviors/strike_back.lua", delay, animation, ticks, touches, 0, "");
    }

    @Test
    void withoutADelayItStrikesAsBefore() throws IOException {
        // The touch arrives at tick 4: until then each tick tries and misses, as it always has; then one strike that
        // plays its own animation, and nothing after.
        assertEquals("1 miss attack; 2 miss attack; 3 miss attack; 4 strike attack", shipped(0, "attack", 12, "4,5,6,7"));
    }

    @Test
    void theDefaultIsNoDelay() throws IOException {
        Globals globals = JsePlatform.standardGlobals();
        try (InputStream stream = StrikeBackModuleTest.class.getResourceAsStream("/pet_behaviors/strike_back.lua")) {
            globals.set("MODULE", LuaValue.valueOf(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
        }
        assertEquals(0, globals.load("""
                ai = { module = function(m) return m end, sensor = function(s) return s end }
                return load(MODULE, 'strike_back.lua')().settings.delay_ticks
                """, "strike-back-default").call().toint());
    }

    @Test
    void withADelayTheAnimationStartsOnTheTouchAndTheStrikeLandsThatManyTicksLater() throws IOException {
        // Touching from tick 4 on: the animation at 4, once; the strike at 9, with no animation of its own.
        assertEquals("4 play attack.headbutt; 9 strike NONE", shipped(5, "attack.headbutt", 30, "4,5,6,7,8,9,10,11,12"));
    }

    @Test
    void aPlayerWhoStepsOutOfReachDuringTheDelayDodgesAndIsNotSwungAtAgain() throws IOException {
        // Touching at ticks 4 to 6 only, then again from 15: the swing comes down on nobody at 9, and that hit is
        // answered. Back in reach, the player is not struck and the animation does not play again.
        assertEquals("4 play attack.headbutt; 9 miss NONE", shipped(5, "attack.headbutt", 40, "4,5,6,15,16,17,18,19,20,21,22"));
    }

    @Test
    void theAnimationNeverReplaysWhileTheSwingIsInTheAir() throws IOException {
        // Touching in every tick of a 12-tick delay.
        assertEquals("1 play attack.headbutt; 13 strike NONE",
                shipped(12, "attack.headbutt", 30, "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16"));
    }

    @Test
    void aSwingLeftInTheAirByAnEarlierHitDoesNotStandInForTheNextHits() throws IOException {
        // The first hit's swing starts at tick 2, then the sensor stops running (the hit gate closes) before it
        // lands. A second hit at tick 40 gets a swing of its own: animation at 41 on the touch, strike at 46.
        assertEquals("2 play attack.headbutt; 41 play attack.headbutt; 46 strike NONE",
                run("/pet_behaviors/strike_back.lua", 5, "attack.headbutt", 60, "2,3,41,42,43,44,45,46,47", 40,
                        "3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39"));
    }

    @Test
    void theCopyTestersHaveKnowsNoDelay() throws IOException {
        // strike_back.lua as FreeMinecraftModels 141716d to d2a5b49 wrote it: given delay_ticks and the headbutt, it
        // strikes on the touch and plays the animation in that tick.
        assertEquals("1 miss attack.headbutt; 2 miss attack.headbutt; 3 strike attack.headbutt",
                run("/pet_behaviors_before/strike_back.lua", 5, "attack.headbutt", 12, "3,4,5,6,7,8,9", 0, ""));
    }
}
