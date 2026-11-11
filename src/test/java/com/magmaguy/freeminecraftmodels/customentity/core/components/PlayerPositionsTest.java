package com.magmaguy.freeminecraftmodels.customentity.core.components;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The hitbox pass reads each world's players once, and measures from where they stood then. */
class PlayerPositionsTest {
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object plain(Object proxy, java.lang.reflect.Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> null;
        };
    }

    /** A world whose player list can change, counting how often it is read. */
    private static final class Players {
        final List<Player> list = new ArrayList<>();
        int reads;
        final World world = stub(World.class, (proxy, method, args) -> {
            if (!method.getName().equals("getPlayers")) return plain(proxy, method, args);
            reads++;
            return new ArrayList<>(list);
        });

        /** A player whose place the returned array holds, so a test can move them. */
        double[] add(double x, double y, double z) {
            double[] at = {x, y, z};
            list.add(stub(Player.class, (proxy, method, args) -> method.getName().equals("getLocation")
                    ? new Location(world, at[0], at[1], at[2]) : plain(proxy, method, args)));
            return at;
        }
    }

    @Test
    void aWorldsPlayersAreReadOnceForThePass() {
        Players overworld = new Players(), nether = new Players();
        double[] first = overworld.add(1, 64, 2);
        overworld.add(-5, 70, 9);
        PlayerPositions pass = new PlayerPositions();
        assertEquals(0, overworld.reads + nether.reads, "a world no model asks about is never read");
        PlayerPositions.InWorld seen = pass.of(overworld.world);
        assertEquals(2, seen.size());
        assertSame(overworld.list.get(0), seen.player(0));
        assertSame(overworld.list.get(1), seen.player(1));
        assertEquals(new Location(overworld.world, 1, 64, 2).distanceSquared(new Location(overworld.world, 4, 60, 2)),
                seen.distanceSquared(0, 4, 60, 2), 1.0E-9, "the distance Location.distanceSquared gives");
        assertEquals(36 + 36 + 81, seen.distanceSquared(1, 1, 64, 0), 1.0E-9);
        // Every later model of the pass gets the same snapshot: a player who moved or joined since is as first seen.
        first[0] = 100;
        overworld.add(0, 0, 0);
        assertSame(seen, pass.of(overworld.world));
        assertEquals(1, overworld.reads);
        assertEquals(2, seen.size());
        assertEquals(0, seen.distanceSquared(0, 1, 64, 2), 1.0E-9, "where they stood when the pass first looked");
        // A world without players gives none to check, and each world is its own.
        assertEquals(0, pass.of(nether.world).size());
        assertEquals(1, nether.reads);
        // The next pass reads afresh.
        PlayerPositions.InWorld next = new PlayerPositions().of(overworld.world);
        assertEquals(3, next.size());
        assertEquals(0, next.distanceSquared(0, 100, 64, 2), 1.0E-9);
        assertEquals(2, overworld.reads);
    }
}
