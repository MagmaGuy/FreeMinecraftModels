package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.customentity.core.TravelOrientation;
import com.magmaguy.magmacore.ai.MindSwimWave;
import org.bukkit.configuration.ConfigurationSection;

import java.util.function.Consumer;

/**
 * How the species swims, from the pet file's {@code swimming:} section; a species without one swims as it walks.
 * {@code animation} is what the model plays while swimming, in place of idle and walk; {@code orientation} how it
 * leans with its travel in water, front first by default, as the species' own {@code orientation} section does on
 * land; and {@code wave} a gentle rise and fall an amphibious pet swims while it travels: {@code height} blocks from
 * crest to trough every {@code seconds}, fading out as it slows down. A wave of height 0 swims level.
 */
public record PetSwimming(String animation, TravelOrientation orientation, MindSwimWave wave) {
    private static final String ANIMATION = "swim";
    /** Leads with its front: pitches into the wave's climbs and dives and banks into turns, no more than 45 degrees. */
    static final TravelOrientation ORIENTATION =
            new TravelOrientation(TravelOrientation.Style.FRONT_FIRST, 0.3D, 2.5D, 45D, 120D);
    private static final double WAVE_HEIGHT = 0.5D, WAVE_SECONDS = 2D;

    /** The swimming section's settings, defaults for those it leaves out, or null without a section. */
    static PetSwimming read(ConfigurationSection section, String filename, Consumer<String> warn) {
        if (section == null) return null;
        String animation = section.getString("animation", ANIMATION);
        ConfigurationSection orientation = section.getConfigurationSection("orientation");
        ConfigurationSection wave = section.getConfigurationSection("wave");
        double height = wave == null ? WAVE_HEIGHT : wave.getDouble("height", WAVE_HEIGHT);
        double seconds = wave == null ? WAVE_SECONDS : wave.getDouble("seconds", WAVE_SECONDS);
        return new PetSwimming(animation == null || animation.isBlank() ? ANIMATION : animation,
                orientation == null ? ORIENTATION
                        : PetSpecies.orientation(orientation, filename, "swimming.orientation", ORIENTATION, warn),
                new MindSwimWave(height, (int) Math.round(seconds * 20D)));
    }
}
