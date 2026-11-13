package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pet file's riding section: how fast, how high and on which blocks a saddled adult goes faster. */
class PetRidingTest {
    private static final String FILE = "penguin.pet.yml";

    private static PetRiding read(String yaml, List<String> warnings) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return PetRiding.read(config.getConfigurationSection("riding"), FILE, warnings::add);
    }

    @Test
    void aSpeciesWithoutARidingSectionCannotBeRidden() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertNull(read("name: Penguin\n", warnings));
        assertEquals(List.of(), warnings);
    }

    @Test
    void anEmptyRidingSectionTakesTheDefaults() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        PetRiding riding = read("riding: {}\n", warnings);
        assertEquals(0.2D, riding.speed());
        assertEquals(0.4D, riding.jump());
        assertEquals(10D, riding.seatHeight());
        assertEquals("ride", riding.animation());
        assertEquals(1.5D, riding.multiplier(Material.AIR, Material.SNOW_BLOCK));
        assertEquals(1.2D, riding.multiplier(Material.AIR, Material.BLUE_ICE));
        assertEquals(List.of(), warnings);
    }

    @Test
    void writtenSurfacesReplaceTheDefaults() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        PetRiding riding = read("""
                riding:
                  speed: 0.3
                  jump: 0.6
                  seatHeight: 12
                  animation: glide
                  surfaces:
                    sand: 2
                """, warnings);
        assertEquals(0.3D, riding.speed());
        assertEquals(0.6D, riding.jump());
        assertEquals(12D, riding.seatHeight());
        assertEquals("glide", riding.animation());
        assertEquals(Map.of(Material.SAND, 2D), riding.surfaces());
        assertEquals(1D, riding.multiplier(Material.AIR, Material.SNOW_BLOCK), "only the written surfaces count");
        assertEquals(List.of(), warnings);
    }

    @Test
    void aSnowLayerAtTheFeetCountsBeforeTheBlockBelow() throws InvalidConfigurationException {
        PetRiding riding = read("riding: {}\n", new ArrayList<>());
        assertEquals(1.5D, riding.multiplier(Material.SNOW, Material.PACKED_ICE));
        assertEquals(1.2D, riding.multiplier(Material.AIR, Material.PACKED_ICE));
        assertEquals(1D, riding.multiplier(Material.AIR, Material.GRASS_BLOCK));
        assertEquals(1D, riding.multiplier(Material.SHORT_GRASS, Material.GRASS_BLOCK));
    }

    @Test
    void anUnknownSurfaceIsReportedAndSkipped() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        PetRiding riding = read("""
                riding:
                  surfaces:
                    snow_block: 1.5
                    slush: 3
                """, warnings);
        assertEquals(Map.of(Material.SNOW_BLOCK, 1.5D), riding.surfaces());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("slush"), warnings.getFirst());
    }

    @Test
    void aSpeciesDivesOnlyWhenItsRidingSectionSetsASwimSpeed() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertFalse(read("riding: {}\n", warnings).dives());
        PetRiding diver = read("riding:\n  swimSpeed: 14\n", warnings);
        assertTrue(diver.dives());
        assertEquals(14D, diver.swimSpeed());
        assertFalse(read("riding:\n  swimSpeed: -3\n", warnings).dives());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aDiverSwimsItsSwimSpeedTimesItsSpeedStatAsARiddenNautilus() throws InvalidConfigurationException {
        PetRiding diver = read("riding:\n  swimSpeed: 14\n", new ArrayList<>());
        // A ridden nautilus moves 10 x 0.0325 blocks per tick per point of movement speed once sped up: 6.5 a second.
        assertEquals(6.5D, PetRiding.NAUTILUS_BLOCKS_PER_SECOND, 1.0E-9);
        assertEquals(14D / 6.5D, diver.swimMovementSpeed(1D), 1.0E-9);
        assertEquals(2D * 14D / 6.5D, diver.swimMovementSpeed(2D), 1.0E-9);
    }

    @Test
    void aRiddenPetsStepSoundsAreReadByBlock() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(Map.of(), read("riding: {}\n", warnings).stepSounds());
        PetRiding riding = read("riding:\n  stepSounds:\n    ice: block.powder_snow.step\n    minecraft:packed_ice: my_pack:slide\n"
                + "    glacier: block.snow.step\n    blue_ice: ''\n", warnings);
        assertEquals(Map.of(Material.ICE, "block.powder_snow.step", Material.PACKED_ICE, "my_pack:slide"), riding.stepSounds());
        assertEquals(List.of(FILE + ": riding.stepSounds names glacier, which is not a material; skipped.",
                FILE + ": riding.stepSounds gives blue_ice no sound; skipped."), warnings);
    }

    @Test
    void aRiddenPetsJumpSoundIsANameOrASectionAndNoneByDefault() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(null, read("riding: {}\n", warnings).jumpSound());
        assertEquals(null, read("riding:\n  jumpSound: NONE\n", warnings).jumpSound());
        assertEquals(new PetRiding.JumpSound("entity.rabbit.jump", 0.4F, 1F),
                read("riding:\n  jumpSound: Entity.Rabbit.Jump\n", warnings).jumpSound());
        assertEquals(new PetRiding.JumpSound("my_pack:penguin.jump", 0.6F, 0.8F),
                read("riding:\n  jumpSound:\n    sound: my_pack:penguin.jump\n    volume: 0.6\n    pitch: 0.8\n", warnings).jumpSound());
        // Kept within what a sound packet takes.
        assertEquals(new PetRiding.JumpSound("entity.rabbit.jump", 4F, 0.5F),
                read("riding:\n  jumpSound:\n    sound: entity.rabbit.jump\n    volume: 9\n    pitch: 0.1\n", warnings).jumpSound());
        assertEquals(List.of(), warnings);
        assertEquals(null, read("riding:\n  jumpSound:\n    volume: 0.6\n", warnings).jumpSound());
        assertEquals(List.of(FILE + ": riding.jumpSound names no sound; a jump makes none."), warnings);
    }

    @Test
    void aJumpIsHeardAsTheBodyLeavesTheGroundRisingSoonAfterItsRiderAsked() {
        // Asked on the ground, the body is off it a tick or two later, 0.4 blocks up.
        assertTrue(PetRides.leapt(true, false, 0.4D, 1L));
        assertTrue(PetRides.leapt(true, false, 0.1D, PetRides.JUMP_WINDOW_TICKS));
        // Still on the ground, or already in the air last tick: not the tick it leaves.
        assertFalse(PetRides.leapt(true, true, 0D, 1L));
        assertFalse(PetRides.leapt(false, false, 0.3D, 2L));
        // Walking off an edge sinks; a step up a block keeps it on the ground.
        assertFalse(PetRides.leapt(true, false, -0.08D, 1L));
        assertFalse(PetRides.leapt(true, true, 1D, 1L));
        // Asked too long ago: that jump never came.
        assertFalse(PetRides.leapt(true, false, 0.4D, PetRides.JUMP_WINDOW_TICKS + 1L));
    }

    @Test
    void aJumpAskedForOnTheGroundIsHeardAtTheAskingAndNotAgainAsTheBodyLeavesTheGround() {
        PetRides.Jump jump = new PetRides.Jump();
        // The penguin's jump strength of 0.4 at the least power a rider's client sends, under open sky.
        assertTrue(jump.asked(100L, true, 0.16D, Double.POSITIVE_INFINITY));
        // Its rider's client lifts it a tick or two later: that is the jump already heard.
        assertFalse(jump.left(102L, true, false, 0.16D));
    }

    @Test
    void aJumpAskedForInTheAirIsHeardOnceAsTheBodyLeavesTheGroundAgain() {
        PetRides.Jump jump = new PetRides.Jump();
        assertFalse(jump.asked(100L, false, 0.4D, Double.POSITIVE_INFINITY));
        // Still falling, then landed: no jump yet.
        assertFalse(jump.left(101L, false, false, -0.3D));
        assertFalse(jump.left(103L, false, true, -0.2D));
        // It jumps as it lands.
        assertTrue(jump.left(104L, true, false, 0.4D));
        // Leaving the ground again without its rider asking is no jump of theirs.
        assertFalse(jump.left(120L, true, false, 0.4D));
    }

    @Test
    void aJumpThatWouldNotLiftTheBodyIsNotHeardAtTheAsking() {
        // Too little power for the body's jump strength: it rises less than a jump does in its first tick.
        assertFalse(new PetRides.Jump().asked(100L, true, 0.08D, Double.POSITIVE_INFINITY));
        // A block over the body, nearer than a jump's first tick rises.
        assertFalse(new PetRides.Jump().asked(100L, true, 0.4D, 0.05D));
        assertTrue(new PetRides.Jump().asked(100L, true, PetRides.JUMP_RISE, PetRides.JUMP_RISE));
        // Unheard at the asking, it is still heard if the body leaves the ground rising after all.
        PetRides.Jump jump = new PetRides.Jump();
        assertFalse(jump.asked(100L, true, 0.4D, 0.05D));
        assertFalse(jump.left(101L, true, false, 0.05D));
        assertTrue(jump.left(110L, true, false, 0.4D));
    }

    @Test
    void aJumpHeardAtTheAskingEndsTheWaitForAnEarlierOne() {
        PetRides.Jump jump = new PetRides.Jump();
        // Asked for in the air and never seen leaving the ground.
        assertFalse(jump.asked(100L, false, 0.4D, Double.POSITIVE_INFINITY));
        assertTrue(jump.asked(115L, true, 0.4D, Double.POSITIVE_INFINITY));
        // One sound for the new jump, not a second for the old.
        assertFalse(jump.left(116L, true, false, 0.4D));
    }

    @Test
    void theHopOutOfTheWaterIsAPlayersJumpUnlessTheFileSaysOtherwise() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(0.42D, read("riding: {}\n", warnings).surfaceHop());
        assertEquals(0D, read("riding:\n  surfaceHop: 0\n", warnings).surfaceHop());
        assertEquals(0D, read("riding:\n  surfaceHop: -1\n", warnings).surfaceHop());
        assertEquals(0.3D, read("riding:\n  surfaceHop: 0.3\n", warnings).surfaceHop());
        // 14 blocks a second at a speed stat of 1 is 0.7 blocks a tick.
        assertEquals(0.7D, read("riding:\n  swimSpeed: 14\n", warnings).swimStep(1D), 1.0E-9D);
        assertEquals(List.of(), warnings);
    }

    @Test
    void onlyAWalkingHorseBodyCanBeRidden() {
        List<String> warnings = new ArrayList<>();
        assertTrue(PetRiding.supports(EntityType.HORSE, MindBodyLocomotion.AMPHIBIOUS, FILE, warnings::add));
        assertTrue(PetRiding.supports(EntityType.DONKEY, MindBodyLocomotion.GROUNDED, FILE, warnings::add));
        assertEquals(List.of(), warnings);
        assertFalse(PetRiding.supports(EntityType.ALLAY, MindBodyLocomotion.FLYING, FILE, warnings::add));
        assertFalse(PetRiding.supports(EntityType.HORSE, MindBodyLocomotion.FLYING, FILE, warnings::add));
        assertFalse(PetRiding.supports(EntityType.PIG, MindBodyLocomotion.GROUNDED, FILE, warnings::add));
        assertEquals(3, warnings.size(), warnings.toString());
    }
}
