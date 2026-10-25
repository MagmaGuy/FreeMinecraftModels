package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.scripting.LuaEnvironmentFactory;
import com.magmaguy.shaded.luaj.vm2.Globals;
import com.magmaguy.shaded.luaj.vm2.LuaError;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * c.pet as a module sees it, in the sandbox Mind modules run in, which keeps rawset and setmetatable. A whole
 * PetLuaTable needs a server, so this drives the read-only table it is built on.
 */
class PetLuaTableTest {
    private final List<String> reports = new ArrayList<>();
    private final PetLuaTable.Fields pet = new PetLuaTable.Fields("c.pet",
            (name, failure) -> reports.add(name + ": " + failure.getMessage()));
    private double speed = 1D;

    PetLuaTableTest() {
        pet.value("speed", () -> LuaValue.valueOf(speed));
        pet.method("add", args -> LuaValue.valueOf(args.checkdouble(1) + args.checkdouble(2)));
        pet.value("broken", () -> {
            throw new IllegalStateException("the body is gone");
        });
        pet.method("explode", args -> {
            throw new IllegalStateException("the model is gone");
        });
    }

    private LuaValue run(String script) {
        Globals globals = LuaEnvironmentFactory.createGlobals();
        LuaTable c = new LuaTable();
        c.set("pet", pet.view());
        globals.set("c", c);
        return globals.load(script, "module").call();
    }

    @Test
    void propertiesAreReadFreshOnEveryAccess() {
        assertEquals(1D, run("return c.pet.speed").todouble());
        speed = 2D;
        assertEquals(2D, run("return c.pet.speed").todouble());
        assertTrue(run("return c.pet.unknown == nil").toboolean());
    }

    @Test
    void methodsTakeTheSameArgumentsWithAColonOrADot() {
        assertEquals(33D, run("return c.pet:add(1, 2) + c.pet.add(10, 20)").todouble());
    }

    @Test
    void modulesCannotChangeTheTable() {
        LuaError write = assertThrows(LuaError.class, () -> run("c.pet.speed = 5"));
        assertTrue(write.getMessage().contains("c.pet.speed cannot be set; store per-pet values in c.memory"),
                write.getMessage());
        for (String script : List.of("c.pet.add = function() return 0 end", "c.pet.new_field = 1",
                "rawset(c.pet, 'speed', 5)", "table.insert(c.pet, 1)", "setmetatable(c.pet, nil)",
                "setmetatable(c.pet, {})"))
            assertThrows(LuaError.class, () -> run(script), script);
        assertEquals(1D, run("return c.pet.speed").todouble());
        assertEquals(3D, run("return c.pet:add(1, 2)").todouble());
        assertEquals(LuaValue.FALSE, run("return getmetatable(c.pet)"));
        assertEquals(LuaValue.NIL, run("return next(c.pet)"));
    }

    @Test
    void javaFailuresAreReportedOncePerNameAndReachLuaAsAShortError() {
        for (int call = 0; call < 2; call++) {
            String property = run("local ok, message = pcall(function() return c.pet.broken end) return message").tojstring();
            assertTrue(property.contains("c.pet.broken failed; the server log has the cause"), property);
            String method = run("local ok, message = pcall(function() return c.pet:explode() end) return message").tojstring();
            assertTrue(method.contains("c.pet:explode failed; the server log has the cause"), method);
        }
        assertEquals(List.of("c.pet.broken: the body is gone", "c.pet:explode: the model is gone"), reports);
    }

    @Test
    void argumentErrorsPassThroughUnreported() {
        String message = run("local ok, message = pcall(function() return c.pet:add('one', 2) end) return message").tojstring();
        assertTrue(message.contains("number expected"), message);
        assertEquals(List.of(), reports);
    }
}
