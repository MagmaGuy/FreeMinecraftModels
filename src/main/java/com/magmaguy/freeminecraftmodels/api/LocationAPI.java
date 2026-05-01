package com.magmaguy.freeminecraftmodels.api;

import com.magmaguy.magmacore.location.LocationQueryRegistry;
import com.magmaguy.magmacore.location.RegionProtectionProvider;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;

import java.util.function.Predicate;

/**
 * Public API for external plugins to contribute dungeon and region-protection detection
 * to FMM's Lua {@code em.location.is_in_dungeon} and {@code em.location.is_protected}
 * checks.
 * <p>
 * Plugins pass a plain {@link Predicate}{@code <Location>} rather than an FMM-specific
 * interface so no shaded types are exchanged across plugin classloaders.
 */
public final class LocationAPI {

    private LocationAPI() {
    }

    /**
     * Registers a dungeon predicate. A location is reported as being inside a dungeon
     * whenever any registered predicate returns {@code true} for it.
     *
     * @param providerName short identifier for logging (e.g. {@code "EliteMobs"})
     * @param predicate    returns {@code true} if the location is inside a dungeon
     */
    public static void registerDungeonLocator(String providerName, Predicate<Location> predicate) {
        if (predicate == null) return;
        LocationQueryRegistry.registerDungeonLocator(predicate::test);
        Logger.info("[FMM] Registered dungeon locator provider: " + providerName);
    }

    /**
     * Registers a region-protection predicate. A location is reported as protected whenever
     * any registered predicate returns {@code true} for it.
     *
     * @param providerName short identifier for logging and diagnostics
     * @param predicate    returns {@code true} if the location is inside a protected region
     */
    public static void registerProtectionProvider(String providerName, Predicate<Location> predicate) {
        if (predicate == null) return;
        LocationQueryRegistry.registerProtectionProvider(new RegionProtectionProvider() {
            @Override
            public boolean isProtected(Location location) {
                return predicate.test(location);
            }

            @Override
            public String providerName() {
                return providerName;
            }
        });
        Logger.info("[FMM] Registered protection provider: " + providerName);
    }
}
