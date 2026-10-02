package com.magmaguy.freeminecraftmodels.api;

import com.magmaguy.magmacore.location.DungeonLocator;
import com.magmaguy.magmacore.location.LocationQueryRegistry;
import com.magmaguy.magmacore.location.RegionProtectionProvider;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    // A provider registers again every time its plugin enables. Keying by provider name
    // replaces the previous entry, so FMM stops calling, and holding on to, a reloaded instance.
    // The swap runs inside compute so concurrent registrations under one name leave exactly one entry.
    private static final Map<String, DungeonLocator> dungeonLocators = new ConcurrentHashMap<>();
    private static final Map<String, RegionProtectionProvider> protectionProviders = new ConcurrentHashMap<>();

    private LocationAPI() {
    }

    /**
     * Registers a dungeon predicate. A location is reported as being inside a dungeon
     * whenever any registered predicate returns {@code true} for it. Registering again
     * under the same provider name replaces the earlier predicate.
     *
     * @param providerName short identifier for logging and replacement (e.g. {@code "EliteMobs"});
     *                     a {@code null} name can be neither replaced nor unregistered
     * @param predicate    returns {@code true} if the location is inside a dungeon
     */
    public static void registerDungeonLocator(String providerName, Predicate<Location> predicate) {
        if (predicate == null) return;
        DungeonLocator locator = predicate::test;
        if (providerName == null) LocationQueryRegistry.registerDungeonLocator(locator);
        else dungeonLocators.compute(providerName, (name, previous) -> {
            if (previous != null) LocationQueryRegistry.unregisterDungeonLocator(previous);
            LocationQueryRegistry.registerDungeonLocator(locator);
            return locator;
        });
        Logger.info("[FMM] Registered dungeon locator provider: " + providerName);
    }

    /**
     * Removes the dungeon predicate registered under {@code providerName}, if any. Call it
     * from the providing plugin's {@code onDisable} so FMM stops calling into, and holding
     * on to, the disabled instance.
     *
     * @param providerName the name passed to {@link #registerDungeonLocator}
     */
    public static void unregisterDungeonLocator(String providerName) {
        if (providerName == null) return;
        dungeonLocators.computeIfPresent(providerName, (name, locator) -> {
            LocationQueryRegistry.unregisterDungeonLocator(locator);
            return null;
        });
    }

    /**
     * Registers a region-protection predicate. A location is reported as protected whenever
     * any registered predicate returns {@code true} for it. Registering again under the same
     * provider name replaces the earlier predicate.
     *
     * @param providerName short identifier for logging, diagnostics and replacement;
     *                     a {@code null} name can be neither replaced nor unregistered
     * @param predicate    returns {@code true} if the location is inside a protected region
     */
    public static void registerProtectionProvider(String providerName, Predicate<Location> predicate) {
        if (predicate == null) return;
        RegionProtectionProvider provider = new RegionProtectionProvider() {
            @Override
            public boolean isProtected(Location location) {
                return predicate.test(location);
            }

            @Override
            public String providerName() {
                return providerName;
            }
        };
        if (providerName == null) LocationQueryRegistry.registerProtectionProvider(provider);
        else protectionProviders.compute(providerName, (name, previous) -> {
            if (previous != null) LocationQueryRegistry.unregisterProtectionProvider(previous);
            LocationQueryRegistry.registerProtectionProvider(provider);
            return provider;
        });
        Logger.info("[FMM] Registered protection provider: " + providerName);
    }

    /**
     * Removes the protection predicate registered under {@code providerName}, if any. Call it
     * from the providing plugin's {@code onDisable} so FMM stops calling into, and holding
     * on to, the disabled instance.
     *
     * @param providerName the name passed to {@link #registerProtectionProvider}
     */
    public static void unregisterProtectionProvider(String providerName) {
        if (providerName == null) return;
        protectionProviders.computeIfPresent(providerName, (name, provider) -> {
            LocationQueryRegistry.unregisterProtectionProvider(provider);
            return null;
        });
    }
}
