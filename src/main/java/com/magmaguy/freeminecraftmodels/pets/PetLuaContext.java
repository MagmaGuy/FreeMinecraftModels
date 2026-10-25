package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.pets.behavior.PetBehaviorSettings;
import com.magmaguy.freeminecraftmodels.pets.behavior.PetPrograms;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.scripting.LuaMindContextExtension;
import com.magmaguy.shaded.luaj.vm2.LuaString;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Hands each of a pet's module callbacks its c.pet table and its own module's c.settings. Every table is made when the
 * pet's program is, so a callback allocates nothing here. A module may write to its c.settings; that changes only its
 * own pet's copy.
 */
final class PetLuaContext implements LuaMindContextExtension {
    private static final LuaString PET = LuaValue.valueOf("pet"), SETTINGS = LuaValue.valueOf("settings");

    private final PetLuaTable pet;
    private final Map<String, LuaTable> settings = new HashMap<>();
    // For a callback from something with no settings of its own, such as the program itself.
    private final LuaTable none = new LuaTable();

    PetLuaContext(PetControl control, PetPrograms.Program program) {
        this.pet = new PetLuaTable(control);
        program.settings().forEach((module, values) -> settings.put(module, PetBehaviorSettings.toLua(values)));
    }

    @Override
    public void extend(LuaTable table, MindContext context, String contribution) {
        pet.bind(context);
        table.rawset(PET, pet.table());
        table.rawset(SETTINGS, settings.getOrDefault(contribution, none));
    }
}
