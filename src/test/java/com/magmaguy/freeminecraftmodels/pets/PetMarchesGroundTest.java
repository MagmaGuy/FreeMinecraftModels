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

    /**
     * A wood on level stone whose top is y 63: a spruce trunk three blocks tall at x 5, z 1; a leaf block at head
     * height, y 65, at x 9, z 1; a step up of one block from x 13 on along z 1; a hole two deep at x 17, z 1.
     */
    private static Material wood(int x, int y, int z) {
        if (z == 1 && x == 5 && y >= 64 && y <= 66) return Material.SPRUCE_LOG;
        if (z == 1 && x == 9 && y == 65) return Material.SPRUCE_LEAVES;
        if (z == 1 && x >= 13 && x <= 15 && y == 64) return Material.STONE;
        if (z == 1 && x == 17 && y >= 62) return Material.AIR;
        return y <= 63 ? Material.STONE : Material.AIR;
    }

    private static World woodland() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    case "isChunkLoaded" -> true;
                    case "getBlockAt" -> block(wood((Integer) args[0], (Integer) args[1], (Integer) args[2]));
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "woodland";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
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

    @Test
    void aLegKeepsItsBodysWidthClearOfATrunkOrLeavesBesideIt() {
        MarchRoute.Ground ground = PetMarches.ground(woodland());
        assertTrue(ground.walled(5, 64, 1), "a trunk is a wall");
        assertTrue(ground.walled(9, 64, 1), "and so are leaves at the height of a marcher's head");
        assertFalse(ground.walled(13, 64, 1), "a step up of one block is not: a marcher steps onto it");
        assertFalse(ground.walled(17, 64, 1), "nor is a hole beside the way: nothing there is in the body's way");
        assertFalse(ground.walled(3, 64, 1), "nor open ground");
        // Along z 0.5, the middle of its row of blocks, a body 0.7 wide passes the trunk in the next row.
        assertEquals(24.0, MarchRoute.run(ground, new Vector(0.5, 64, 0.5), 1, 0, 24).length(), 1.0E-9,
                "a line half a block from the trunk's row is clear all the way");
        // Along z 0.8 the line itself still misses the trunk, by 0.2, and the body would not.
        assertEquals(4.0, MarchRoute.run(ground, new Vector(0.5, 64, 0.8), 1, 0, 24).length(), 1.0E-9,
                "a line a fifth of a block from the trunk stops before it");
        assertEquals(2.0, MarchRoute.run(ground, new Vector(6.5, 64, 0.8), 1, 0, 24).length(), 1.0E-9,
                "and before the leaves at head height");
        assertEquals(24.0, MarchRoute.run(ground, new Vector(10.5, 64, 0.8), 1, 0, 24).length(), 1.0E-9,
                "it passes the step up and the hole beside it");
        // A turn away from the trunk's row is found as for a wall ahead.
        double[] turned = MarchRoute.turn(ground, new Vector(4.4, 64, 0.8), 1, 0, MarchRoute.MIN_LEG, false);
        assertTrue(turned[1] < -1.0E-6, "the smallest turn that clears the trunk leads away from it: " + turned[1]);
    }
}
