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
 * Blockbench pixels at scale 1; {@code animation} what the model plays while ridden and moving, in place of walk
 * ({@code ride} by default); {@code idleAnimation} what it plays while ridden and standing still, in place of idle
 * ({@code idle.ride} by default; a model without that animation idles as it does unridden); and {@code surfaces} multiplies the speed on the listed blocks, the block at
 * the pet's feet first (a snow layer), then the one under them. {@code swimSpeed} is how fast, in blocks per second, a
 * ridden adult swims once it dives in deep water, times its speed stat; at 0, the default, it does not dive.
 */
public record PetRiding(double speed, double jump, double seatHeight, String animation, String idleAnimation,
                        Map<Material, Double> surfaces, double swimSpeed) {
    /**
     * Blocks per second a ridden vanilla nautilus swims per point of movement speed. Its rider's client adds
     * 0.0325 x speed along the rider's input to its velocity, moves it by that velocity and then keeps 0.9 of it, so
     * each tick it moves 0.0325 / (1 - 0.9) blocks once it has sped up; measured on a real client at 6.5.
     */
    static final double NAUTILUS_BLOCKS_PER_SECOND = 20D * 0.0325D / (1D - 0.9D);
    private static final double SPEED = 0.2D, JUMP = 0.4D, SEAT_HEIGHT = 10D;
    private static final String ANIMATION = "ride", IDLE_ANIMATION = "idle.ride";
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
                Math.max(0D, section.getDouble("seatHeight", SEAT_HEIGHT)), name(section, "animation", ANIMATION),
                name(section, "idleAnimation", IDLE_ANIMATION), surfaces, Math.max(0D, section.getDouble("swimSpeed", 0D)));
    }

    /** An animation name the section sets, or the default for a missing or blank one. */
    private static String name(ConfigurationSection section, String key, String fallback) {
        String name = section.getString(key, fallback);
        return name == null || name.isBlank() ? fallback : name;
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

    /** Whether a ridden pet of the species dives in deep water: its riding section sets a swim speed. */
    public boolean dives() {
        return swimSpeed > 0D;
    }

    /** The movement speed that swims a diving body at swimSpeed blocks a second times the pet's speed stat. */
    public double swimMovementSpeed(double statSpeed) {
        return swimSpeed * statSpeed / NAUTILUS_BLOCKS_PER_SECOND;
    }

    /** The speed multiplier for a pet standing in {@code feet} on top of {@code below}. */
    public double multiplier(Material feet, Material below) {
        Double atFeet = surfaces.get(feet);
        if (atFeet != null) return atFeet;
        return surfaces.getOrDefault(below, 1D);
    }
}
