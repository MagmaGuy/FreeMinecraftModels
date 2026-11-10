package com.magmaguy.freeminecraftmodels.customentity.core.components;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The players of each world and where they stood, read once per hitbox pass instead of once per model. A pass checks
 * every contact-enabled model against the players of its world; reading the world's players and each one's location
 * for every model made a list and a location per model and player. A world is read the first time a model in it asks,
 * so a world without contact-enabled models costs nothing, and a world without players gives none to check. Primary
 * thread, for one pass only.
 */
public final class PlayerPositions {
    private final Map<World, InWorld> byWorld = new HashMap<>();

    /** The players of this world, and where each stood, as the pass first saw them. */
    InWorld of(World world) {
        return byWorld.computeIfAbsent(world, InWorld::read);
    }

    /** One world's players and their positions, by index. */
    static final class InWorld {
        private final Player[] players;
        private final double[] x, y, z;

        private InWorld(Player[] players, double[] x, double[] y, double[] z) {
            this.players = players;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private static InWorld read(World world) {
            List<Player> list = world.getPlayers();
            int size = list.size();
            Player[] players = new Player[size];
            double[] x = new double[size], y = new double[size], z = new double[size];
            for (int i = 0; i < size; i++) {
                Player player = list.get(i);
                Location at = player.getLocation();
                players[i] = player;
                x[i] = at.getX();
                y[i] = at.getY();
                z[i] = at.getZ();
            }
            return new InWorld(players, x, y, z);
        }

        int size() {
            return players.length;
        }

        Player player(int index) {
            return players[index];
        }

        /** The squared distance from player {@code index} to the point, as {@link Location#distanceSquared} gives it. */
        double distanceSquared(int index, double px, double py, double pz) {
            double dx = x[index] - px, dy = y[index] - py, dz = z[index] - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
