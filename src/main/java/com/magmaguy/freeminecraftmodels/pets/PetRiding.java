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
 * {@code surfaceHop} is the upward speed, in blocks a tick, a diving pet is given as it surfaces in the shallows at
 * full swim speed, keeping that speed, so it jumps out of the water (see {@link RideHop}); slower, it gets its share.
 * The default is a player's own jump, 0.42, which rises about 1.2 blocks; 0 turns the hop off.
 * {@code stepSounds} names, by block, the sound a ridden pet's footstep makes on that block in place of the block's
 * own step sound (see {@link PetSteps}): a Minecraft sound or a resource pack's own. Blocks it leaves out keep theirs.
 * {@code jumpSound} is the sound a ridden pet makes as it jumps with its rider on land and as it hops out of the
 * water (see {@link JumpSound}); null, the default, makes none, since every pet body is silent.
 */
public record PetRiding(double speed, double jump, double seatHeight, String animation, String idleAnimation,
                        Map<Material, Double> surfaces, double swimSpeed, double surfaceHop,
                        Map<Material, String> stepSounds, JumpSound jumpSound) {
    /**
     * The sound of a ridden pet's jump: a Minecraft sound or a resource pack's own, at a volume (1 is the sound as
     * recorded, heard 16 blocks away) and a pitch (0.5 to 2). The pet file gives it as a name alone, at
     * {@link #VOLUME} and pitch 1, or as a section with {@code sound}, {@code volume} and {@code pitch}.
     */
    public record JumpSound(String sound, float volume, float pitch) {
        /** A jump is one soft accent over the footsteps, which play at 0.15: well under a mob's own voice at 1. */
        static final float VOLUME = 0.4F;
    }

    /**
     * Blocks per second a ridden vanilla nautilus swims per point of movement speed. Its rider's client adds
     * 0.0325 x speed along the rider's input to its velocity, moves it by that velocity and then keeps 0.9 of it, so
     * each tick it moves 0.0325 / (1 - 0.9) blocks once it has sped up; measured on a real client at 6.5.
     */
    static final double NAUTILUS_BLOCKS_PER_SECOND = 20D * 0.0325D / (1D - 0.9D);
    private static final double SPEED = 0.2D, JUMP = 0.4D, SEAT_HEIGHT = 10D, SURFACE_HOP = 0.42D;
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
        stepSounds = Map.copyOf(stepSounds);
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
        Map<Material, String> stepSounds = new LinkedHashMap<>();
        ConfigurationSection sounds = section.getConfigurationSection("stepSounds");
        if (sounds != null)
            for (String key : sounds.getKeys(false)) {
                String name = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
                Material material = Material.getMaterial(name.toUpperCase(Locale.ROOT));
                String sound = sounds.getString(key);
                if (material == null) {
                    warn.accept(filename + ": riding.stepSounds names " + key + ", which is not a material; skipped.");
                    continue;
                }
                if (sound == null || sound.isBlank()) {
                    warn.accept(filename + ": riding.stepSounds gives " + key + " no sound; skipped.");
                    continue;
                }
                stepSounds.put(material, sound.trim());
            }
        JumpSound jumpSound = jumpSound(section, filename, warn);
        return new PetRiding(Math.max(0D, section.getDouble("speed", SPEED)), Math.max(0D, section.getDouble("jump", JUMP)),
                Math.max(0D, section.getDouble("seatHeight", SEAT_HEIGHT)), name(section, "animation", ANIMATION),
                name(section, "idleAnimation", IDLE_ANIMATION), surfaces, Math.max(0D, section.getDouble("swimSpeed", 0D)),
                Math.max(0D, section.getDouble("surfaceHop", SURFACE_HOP)), stepSounds, jumpSound);
    }

    /** The section's jumpSound: a sound's name, or a section naming one with a volume and a pitch; null without one. */
    private static JumpSound jumpSound(ConfigurationSection section, String filename, Consumer<String> warn) {
        ConfigurationSection written = section.getConfigurationSection("jumpSound");
        String sound = written != null ? written.getString("sound") : section.getString("jumpSound");
        if (sound == null || sound.isBlank() || sound.trim().equalsIgnoreCase("NONE")) {
            if (written != null) warn.accept(filename + ": riding.jumpSound names no sound; a jump makes none.");
            return null;
        }
        double volume = written == null ? JumpSound.VOLUME : written.getDouble("volume", JumpSound.VOLUME);
        double pitch = written == null ? 1D : written.getDouble("pitch", 1D);
        return new JumpSound(sound.trim().toLowerCase(Locale.ROOT), (float) Math.max(0D, Math.min(4D, volume)),
                (float) Math.max(0.5D, Math.min(2D, pitch)));
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

    /** How far a diving pet swims in a tick at full speed, in blocks, for a pet with this speed stat. */
    public double swimStep(double statSpeed) {
        return swimSpeed * statSpeed / 20D;
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
