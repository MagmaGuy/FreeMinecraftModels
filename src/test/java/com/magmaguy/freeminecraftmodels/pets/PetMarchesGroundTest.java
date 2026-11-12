package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ground a march's legs run over, read from a made-up hillside: stone up to y 63 with air above it, and from x 6
 * on the top of a tree that grows lower down, level with the stone: spruce leaves at y 63 under a layer of snow, with
 * nothing under them.
 */
class PetMarchesGroundTest {
    private static Material type(int x, int y) {
        if (x >= 6) return y == 64 ? Material.SNOW : y == 63 ? Material.SPRUCE_LEAVES : Material.AIR;
        return y <= 63 ? Material.STONE : Material.AIR;
    }

    private static Block block(Material type) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> type;
                    case "isPassable" -> type == Material.AIR || type == Material.SNOW;
                    case "isLiquid" -> false;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> type.name();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static World hillside() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    case "isChunkLoaded" -> true;
                    case "getBlockAt" -> block(type((Integer) args[0], (Integer) args[1]));
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "hillside";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void aTreesLeavesAreNoGroundForAMarch() {
        assertTrue(PetMotion.isLeaves(Material.SPRUCE_LEAVES));
        assertTrue(PetMotion.isLeaves(Material.FLOWERING_AZALEA_LEAVES));
        assertFalse(PetMotion.isLeaves(Material.SPRUCE_LOG));
        assertFalse(PetMotion.isLeaves(Material.SNOW));
        assertTrue(PetMarches.footing(block(Material.STONE)));
        assertFalse(PetMarches.footing(block(Material.SPRUCE_LEAVES)), "leaves are solid, and still no footing");
        assertFalse(PetMarches.footing(block(Material.SNOW)), "a snow layer is stood in, not on");

        MarchRoute.Ground ground = PetMarches.ground(hillside());
        assertEquals(64, ground.standing(5, 64, 0), "stone under the feet is ground");
        assertEquals(MarchRoute.Ground.NO_FOOTING, ground.standing(6, 64, 0), "snow on a tree's leaves is not");
        // A leg toward the tree stops at its edge instead of running out over the treetop.
        assertEquals(5.0, MarchRoute.run(ground, new Vector(0.5, 64, 0.5), 1, 0, 24).length(), 1.0E-9);
    }
}
