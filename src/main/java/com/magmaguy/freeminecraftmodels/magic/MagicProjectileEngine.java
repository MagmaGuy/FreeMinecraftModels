package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitByProjectileEvent;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * FMM-owned marker projectiles, steering and collision arbitration.
 *
 * <p>Invisible arrows supply the server's proven entity and block collision path. Particles render
 * the magic flight, while this engine cancels all native arrow damage and resolves each marker
 * through one impact ledger.</p>
 */
final class MagicProjectileEngine implements Listener, AutoCloseable {
    private static final double BLOCK_EPSILON = .08D;
    private static final double PROJECTILE_ORIGIN_DROP = .3D;

    private final Plugin plugin;
    private final ImpactHandler impactHandler;
    private final MagicImpactLedger<Flight> flights = new MagicImpactLedger<>();
    private BukkitTask tickTask;
    private long currentTick;
    private boolean closed;

    MagicProjectileEngine(Plugin plugin, ImpactHandler impactHandler) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.impactHandler = java.util.Objects.requireNonNull(impactHandler, "impactHandler");
    }

    void start() {
        if (closed || tickTask != null) throw new IllegalStateException("Magic projectile engine cannot start");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    long currentTick() {
        return currentTick;
    }

    Optional<LivingEntity> acquireTarget(
            Player owner,
            double range,
            double assistDegrees,
            Predicate<LivingEntity> eligibility,
            ToIntFunction<LivingEntity> priority) {
        return acquireTargets(owner, range, assistDegrees, eligibility, priority, 1).stream().findFirst();
    }

    List<LivingEntity> acquireTargets(Player owner, double range, double assistDegrees,
                                      Predicate<LivingEntity> eligibility, ToIntFunction<LivingEntity> priority,
                                      int limit) {
        Location eye = owner.getEyeLocation();
        Vector aim = eye.getDirection();
        if (aim.lengthSquared() < 1.0E-9D) return List.of();
        aim.normalize();
        double minimumDot = Math.cos(Math.toRadians(assistDegrees));
        List<AimCandidate> candidates = new ArrayList<>();
        for (Entity candidate : owner.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(candidate instanceof LivingEntity target) || !validTarget(owner, target)) continue;
            Location center = center(target);
            Vector offset = center.toVector().subtract(eye.toVector());
            double distance = offset.length();
            if (distance <= 1.0E-6D || distance > range) continue;
            double dot = offset.multiply(1D / distance).dot(aim);
            if (dot < minimumDot || !unobstructed(eye, center) || !eligibility.test(target)) continue;
            candidates.add(new AimCandidate(
                    target, priority.applyAsInt(target), (1D - dot) * 100D + distance / range, distance));
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(AimCandidate::tier)
                        .thenComparingDouble(AimCandidate::score)
                        .thenComparingDouble(AimCandidate::distance))
                .limit(Math.max(1, Math.min(3, limit)))
                .map(AimCandidate::target).toList();
    }

    boolean launchWand(MagicCast cast, LivingEntity target) {
        return launchWand(cast, target == null ? List.of() : List.of(target));
    }

    boolean launchWand(MagicCast cast, List<LivingEntity> targets) {
        if (closed) return false;
        int launched = 0;
        int missileCount = cast.definition().traits().missileCount();
        for (int missileIndex = 0; missileIndex < missileCount; missileIndex++) {
            LivingEntity target = targets.isEmpty() ? null : targets.get(missileIndex % targets.size());
            if (target != null && !validTarget(cast.owner(), target)) target = null;
            Location eye = cast.owner().getEyeLocation();
            Vector direction = target == null ? eye.getDirection()
                    : center(target).toVector().subtract(eye.toVector());
            if (direction.lengthSquared() < 1.0E-9D) continue;
            Location start = eye.clone().add(direction.normalize().multiply(.35D))
                    .subtract(0D, PROJECTILE_ORIGIN_DROP, 0D);
            Arrow marker = spawnMarker(cast.owner(), start, direction);
            if (marker == null) continue;
            Flight flight = target == null
                    ? new StraightWandFlight(marker, cast, start, direction)
                    : new WandFlight(marker, cast, start, target, missileIndex, missileCount);
            flights.register(marker.getUniqueId(), flight);
            launched++;
        }
        return launched > 0;
    }

    boolean launchStaff(MagicCast cast, Vector direction) {
        if (closed || direction == null || direction.lengthSquared() < 1.0E-9D) return false;
        Vector normalized = direction.clone().normalize();
        Location start = cast.owner().getEyeLocation()
                .add(normalized.clone().multiply(.45D))
                .subtract(0D, PROJECTILE_ORIGIN_DROP, 0D);
        Arrow marker = spawnMarker(cast.owner(), start, normalized);
        if (marker == null) return false;
        flights.register(marker.getUniqueId(), new StaffFlight(marker, cast, start, normalized));
        return true;
    }

    /**
     * The literal vanilla fireball look without any of the entity: an inert item display that the
     * flight teleports along the marker's path. It cannot explode and never outlives its flight.
     * It deliberately does not ride the marker: the marker is client-invisible, and a passenger of
     * an entity the client never spawned renders frozen at its spawn point.
     */
    private static ItemDisplay spawnFireballVisual(Arrow marker) {
        try {
            World world = marker.getWorld();
            return world.spawn(marker.getLocation(), ItemDisplay.class, visual -> {
                visual.setItemStack(new ItemStack(Material.FIRE_CHARGE));
                visual.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                visual.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                visual.setPersistent(false);
                visual.setTeleportDuration(2);
                visual.setTransformation(new Transformation(
                        new Vector3f(),
                        new Quaternionf(),
                        new Vector3f(.85F, .85F, .85F),
                        new Quaternionf()));
            });
        } catch (RuntimeException visualFailure) {
            return null; // purely cosmetic: the flight works without its fireball model
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onNativeMarkerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)
                || !MagicProjectileMarker.isMarked(projectile)) return;
        event.setCancelled(true);
        Flight flight = flights.lookup(projectile.getUniqueId());
        if (flight == null) return;
        LivingEntity target = event.getEntity() instanceof LivingEntity living ? living : null;
        resolve(flight, target, projectile.getLocation(), projectile.getVelocity());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onModeledMarkerHit(ModeledEntityHitByProjectileEvent event) {
        if (!MagicProjectileMarker.isMarked(event.getProjectile())) return;
        event.setCancelled(true);
        Flight flight = flights.lookup(event.getProjectile().getUniqueId());
        if (flight == null) return;
        Entity underlying = event.getEntity().getUnderlyingEntity();
        LivingEntity target = underlying instanceof LivingEntity living ? living : null;
        resolve(flight, target, event.getProjectile().getLocation(), event.getProjectile().getVelocity());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMarkerHit(ProjectileHitEvent event) {
        if (!MagicProjectileMarker.isMarked(event.getEntity())) return;
        Flight flight = flights.lookup(event.getEntity().getUniqueId());
        if (flight == null) return;
        LivingEntity target = event.getHitEntity() instanceof LivingEntity living ? living : null;
        resolve(flight, target, event.getEntity().getLocation(), event.getEntity().getVelocity());
    }

    private Arrow spawnMarker(Player owner, Location start, Vector initialDirection) {
        World world = start.getWorld();
        if (world == null || !loaded(start)) return null;
        Arrow arrow = world.spawn(start, Arrow.class, marker -> {
            marker.setShooter(owner);
            marker.setGravity(false);
            marker.setSilent(true);
            marker.setPersistent(false);
            marker.setCritical(false);
            marker.setDamage(0D);
            marker.setKnockbackStrength(0);
            marker.setPierceLevel(0);
            marker.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            marker.setVisibleByDefault(false);
            MagicProjectileMarker.mark(marker);
            marker.setVelocity(initialDirection.clone().normalize().multiply(.05D));
        });
        return arrow;
    }

    private void tick() {
        currentTick++;
        for (Flight flight : flights.snapshot()) {
            if (!flight.valid()) {
                finish(flight);
                continue;
            }
            if (absorbed(flight, flight.previous, flight.marker.getLocation())) continue;
            flight.previous = flight.marker.getLocation().clone();
            flight.tick();
            if (flights.lookup(flight.marker.getUniqueId()) == flight)
                absorbed(flight, flight.marker.getLocation(), flight.marker.getLocation().add(flight.marker.getVelocity()));
        }
    }

    private void resolve(Flight flight, LivingEntity target, Location impact, Vector incoming) {
        if (absorbed(flight, flight.previous, impact)) return;
        if (!flights.claim(flight.marker.getUniqueId(), flight)) return;
        flight.cleanup();
        flight.marker.remove();
        impactHandler.onImpact(flight.cast, target, impact.clone(), incoming.clone());
    }

    private boolean absorbed(Flight flight, Location from, Location to) {
        if (com.magmaguy.freeminecraftmodels.api.magic.MagicProjectileTravelEvent.getHandlerList()
                .getRegisteredListeners().length == 0) return false;
        var query = new com.magmaguy.freeminecraftmodels.api.magic.MagicProjectileTravelEvent(
                flight.cast.owner(), flight.cast.definition().kind(), from, to);
        plugin.getServer().getPluginManager().callEvent(query);
        if (!query.isCancelled()) return false;
        finish(flight);
        return true;
    }

    private void finish(Flight flight) {
        if (!flights.claim(flight.marker.getUniqueId(), flight)) return;
        flight.cleanup();
        flight.marker.remove();
    }

    void cancelAll() {
        for (Flight flight : flights.snapshot()) finish(flight);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        if (tickTask != null) tickTask.cancel();
        tickTask = null;
        cancelAll();
    }

    private abstract class Flight {
        final Arrow marker;
        final MagicCast cast;
        final UUID worldId;
        Location previous;
        int elapsed;

        Flight(Arrow marker, MagicCast cast) {
            this.marker = marker;
            this.cast = cast;
            this.worldId = marker.getWorld().getUID();
            this.previous = marker.getLocation().clone();
        }

        boolean valid() {
            Player owner = cast.owner();
            return !closed && marker.isValid() && owner.isOnline() && owner.isValid() && !owner.isDead()
                    && owner.getWorld().getUID().equals(worldId) && loaded(marker.getLocation());
        }

        /** Releases any visual companion entities; called exactly once at flight end. */
        void cleanup() {
        }

        abstract void tick();
    }

    /** An unlocked wand bolt still uses the same physical impact and damage path. */
    private final class StraightWandFlight extends Flight {
        private final Location start;
        private final Vector direction;

        StraightWandFlight(Arrow marker, MagicCast cast, Location start, Vector direction) {
            super(marker, cast);
            this.start = start.clone();
            this.direction = direction.clone().normalize();
        }

        @Override
        void tick() {
            elapsed++;
            MagicWeaponTraits traits = cast.definition().traits();
            marker.setVelocity(direction.clone().multiply(traits.projectileSpeed()));
            Location location = marker.getLocation();
            marker.getWorld().spawnParticle(Particle.WITCH, location, 3, .06, .06, .06, .01);
            marker.getWorld().spawnParticle(Particle.END_ROD, location, 1, 0D, 0D, 0D, 0D);
            if (location.distanceSquared(start) >= traits.range() * traits.range()
                    || elapsed >= traits.travelTicks()) {
                marker.getWorld().spawnParticle(Particle.SMOKE, location, 5, .08, .08, .08, .01);
                finish(this);
            }
        }
    }

    private final class WandFlight extends Flight {
        private final LivingEntity target;
        private final Vector start;
        private final Vector initialEnd;
        private final Vector firstControl;
        private final Vector secondControl;

        WandFlight(
                Arrow marker,
                MagicCast cast,
                Location startLocation,
                LivingEntity target,
                int missileIndex,
                int missileCount) {
            super(marker, cast);
            this.target = target;
            this.start = startLocation.toVector();
            this.initialEnd = center(target).toVector();
            Vector forward = initialEnd.clone().subtract(start).normalize();
            Vector side = forward.clone().crossProduct(new Vector(0D, 1D, 0D));
            if (side.lengthSquared() < 1.0E-6D) side = new Vector(1D, 0D, 0D);
            else side.normalize();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double sign = random.nextBoolean() ? 1D : -1D;
            double pathSpread = arcSpreadOffset(
                    cast.definition().traits().spreadDegrees(),
                    start.distance(initialEnd),
                    missileIndex,
                    missileCount);
            firstControl = start.clone().add(initialEnd.clone().subtract(start).multiply(.32D))
                    .add(side.clone().multiply(
                            sign * random.nextDouble(.75D, 1.75D) + pathSpread))
                    .add(new Vector(0D, random.nextDouble(.8D, 1.9D), 0D));
            secondControl = start.clone().add(initialEnd.clone().subtract(start).multiply(.68D))
                    .add(side.multiply(
                            -sign * random.nextDouble(.15D, 1.05D) - pathSpread * .35D))
                    .add(new Vector(0D, random.nextDouble(.45D, 1.5D), 0D));
        }

        @Override
        boolean valid() {
            return super.valid() && validTarget(cast.owner(), target)
                    && target.getWorld().getUID().equals(worldId);
        }

        @Override
        void tick() {
            elapsed++;
            MagicWeaponTraits traits = cast.definition().traits();
            Location currentTarget = center(target);
            if (currentTarget.distanceSquared(start.toLocation(marker.getWorld()))
                    > Math.pow(traits.range() + 4D, 2D)) {
                finish(this);
                return;
            }
            double t = Math.min(1D, elapsed / (double) traits.travelTicks());
            Vector currentEnd = currentTarget.toVector();
            Vector shift = currentEnd.clone().subtract(initialEnd);
            Vector desired = cubic(
                    start,
                    firstControl.clone().add(shift.clone().multiply(.32D)),
                    secondControl.clone().add(shift.multiply(.68D)),
                    currentEnd,
                    t);
            Vector velocity = desired.subtract(marker.getLocation().toVector());
            if (velocity.length() > traits.projectileSpeed())
                velocity.normalize().multiply(traits.projectileSpeed());
            marker.setVelocity(velocity);
            marker.getWorld().spawnParticle(Particle.WITCH, marker.getLocation(), 3, .06, .06, .06, .01);
            marker.getWorld().spawnParticle(Particle.END_ROD, marker.getLocation(), 1, 0D, 0D, 0D, 0D);

            // Steering never awards a hit. The marker must physically collide, allowing
            // terrain and intervening entities to consume the bolt before its locked target.
            if (elapsed >= traits.travelTicks() + 5) {
                Location markerLocation = marker.getLocation();
                marker.getWorld().spawnParticle(Particle.SMOKE, markerLocation, 5, .08, .08, .08, .01);
                finish(this);
            }
        }
    }

    private final class StaffFlight extends Flight {
        private static final double SPIRAL_RADIUS = .5D;
        private static final double SPIRAL_RADIANS_PER_TICK = .45D;

        private final Location start;
        private final Vector direction;
        private final Vector side;
        private final Vector vertical;
        private final ItemDisplay fireballVisual;

        StaffFlight(Arrow marker, MagicCast cast, Location start, Vector direction) {
            super(marker, cast);
            this.start = start.clone();
            this.direction = direction.clone();
            Vector sideAxis = direction.clone().crossProduct(new Vector(0D, 1D, 0D));
            this.side = sideAxis.lengthSquared() < 1.0E-6D
                    ? new Vector(1D, 0D, 0D)
                    : sideAxis.normalize();
            this.vertical = direction.clone().crossProduct(this.side).normalize();
            this.fireballVisual = spawnFireballVisual(marker);
        }

        @Override
        void cleanup() {
            if (fireballVisual != null && fireballVisual.isValid()) fireballVisual.remove();
        }

        @Override
        void tick() {
            elapsed++;
            MagicWeaponTraits traits = cast.definition().traits();
            // The fireball corkscrews forward: the marker physically flies the helix, so the
            // collision path matches the visual. Half-block radius means a one-block full swing.
            double angle = elapsed * SPIRAL_RADIANS_PER_TICK;
            double radius = SPIRAL_RADIUS * Math.min(1D, elapsed / 6D);
            Vector desired = start.toVector()
                    .add(direction.clone().multiply(traits.projectileSpeed() * elapsed))
                    .add(side.clone().multiply(Math.cos(angle) * radius))
                    .add(vertical.clone().multiply(Math.sin(angle) * radius));
            marker.setVelocity(desired.subtract(marker.getLocation().toVector()));
            Location location = marker.getLocation();
            if (fireballVisual != null && fireballVisual.isValid())
                fireballVisual.teleport(location);
            marker.getWorld().spawnParticle(Particle.FLAME, location, 3, .12, .12, .12, .025);
            marker.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, location, 1, .07, .07, .07, .01);
            if (location.distanceSquared(start) >= traits.range() * traits.range()
                    || elapsed >= traits.travelTicks()) {
                resolve(this, null, location, direction);
            }
        }
    }

    static boolean validTarget(Player owner, LivingEntity target) {
        return target != null && target != owner && target.isValid() && !target.isDead()
                && target.getHealth() > 0D && !(target instanceof ArmorStand);
    }

    static Location center(LivingEntity entity) {
        return entity.getLocation().add(0D, Math.max(.35D, entity.getHeight() * .52D), 0D);
    }

    static boolean unobstructed(Location source, Location destination) {
        World world = source.getWorld();
        if (world == null || destination.getWorld() == null || !world.equals(destination.getWorld())) return false;
        Vector delta = destination.toVector().subtract(source.toVector());
        double distance = delta.length();
        if (distance < 1.0E-6D) return true;
        RayTraceResult hit = world.rayTraceBlocks(
                source, delta.normalize(), distance, FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitPosition() == null
                || hit.getHitPosition().distance(source.toVector()) >= distance - BLOCK_EPSILON;
    }

    private static boolean loaded(Location location) {
        World world = location.getWorld();
        return world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private static Vector cubic(Vector start, Vector first, Vector second, Vector end, double t) {
        double inverse = 1D - t;
        return start.clone().multiply(inverse * inverse * inverse)
                .add(first.clone().multiply(3D * inverse * inverse * t))
                .add(second.clone().multiply(3D * inverse * t * t))
                .add(end.clone().multiply(t * t * t));
    }

    static double arcSpreadOffset(
            double spreadDegrees,
            double distance,
            int missileIndex,
            int missileCount) {
        if (spreadDegrees <= 0D || distance <= 0D || missileCount <= 1) return 0D;
        double position = missileCount == 2
                ? (missileIndex == 0 ? -1D : 1D)
                : -1D + 2D * missileIndex / (missileCount - 1D);
        double clampedDegrees = Math.min(60D, spreadDegrees);
        return Math.tan(Math.toRadians(clampedDegrees)) * distance * position;
    }

    @FunctionalInterface
    interface ImpactHandler {
        void onImpact(MagicCast cast, LivingEntity directTarget, Location impact, Vector incoming);
    }

    private record AimCandidate(LivingEntity target, int tier, double score, double distance) {
    }
}
