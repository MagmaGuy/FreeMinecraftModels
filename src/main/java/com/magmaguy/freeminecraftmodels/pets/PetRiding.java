package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Llama;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * How a saddled adult of the species rides, from the pet file's {@code riding:} section. A species without one cannot
 * be ridden. {@code speed} is the body's movement speed while ridden (a horse's ranges 0.11 to 0.34), which the pet's
 * speed stat scales; {@code jump} its jump strength; {@code seatHeight} the rider's seat above the model's feet in
 * Blockbench pixels at scale 1; {@code animation} what the model plays while ridden and moving, in place of walk; and
 * {@code surfaces} multiplies the speed on the listed blocks, the block at the pet's feet first (a snow layer), then
 * the one under them.
 */
public record PetRiding(double speed, double jump, double seatHeight, String animation, Map<Material, Double> surfaces) {
    private static final double SPEED = 0.2D, JUMP = 0.4D, SEAT_HEIGHT = 10D;
    private static final String ANIMATION = "slide";
    /** Snow is quick and ice already slides with vanilla friction, so it needs less help. */
    private static final Map<Material, Double> SURFACES = new LinkedHashMap<>();

    static {
        for (Material snow : new Material[]{Material.SNOW, Material.SNOW_BLOCK, Material.POWDER_SNOW})
            SURFACES.put(snow, 1.5D);
        for (Material ice : new Material[]{Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE})
            SURFACES.put(ice, 1.2D);
    }

    public PetRiding {
        surfaces = Map.copyOf(surfaces);
    }

    /** The riding section's settings, defaults for those it leaves out, or null without a section. */
    static PetRiding read(ConfigurationSection section, String filename, Consumer<String> warn) {
        if (section == null) return null;
        String animation = section.getString("animation", ANIMATION);
        Map<Material, Double> surfaces = SURFACES;
        ConfigurationSection written = section.getConfigurationSection("surfaces");
        if (written != null) {
            surfaces = new LinkedHashMap<>();
            for (String key : written.getKeys(false)) {
                String name = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
                Material material = Material.getMaterial(name.toUpperCase(Locale.ROOT));
                if (material == null) {
                    warn.accept(filename + ": riding.surfaces names " + key + ", which is not a material; skipped.");
                    continue;
                }
                surfaces.put(material, Math.max(0D, written.getDouble(key, 1D)));
            }
        }
        return new PetRiding(Math.max(0D, section.getDouble("speed", SPEED)), Math.max(0D, section.getDouble("jump", JUMP)),
                Math.max(0D, section.getDouble("seatHeight", SEAT_HEIGHT)),
                animation == null || animation.isBlank() ? ANIMATION : animation, surfaces);
    }

    /**
     * Whether this body and locomotion can carry a rider: a saddled horse-family body the rider's client steers, on a
     * species that walks. Reports why not to {@code warn}.
     */
    static boolean supports(EntityType body, MindBodyLocomotion locomotion, String filename, Consumer<String> warn) {
        Class<?> type = body.getEntityClass();
        if (type == null || !AbstractHorse.class.isAssignableFrom(type) || Llama.class.isAssignableFrom(type)) {
            warn.accept(filename + ": riding needs a body a rider steers with a saddle (HORSE, DONKEY, MULE, CAMEL, "
                    + "SKELETON_HORSE or ZOMBIE_HORSE), not " + body + "; the species cannot be ridden.");
            return false;
        }
        if (locomotion != MindBodyLocomotion.GROUNDED && locomotion != MindBodyLocomotion.AMPHIBIOUS) {
            warn.accept(filename + ": riding needs locomotion GROUNDED or AMPHIBIOUS, not " + locomotion
                    + "; the species cannot be ridden.");
            return false;
        }
        return true;
    }

    /** The speed multiplier for a pet standing in {@code feet} on top of {@code below}. */
    public double multiplier(Material feet, Material below) {
        Double atFeet = surfaces.get(feet);
        if (atFeet != null) return atFeet;
        return surfaces.getOrDefault(below, 1D);
    }
}
