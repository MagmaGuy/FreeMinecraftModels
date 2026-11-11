package com.magmaguy.freeminecraftmodels.commands.arguments;

import com.magmaguy.freeminecraftmodels.pets.PetManager;
import com.magmaguy.freeminecraftmodels.pets.PetSpecies;
import com.magmaguy.magmacore.command.arguments.DynamicListStringCommandArgument;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A pet command's variant argument: it accepts any species' variant model, so the command can say which variants a
 * species has when the one typed is not among them, and suggests only the variants of the species typed earlier in
 * the command.
 */
public final class PetVariantArgument {
    private PetVariantArgument() {
    }

    /**
     * The argument. {@code speciesTyped} reads the species id out of the values typed for the command's earlier
     * arguments (the first after the command's name is element 0), or answers null where no variant belongs, as after
     * {@code /fmm petadmin mode creatures}.
     */
    public static DynamicListStringCommandArgument create(Function<List<String>, String> speciesTyped, String hint) {
        return new DynamicListStringCommandArgument(PetVariantArgument::everyVariant,
                earlier -> suggestions(earlier, speciesTyped, PetVariantArgument::variantsOf), hint);
    }

    /**
     * The variants to suggest after these earlier arguments: those of the species they name, and none when they name
     * no species, one that is not installed, or one without variants.
     */
    public static List<String> suggestions(List<String> earlier, Function<List<String>, String> speciesTyped,
                                           Function<String, List<String>> variantsOf) {
        String species = speciesTyped.apply(earlier);
        if (species == null) return List.of();
        List<String> variants = variantsOf.apply(species);
        return variants == null ? List.of() : variants;
    }

    private static List<String> variantsOf(String speciesId) {
        PetManager manager = PetManager.get();
        PetSpecies species = manager == null ? null : manager.species(speciesId);
        return species == null ? List.of() : species.variants().stream().map(PetSpecies.Variant::model).toList();
    }

    private static List<String> everyVariant() {
        List<String> values = new ArrayList<>();
        PetManager manager = PetManager.get();
        if (manager != null)
            for (PetSpecies species : manager.species())
                for (PetSpecies.Variant variant : species.variants()) values.add(variant.model());
        return values;
    }
}
