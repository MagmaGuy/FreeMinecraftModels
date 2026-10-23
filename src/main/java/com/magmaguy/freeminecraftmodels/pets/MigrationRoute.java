package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.Location;

/**
 * The path a migrating pet flies instead of its usual life: a wild pet with a route follows it at the top of its Mind
 * program until the route ends or a player tames it. The route also owns the pet's lifetime, so the usual wild despawn
 * rules leave it alone.
 */
public interface MigrationRoute {
    /** Where the pet should be heading this tick, or null once the route is over. */
    Location target(PetControl pet);

    /** Blocks per tick the pet may fly to keep its place on the route. */
    double cruise();
}
