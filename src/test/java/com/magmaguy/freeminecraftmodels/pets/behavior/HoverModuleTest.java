package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.magmacore.shaded.luaj.vm2.Globals;
import com.magmaguy.magmacore.shaded.luaj.vm2.LuaValue;
import com.magmaguy.magmacore.shaded.luaj.vm2.lib.jse.JsePlatform;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped hover module's bob, run as Lua against a pet at a chosen height over the ground: where it steers.
 * The Driftjelly's band is 1.5 over the ground, give or take 0.75.
 */
class HoverModuleTest {
    private static final double GROUND = 64D, HEIGHT = 1.5D, VARIANCE = 0.75D;

    /** The height hover steers at on its first tick, for a pet at this height with this anchor height (NaN for none). */
    private static double steeredAt(double y, double anchorY) throws IOException {
        Globals globals = JsePlatform.standardGlobals();
        String module;
        try (InputStream stream = HoverModuleTest.class.getResourceAsStream("/pet_behaviors/hover.lua")) {
            module = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        globals.set("MODULE", LuaValue.valueOf(module));
        globals.set("Y", LuaValue.valueOf(y));
        globals.set("GROUND", LuaValue.valueOf(GROUND));
        globals.set("HEIGHT", LuaValue.valueOf(HEIGHT));
        globals.set("VARIANCE", LuaValue.valueOf(VARIANCE));
        globals.set("ANCHOR", Double.isNaN(anchorY) ? LuaValue.NIL : LuaValue.valueOf(anchorY));
        return globals.load("""
                ai = { controls = { move = 'move', look = 'look' },
                       module = function(m) return m end, behavior = function(b) return b end }
                local module = load(MODULE, 'hover.lua')()
                local memory, steered = {}, nil
                local c = {
                    tick = 100, settings = module.settings,
                    memory = {
                        contains = function(_, key) return memory[key] ~= nil end,
                        get = function(_, key) return memory[key] end,
                        set = function(_, key, value) memory[key] = value end,
                        forget = function(_, key) memory[key] = nil end },
                    pet = {
                        staying = false, position = { world = 'w', x = 0.5, y = Y, z = 0.5 },
                        anchor = ANCHOR and { world = 'w', x = 0.5, y = ANCHOR, z = 0.5 } or nil,
                        species = { speed = 1, hover_height = HEIGHT, hover_variance = VARIANCE },
                        ground_below = function() return GROUND end,
                        steer_to = function(_, to) steered = to end } }
                module.behaviors[1].tick(c)
                return steered.y
                """, "hover-test").call().todouble();
    }

    @Test
    void aPetRestingOnTheGroundRisesToTheBottomOfItsBand() throws IOException {
        // On the block: 1.5 under its hover height, which the correction for a pet far off its height does not
        // catch, since that takes more than 1.5. Its bob used to pick a point within 0.375 of the ground.
        for (int run = 0; run < 50; run++)
            assertEquals(GROUND + HEIGHT - VARIANCE, steeredAt(GROUND, Double.NaN), 1.0E-9D);
    }

    @Test
    void aPetLowInItsBandNeverBobsUnderIt() throws IOException {
        for (int run = 0; run < 200; run++) {
            double y = steeredAt(GROUND + HEIGHT - VARIANCE + 0.1D, Double.NaN);
            assertTrue(y >= GROUND + HEIGHT - VARIANCE - 1.0E-9D, "steered at " + y);
        }
    }

    @Test
    void aPetInTheMiddleOfItsBandBobsAsBefore() throws IOException {
        double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
        for (int run = 0; run < 400; run++) {
            double y = steeredAt(GROUND + HEIGHT, Double.NaN);
            low = Math.min(low, y);
            high = Math.max(high, y);
        }
        // Half the variance each way round where it is.
        assertTrue(low >= GROUND + HEIGHT - VARIANCE / 2D && low < GROUND + HEIGHT - 0.2D, "lowest bob " + low);
        assertTrue(high <= GROUND + HEIGHT + VARIANCE / 2D && high > GROUND + HEIGHT + 0.2D, "highest bob " + high);
    }

    @Test
    void aPetFarOverItsBandStillHeadsBackToItsHoverHeight() throws IOException {
        assertEquals(GROUND + HEIGHT, steeredAt(GROUND + HEIGHT + 2D, Double.NaN), 1.0E-9D);
    }

    @Test
    void aPetBesideAnOwnerInTheAirKeepsWithinThreeBlocksUnderThem() throws IOException {
        // The owner 10 over the ground: the pet's height is 3 under them, far over the band's bottom.
        assertEquals(GROUND + 7D, steeredAt(GROUND + 2D, GROUND + 10D), 1.0E-9D);
    }
}
