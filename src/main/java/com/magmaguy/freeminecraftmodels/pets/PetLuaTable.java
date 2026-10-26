package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.OrientedBoundingBox;
import com.magmaguy.freeminecraftmodels.pets.interactions.CampfireIndex;
import com.magmaguy.freeminecraftmodels.pets.interactions.PetSky;
import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindPosition;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.shaded.luaj.vm2.LuaError;
import com.magmaguy.shaded.luaj.vm2.LuaString;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;
import com.magmaguy.shaded.luaj.vm2.Varargs;
import com.magmaguy.shaded.luaj.vm2.lib.ThreeArgFunction;
import com.magmaguy.shaded.luaj.vm2.lib.TwoArgFunction;
import com.magmaguy.shaded.luaj.vm2.lib.VarArgFunction;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The c.pet table a behavior module reads: the pet's state, read fresh on each access, and the Java helpers that
 * keep per-tick work fast. One table per pet; {@link #bind} points it at the current callback's Mind context first.
 * <p>
 * Modules get it read-only: assigning to c.pet or c.pet.species, rawset on them and setmetatable all raise a Lua
 * error, so no module can shadow a property or replace a method for the rest of its pet's life. c.pet never acts in
 * another world: a location it would return from another world than the body's reads as nil, and a location argument
 * naming another world counts as nothing there (nil, false or 0, and an infinite distance_to). Number arguments must
 * be finite; radii, depths, heights, multipliers, amplifiers, durations and lists are capped below, so the Java work
 * stays bounded, and no method loads a chunk. A Java failure inside a property or method is logged through the load's
 * {@link Warnings}, then reaches Lua as a short error that fails the callback. Server thread only.
 */
public final class PetLuaTable {
    /** Every name a module can read on c.pet. */
    public static final Set<String> PROPERTIES = Set.of("wild", "staying", "settled", "baby", "migrating", "model",
            "species", "position", "home", "anchor", "stay_anchor", "owner_location", "owner_distance", "night", "day",
            "raining", "speed", "route_cruise");
    /** Every method a module can call on c.pet. */
    public static final Set<String> METHODS = Set.of("steer_to", "stop", "hover_point", "ground_below", "ground_point",
            "water_point", "trail_point", "teleport_near_owner", "distance_to", "route_target", "find_perch",
            "nearest_campfire", "campfire_usable", "set_glow", "clear_glow", "scale_toward", "sting_contacts",
            "sting_enemies", "drizzle", "owner_effect", "play_animation");
    /** The fields of c.pet.species. */
    public static final Set<String> SPECIES_FIELDS = Set.of("id", "speed", "hover_height", "hover_variance",
            "roam_radius", "follow_distance", "leash_distance", "teleport_distance", "scale", "baby_scale");

    // Caps on what a module can ask for, each well above the shipped pet files' values: roam radius 5, hover depth
    // 12, campfire radius 12 kept 5 s, perch radius 2 at height 0.3, sting reach 0.5, drizzle depth 4, rain bloom x1.25,
    // effects of 60 and 300 ticks.
    private static final double MAX_SPOT_RADIUS = 64D;
    private static final int MAX_GROUND_DEPTH = 64;
    private static final double MAX_WATER_DEPTH = 16D;
    private static final double MAX_CAMPFIRE_RADIUS = 32D;
    // The campfire index is shared by every pet, so a long keep would hold back fresh fires for all of them.
    private static final int MIN_CAMPFIRE_KEEP_TICKS = 20, MAX_CAMPFIRE_KEEP_TICKS = 1200;
    private static final int MAX_PERCH_RADIUS = 4;
    private static final double MAX_PERCH_HEIGHT = 8D;
    private static final double MAX_STING_REACH = 4D;
    private static final int MAX_DRIZZLE_DEPTH = 8;
    // The scale attribute's own top, so the eased size stays finite.
    private static final double MIN_SCALE_MULTIPLIER = 0.1D, MAX_SCALE_MULTIPLIER = 16D;
    private static final int MAX_AMPLIFIER = 255;
    // Five minutes.
    private static final int MAX_EFFECT_TICKS = 6000;
    /** The most entries a list argument (campfire blocks, soul variants) is read for. */
    static final int MAX_LIST_ENTRIES = 64;
    // How the Mind actuator words a call from a callback that does not hold the control it needs.
    private static final String LEASE_FAILURE = "Mind behavior must declare and hold the ";
    private static final LuaString WORLD = LuaValue.valueOf("world"), X = LuaValue.valueOf("x"),
            Y = LuaValue.valueOf("y"), Z = LuaValue.valueOf("z");

    private final PetControl control;
    private final Warnings warnings;
    private final Fields fields;
    private final Set<String> registered = new HashSet<>();
    private MindContext context;
    private String module = "";
    private final WalkPath path = new WalkPath();
    private boolean raining;
    private long nextRainCheck = Long.MIN_VALUE;
    private double scale = Double.NaN;
    private DynamicEntity glowModel;
    private boolean glowAnimating;
    private final Map<UUID, Long> stingReadyAt = new HashMap<>();

    /** {@code warnings} belongs to the load that builds this pet, and is shared by every pet that load builds. */
    PetLuaTable(PetControl control, Warnings warnings) {
        this.control = control;
        this.warnings = warnings;
        PetSpecies pet = control.species();
        BiConsumer<String, Throwable> report = (call, failure) -> warnings.failure(pet.id(), module, call, failure);
        this.fields = new Fields("c.pet", report);
        Map<String, LuaValue> values = new LinkedHashMap<>();
        values.put("id", LuaValue.valueOf(pet.id()));
        values.put("speed", LuaValue.valueOf(pet.speed()));
        values.put("hover_height", LuaValue.valueOf(pet.hoverHeight()));
        values.put("hover_variance", LuaValue.valueOf(pet.hoverVariance()));
        values.put("roam_radius", LuaValue.valueOf(pet.roamRadius()));
        values.put("follow_distance", LuaValue.valueOf(pet.followDistance()));
        values.put("leash_distance", LuaValue.valueOf(pet.leashDistance()));
        values.put("teleport_distance", LuaValue.valueOf(pet.teleportDistance()));
        values.put("scale", LuaValue.valueOf(pet.scale()));
        values.put("baby_scale", LuaValue.valueOf(pet.babyScale()));
        LuaTable species = speciesTable(values);

        property("species", () -> species);
        property("wild", () -> LuaValue.valueOf(control.isWild()));
        property("staying", () -> LuaValue.valueOf(control.isStaying()));
        property("settled", () -> LuaValue.valueOf(PetMotion.settled(control, body())));
        property("baby", () -> LuaValue.valueOf(control.isBaby()));
        property("migrating", () -> LuaValue.valueOf(control.route() != null));
        property("model", () -> control.model() == null ? LuaValue.NIL : LuaValue.valueOf(control.model()));
        property("position", () -> LuaTableSupport.locationToTable(body().getLocation()));
        property("home", () -> location(control.home()));
        property("anchor", () -> location(anchor()));
        property("stay_anchor", () -> location(control.stayAnchor()));
        property("owner_location", () -> location(control.owner().map(Player::getLocation).orElse(null)));
        property("owner_distance", () -> LuaValue.valueOf(control.ownerDistance(body())));
        property("night", () -> LuaValue.valueOf(PetSky.isNight(body().getWorld())));
        property("day", () -> LuaValue.valueOf(PetSky.isDay(body().getWorld())));
        property("raining", () -> LuaValue.valueOf(raining()));
        property("speed", () -> LuaValue.valueOf(body().getVelocity().length()));
        property("route_cruise", () -> LuaValue.valueOf(control.route() == null ? 0D : control.route().cruise()));

        // steer_to(point, cruise): one tick toward the point at cruise blocks per tick (0 to 4; positive infinity means
        // 4 and negative infinity 0); false when the body cannot go there. Needs a behavior holding MOVE and LOOK.
        method("steer_to", args -> {
            Location target = at(args, 1);
            double cruise = cruise(args, 2);
            if (target == null) return LuaValue.FALSE;
            return LuaValue.valueOf(leased("c.pet:steer_to", "ai.controls.move and ai.controls.look",
                    () -> steerTo(target, cruise)));
        });
        // stop(): needs a behavior holding MOVE.
        method("stop", args -> {
            path.forget();
            leased("c.pet:stop", "ai.controls.move", () -> {
                context.actuator().stopMoving();
                return null;
            });
            return LuaValue.NIL;
        });
        // hover_point(center, radius): a point in the hover band within radius (up to 64), nil when unloaded.
        method("hover_point", args -> {
            Location center = at(args, 1);
            double radius = number(args, 2, 0D, MAX_SPOT_RADIUS);
            if (center == null) return LuaValue.NIL;
            Location point = PetMotion.hoverPoint(control, center, radius);
            return PetMotion.loaded(point) ? LuaTableSupport.locationToTable(point) : LuaValue.NIL;
        });
        // ground_below(point, depth): the ground's top within depth blocks down (1 to 64), nil when unloaded.
        method("ground_below", args -> {
            Location point = at(args, 1);
            int depth = whole(args, 2, 1, MAX_GROUND_DEPTH);
            if (point == null || !PetMotion.loaded(point)) return LuaValue.NIL;
            return LuaValue.valueOf(PetMotion.groundBelow(point, depth, PetMotion.floatsOnLiquid(control)));
        });
        // ground_point(center, radius): a standing spot within radius (up to 64), or nil.
        method("ground_point", args -> {
            Location center = at(args, 1);
            double radius = number(args, 2, 0D, MAX_SPOT_RADIUS);
            return center == null ? LuaValue.NIL : location(PetMotion.groundPoint(center, radius));
        });
        // water_point(center, radius, min_depth, max_depth): a swimming spot within radius (up to 64), between the
        // depths (0 to 16) under the surface, or nil.
        method("water_point", args -> {
            Location center = at(args, 1);
            double radius = number(args, 2, 0D, MAX_SPOT_RADIUS);
            double minDepth = number(args, 3, 0D, MAX_WATER_DEPTH), maxDepth = number(args, 4, 0D, MAX_WATER_DEPTH);
            return center == null ? LuaValue.NIL : location(PetMotion.waterPoint(center, radius, minDepth, maxDepth));
        });
        // trail_point(back, height): behind the owner, or nil when the owner is away or in another world.
        method("trail_point", args -> {
            double back = number(args, 1), height = number(args, 2);
            return location(control.owner().map(owner -> PetMotion.trailPoint(control, owner, back, height)).orElse(null));
        });
        // teleport_near_owner(radius): radius up to 64. It moves the body directly, so it needs no control.
        method("teleport_near_owner", args -> {
            double radius = number(args, 1, 0D, MAX_SPOT_RADIUS);
            path.forget();
            return LuaValue.valueOf(PetMotion.teleportNearOwner(control, body(), radius));
        });
        method("distance_to", args -> {
            Location point = at(args, 1);
            return LuaValue.valueOf(point == null ? Double.POSITIVE_INFINITY : body().getLocation().distance(point));
        });
        method("route_target", args -> location(control.route() == null ? null : control.route().target(control)));
        // find_perch(height, radius): height 0 to 8, radius 0 to 4.
        method("find_perch", args -> {
            double height = number(args, 1, 0D, MAX_PERCH_HEIGHT);
            return location(findPerch(height, whole(args, 2, 0, MAX_PERCH_RADIUS)));
        });
        // nearest_campfire(radius, keep_ticks, blocks, soul_variants, height): radius 1 to 32, keep_ticks 20 to 1200.
        method("nearest_campfire", args -> {
            double radius = number(args, 1, 1D, MAX_CAMPFIRE_RADIUS);
            int keepTicks = whole(args, 2, MIN_CAMPFIRE_KEEP_TICKS, MAX_CAMPFIRE_KEEP_TICKS);
            Campfire fire = campfire(args, 3);
            Block block = CampfireIndex.nearest(body().getLocation(), radius, keepTicks, fire::usable);
            return block == null ? LuaValue.NIL : LuaTableSupport.locationToTable(block.getLocation());
        });
        // campfire_usable(fire, blocks, soul_variants, height): false when the fire is unloaded or elsewhere.
        method("campfire_usable", args -> {
            Location at = at(args, 1);
            Campfire fire = campfire(args, 2);
            return LuaValue.valueOf(at != null && PetMotion.loaded(at) && fire.usable(at.getBlock()));
        });
        // set_glow(light, lantern_bone, animation): light 0 to 15.
        method("set_glow", args -> {
            setGlow(whole(args, 1, 0, 15), args.optjstring(2, ""), args.optjstring(3, ""));
            return LuaValue.NIL;
        });
        method("clear_glow", args -> {
            clearGlow();
            return LuaValue.NIL;
        });
        // scale_toward(target, span, ticks, elapsed): multipliers 0.1 to 16, ticks at least 2, elapsed at least 0.
        method("scale_toward", args -> {
            scaleToward(number(args, 1, MIN_SCALE_MULTIPLIER, MAX_SCALE_MULTIPLIER),
                    number(args, 2, MIN_SCALE_MULTIPLIER, MAX_SCALE_MULTIPLIER),
                    whole(args, 3, 2, Integer.MAX_VALUE), whole(args, 4, 0, Integer.MAX_VALUE));
            return LuaValue.NIL;
        });
        // sting_contacts(on, effect, duration, amplifier, damage, cooldown): takes the touches first, whatever follows.
        method("sting_contacts", args -> {
            List<Player> touched = control.drainContacts();
            boolean on = args.arg1().toboolean();
            Sting sting = sting(args, 2);
            if (!on) return LuaValue.valueOf(0);
            World world = body().getWorld();
            int stung = 0;
            for (Player player : touched) {
                GameMode mode = player.getGameMode();
                if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.isDead()
                        || player.getWorld() != world) continue;
                if (sting(player, sting, "c.pet:sting_contacts")) stung++;
            }
            return LuaValue.valueOf(stung);
        });
        // sting_enemies(reach, effect, duration, amplifier, damage, cooldown): reach 0 to 4.
        method("sting_enemies", args -> {
            double reach = number(args, 1, 0D, MAX_STING_REACH);
            Sting sting = sting(args, 2);
            PetManager manager = PetManager.get();
            if (manager == null) return LuaValue.valueOf(0);
            LivingEntity body = body();
            int stung = 0;
            for (Entity entity : body.getWorld().getNearbyEntities(body.getBoundingBox().expand(reach + 1.5D),
                    candidate -> candidate instanceof Enemy && candidate instanceof LivingEntity && !(candidate instanceof Player))) {
                LivingEntity victim = (LivingEntity) entity;
                if (victim.isDead() || manager.pet(victim) != null) continue;
                if (touches(victim, reach) && sting(victim, sting, "c.pet:sting_enemies")) stung++;
            }
            return LuaValue.valueOf(stung);
        });
        // drizzle(depth, chance): depth 1 to 8, chance 0 to 1.
        method("drizzle", args -> LuaValue.valueOf(drizzle(whole(args, 1, 1, MAX_DRIZZLE_DEPTH), number(args, 2, 0D, 1D))));
        // owner_effect(effect, duration, amplifier, range): duration 1 to 6000 ticks, amplifier 0 to 255; false when
        // the owner is away, elsewhere or out of range.
        method("owner_effect", args -> {
            String name = args.checkjstring(1);
            int duration = whole(args, 2, 1, MAX_EFFECT_TICKS), amplifier = whole(args, 3, 0, MAX_AMPLIFIER);
            double range = number(args, 4);
            Player owner = control.owner().orElse(null);
            LivingEntity body = body();
            if (owner == null || owner.getWorld() != body.getWorld()
                    || owner.getLocation().distance(body.getLocation()) > range) return LuaValue.FALSE;
            PotionEffectType effect = knownEffect(name, "c.pet:owner_effect");
            if (effect == null) return LuaValue.FALSE;
            owner.addPotionEffect(new PotionEffect(effect, duration, amplifier, true, false, true));
            return LuaValue.TRUE;
        });
        method("play_animation", args -> {
            String name = args.checkjstring(1);
            DynamicEntity modeled = control.modeled();
            return LuaValue.valueOf(modeled != null && !modeled.isRemoved() && modeled.hasAnimation(name)
                    && modeled.playAnimation(name, true, false));
        });

        Set<String> expected = new HashSet<>(PROPERTIES);
        expected.addAll(METHODS);
        if (!registered.equals(expected)) throw new IllegalStateException("PetLuaTable names drifted from its lists");
    }

    /** The read-only table modules see as c.pet. */
    LuaTable table() {
        return fields.view();
    }

    /** Points the table at a callback's context, and names the module it runs for in warnings. */
    void bind(MindContext context, String module) {
        this.context = context;
        this.module = module;
    }

    private LivingEntity body() {
        return context != null ? context.entity() : control.body();
    }

    private void property(String name, Supplier<LuaValue> supplier) {
        if (!PROPERTIES.contains(name) || !registered.add(name)) throw new IllegalStateException("c.pet." + name);
        fields.value(name, supplier);
    }

    private void method(String name, Function<Varargs, LuaValue> body) {
        if (!METHODS.contains(name) || !registered.add(name)) throw new IllegalStateException("c.pet:" + name);
        fields.method(name, body);
    }

    /** The read-only c.pet.species table, from exactly {@link #SPECIES_FIELDS}. */
    static LuaTable speciesTable(Map<String, LuaValue> values) {
        if (!values.keySet().equals(SPECIES_FIELDS)) throw new IllegalStateException("c.pet.species names drifted from its list");
        // Constants cannot fail, so nothing reaches the report.
        Fields species = new Fields("c.pet.species", (call, failure) -> {
        });
        values.forEach((name, value) -> species.value(name, () -> value));
        return species.view();
    }

    /** A number argument; NaN and the infinities are refused. */
    static double number(Varargs args, int index) {
        double value = args.checkdouble(index);
        if (!Double.isFinite(value)) LuaValue.argerror(index, "finite number expected, got " + value);
        return value;
    }

    static double number(Varargs args, int index, double min, double max) {
        return Math.max(min, Math.min(max, number(args, index)));
    }

    /**
     * A cruise argument, 0 to the flight cap. A module multiplying an owner's huge speed setting can overflow to an
     * infinity: positive infinity means the cap and negative infinity 0, as any other number past them does. NaN is
     * refused.
     */
    static double cruise(Varargs args, int index) {
        double value = args.checkdouble(index);
        if (value == Double.POSITIVE_INFINITY) return MindActuator.MAX_FLIGHT_SPEED;
        if (value == Double.NEGATIVE_INFINITY) return 0D;
        return number(args, index, 0D, MindActuator.MAX_FLIGHT_SPEED);
    }

    /** A whole number argument, truncated as Lua's integer arguments are, then clamped. */
    static int whole(Varargs args, int index, int min, int max) {
        return (int) Math.max(min, Math.min(max, number(args, index)));
    }

    /**
     * Runs an actuator call, turning the actuator's refusal of a callback that holds no lease (a sensor, can_start)
     * into a plain Lua error that says where the call belongs. Any other failure passes on.
     */
    static <T> T leased(String call, String controls, Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalStateException refused) {
            if (refused.getMessage() == null || !refused.getMessage().startsWith(LEASE_FAILURE)) throw refused;
            throw new LuaError(call + " needs a behavior that declares " + controls
                    + "; call it from a behavior's start, tick or stop, not from can_start or a sensor");
        }
    }

    private Location at(Varargs args, int index) {
        return at(args, index, body().getWorld());
    }

    /**
     * The location a table argument names, in {@code world}, or null when its world key names another world; a table
     * without one means {@code world}. Its coordinates must be finite numbers; yaw and pitch are ignored.
     */
    static Location at(Varargs args, int index, World world) {
        LuaTable table = args.checktable(index);
        double x = coordinate(table, X, index), y = coordinate(table, Y, index), z = coordinate(table, Z, index);
        LuaValue name = table.get(WORLD);
        if (!name.isnil() && !(name.isstring() && name.tojstring().equals(world.getName()))) return null;
        return new Location(world, x, y, z);
    }

    private static double coordinate(LuaTable table, LuaString axis, int index) {
        LuaValue value = table.get(axis);
        if (!value.isnumber() || !Double.isFinite(value.todouble()))
            LuaValue.argerror(index, "location " + axis + " must be a finite number, got " + value.tojstring());
        return value.todouble();
    }

    /** A location as a table, or nil when there is none or it is in another world than the body. */
    private LuaValue location(Location location) {
        return location == null || location.getWorld() != body().getWorld() ? LuaValue.NIL
                : LuaTableSupport.locationToTable(location);
    }

    /** Owned: the owner. Wild: its home, or where it is when it has none. */
    private Location anchor() {
        if (!control.isWild()) return control.owner().map(Player::getLocation).orElse(null);
        Location home = control.home();
        return home != null ? home : body().getLocation();
    }

    /** Whether rain falls on the pet, looked up at most every 10 ticks. */
    private boolean raining() {
        long now = PetManager.ticks();
        if (now >= nextRainCheck) {
            nextRainCheck = now + 10L;
            raining = PetSky.isRainingOn(body().getLocation());
        }
        return raining;
    }

    private boolean steerTo(Location target, double cruise) {
        if (control.species().locomotion() == MindBodyLocomotion.FLYING) return PetMotion.steerFlight(context, target, cruise);
        return path.steer(context, target, cruise);
    }

    /**
     * A walking or swimming body's path. A request is kept for a second while the target stays within a block, the
     * cruise is the same and the body's navigation still heads for that request: a released MOVE lease, an arrival or
     * another module's request ends that, and the next call asks again.
     */
    static final class WalkPath {
        private Location target;
        private double cruise;
        private long until;
        private boolean accepted;

        boolean steer(MindContext context, Location to, double cruise) {
            long now = context.gameTick();
            if (target != null && cruise == this.cruise && now < until && target.getWorld() == to.getWorld()
                    && target.distanceSquared(to) < 1D && heading(context)) return accepted;
            target = to.clone();
            this.cruise = cruise;
            until = now + 20L;
            accepted = context.entity().getLocation().distanceSquared(to) < 0.25D
                    || context.actuator().moveTo(to, Math.max(0.6D, cruise * 4D));
            return accepted;
        }

        void forget() {
            target = null;
        }

        private boolean heading(MindContext context) {
            MindPosition destination = context.perception().navigation().destination().orElse(null);
            return destination != null && destination.world().equals(target.getWorld().getName())
                    && destination.x() == target.getX() && destination.y() == target.getY()
                    && destination.z() == target.getZ();
        }
    }

    /** Leaves within the search radius under the hover band, nearest first, else the ground right below. */
    private Location findPerch(double height, int radius) {
        LivingEntity body = body();
        Location from = body.getLocation();
        World world = from.getWorld();
        PetSpecies species = control.species();
        int depth = (int) Math.ceil(species.hoverHeight() + species.hoverVariance()) + 3;
        // A model centred on its body hangs below the body's feet; lift the body so the model, not the body, rests
        // at the perch height.
        double sink = control.modeled() == null ? 0D : Math.max(0D, -control.modeled().getModelBottomHeight());
        Location leaves = null;
        double leavesScore = Double.MAX_VALUE;
        Location ground = null;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = from.getBlockX() + dx, z = from.getBlockZ() + dz;
                // Reading a column in an unloaded chunk would load it.
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = from.getBlockY(); y >= from.getBlockY() - depth && y > world.getMinHeight(); y--) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (block.isLiquid()) break;
                    // Air, grass and flowers: look under them.
                    if (type.isAir() || block.isPassable()) continue;
                    if (Tag.LEAVES.isTagged(type)) {
                        double score = dx * dx + dz * dz + (from.getY() - y) * 0.5D;
                        Location top = new Location(world, x + 0.5D, y + 1D + height + sink, z + 0.5D);
                        if (score < leavesScore && withinLeash(top)) {
                            leavesScore = score;
                            leaves = top;
                        }
                    } else if (dx == 0 && dz == 0 && type.isSolid()) {
                        ground = new Location(world, from.getX(), y + 1D + height + sink, from.getZ());
                    }
                    break;
                }
            }
        }
        Location chosen = leaves != null ? leaves : ground;
        if (chosen == null || !PetMotion.loaded(chosen) || !withinLeash(chosen)) return null;
        return chosen;
    }

    private boolean withinLeash(Location spot) {
        if (control.isWild()) return true;
        Player owner = control.owner().orElse(null);
        return owner != null && owner.getWorld() == spot.getWorld()
                && owner.getLocation().distance(spot) <= control.species().followDistance();
    }

    /** The fire kinds, soul variants and smoke height a campfire call gives, from argument {@code first}. */
    private Campfire campfire(Varargs args, int first) {
        return new Campfire(materials(args.arg(first)), texts(args.arg(first + 1)), number(args, first + 2));
    }

    private final class Campfire {
        private final Set<Material> blocks;
        private final Set<String> soulVariants;
        private final double height;

        private Campfire(Set<Material> blocks, Set<String> soulVariants, double height) {
            this.blocks = blocks;
            this.soulVariants = soulVariants;
            this.height = height;
        }

        /** A lit fire of a kind this pet's variant likes, close enough to its owner when it has one. */
        boolean usable(Block block) {
            Material type = block.getType();
            if (!blocks.contains(type)) return false;
            if (!soulVariants.isEmpty() && (type == Material.SOUL_CAMPFIRE) != soulVariants.contains(control.model())) return false;
            if (!(block.getBlockData() instanceof Lightable lightable) || !lightable.isLit()) return false;
            if (control.isWild()) return true;
            Player owner = control.owner().orElse(null);
            if (owner == null || owner.getWorld() != block.getWorld()) return false;
            Location above = block.getLocation().add(0.5D, height, 0.5D);
            return owner.getLocation().distance(above) <= Math.max(1D, control.species().followDistance() - 1D);
        }
    }

    /** The campfire kinds a list names; none it recognises means both, as in a pet file. */
    static Set<Material> materials(LuaValue list) {
        Set<Material> materials = new HashSet<>();
        for (String name : texts(list)) {
            Material material = Material.matchMaterial(name);
            if (material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE) materials.add(material);
        }
        if (materials.isEmpty()) {
            materials.add(Material.CAMPFIRE);
            materials.add(Material.SOUL_CAMPFIRE);
        }
        return materials;
    }

    /**
     * The texts of a list's first {@link #MAX_LIST_ENTRIES} entries, read raw so a length metamethod cannot stretch
     * the loop. Anything but a table is an empty list.
     */
    static Set<String> texts(LuaValue list) {
        Set<String> texts = new HashSet<>();
        if (!(list instanceof LuaTable table)) return texts;
        int length = Math.min(MAX_LIST_ENTRIES, table.rawlen());
        for (int index = 1; index <= length; index++) texts.add(table.rawget(index).tojstring());
        return texts;
    }

    private void setGlow(int light, String lanternBone, String animation) {
        DynamicEntity modeled = control.modeled();
        if (modeled == null || modeled.isRemoved()) return;
        if (modeled.getBrightnessOverrideBlockLight() != light) modeled.setBrightnessOverride(light, light);
        if (glowModel != modeled) {
            glowModel = modeled;
            glowAnimating = hasBone(modeled, lanternBone) && modeled.hasAnimation(animation)
                    && modeled.playAnimation(animation, true, true);
        }
    }

    private void clearGlow() {
        DynamicEntity modeled = control.modeled();
        if (modeled == null || modeled.isRemoved()) return;
        if (modeled.hasBrightnessOverride()) modeled.clearBrightnessOverride();
        if (glowModel == modeled) {
            if (glowAnimating) modeled.stopCurrentAnimations();
            glowModel = null;
            glowAnimating = false;
        }
    }

    private static boolean hasBone(DynamicEntity modeled, String name) {
        if (name == null || name.isBlank() || modeled.getSkeleton() == null) return false;
        for (Bone bone : modeled.getSkeleton().getBones())
            if (name.equalsIgnoreCase(bone.getBoneBlueprint().getOriginalBoneName())
                    || name.equalsIgnoreCase(bone.getBoneBlueprint().getBoneName())) return true;
        return false;
    }

    /** Eases the body's size toward target times its base size, crossing the span from 1 to spanMultiplier in ticks. */
    private void scaleToward(double targetMultiplier, double spanMultiplier, int ticks, int elapsed) {
        LivingEntity body = body();
        PetSpecies species = control.species();
        double base = species.scale() * (control.isBaby() ? species.babyScale() : 1D);
        double target = base * targetMultiplier;
        if (Double.isNaN(scale)) scale = base;
        if (scale == target) return;
        double step = Math.abs(base * (spanMultiplier - 1D)) * elapsed / ticks;
        scale = scale < target ? Math.min(target, scale + step) : Math.max(target, scale - step);
        AttributeInstance attribute = body.getAttribute(Attribute.SCALE);
        if (attribute != null) attribute.setBaseValue(scale);
    }

    /** Whether the victim's box, grown by the reach, meets the model's oriented hitbox, or else the body's box. */
    private boolean touches(LivingEntity victim, double reach) {
        BoundingBox victimBox = victim.getBoundingBox().expand(reach);
        DynamicEntity modeled = control.modeled();
        if (modeled != null && !modeled.isRemoved()) {
            OrientedBoundingBox hitbox = modeled.getHitboxComponent().getObbHitbox();
            if (hitbox != null) return hitbox.intersectsAABBCoherently(victimBox);
        }
        return body().getBoundingBox().overlaps(victimBox);
    }

    /** A sting's effect name, duration ticks, amplifier, damage and cooldown ticks. */
    private record Sting(String effect, int duration, int amplifier, double damage, int cooldown) {
    }

    /**
     * The sting arguments from {@code first}: effect, duration 1 to 6000 ticks, amplifier 0 to 255, damage at least 0
     * and cooldown at least 1 tick.
     */
    private static Sting sting(Varargs args, int first) {
        return new Sting(args.optjstring(first, ""), whole(args, first + 1, 1, MAX_EFFECT_TICKS),
                whole(args, first + 2, 0, MAX_AMPLIFIER), number(args, first + 3, 0D, Double.MAX_VALUE),
                whole(args, first + 4, 1, Integer.MAX_VALUE));
    }

    private boolean sting(LivingEntity victim, Sting sting, String call) {
        long now = PetManager.ticks();
        Long readyAt = stingReadyAt.get(victim.getUniqueId());
        if (readyAt != null && now < readyAt) return false;
        if (stingReadyAt.size() > 32) stingReadyAt.values().removeIf(at -> at <= now);
        stingReadyAt.put(victim.getUniqueId(), now + sting.cooldown());
        PotionEffectType effect = knownEffect(sting.effect(), call);
        if (effect != null)
            victim.addPotionEffect(new PotionEffect(effect, sting.duration(), sting.amplifier(), false, true, true));
        if (sting.damage() > 0D) victim.damage(sting.damage(), body());
        Location at = victim.getLocation().add(0D, victim.getHeight() * 0.5D, 0D);
        victim.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 8, 0.25D, 0.3D, 0.25D, 0.05D);
        victim.getWorld().playSound(at, Sound.ENTITY_BEE_STING, 0.6F, 1.6F);
        DynamicEntity modeled = control.modeled();
        // Babies have no attack animation.
        if (!control.isBaby() && modeled != null && !modeled.isRemoved() && modeled.hasAnimation("attack"))
            modeled.playAnimation("attack", true, false);
        return true;
    }

    /** The effect a module names; one that names none is warned about once per species and name per load. */
    private PotionEffectType knownEffect(String name, String call) {
        PotionEffectType effect = effect(name);
        if (effect == null && name != null && !name.isBlank() && !name.equalsIgnoreCase("NONE"))
            warnings.unknownEffect(control.species().id(), module, call, name);
        return effect;
    }

    @SuppressWarnings("deprecation")
    static PotionEffectType effect(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("NONE")) return null;
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        PotionEffectType effect = key == null ? null : Registry.EFFECT.get(key);
        // Older names such as SLOW still resolve through the legacy lookup.
        return effect != null ? effect : PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
    }

    /** Hovering still over a crop: water particles, and a chance it grows a stage. */
    private boolean drizzle(int depth, double chance) {
        LivingEntity body = body();
        // Following or drifting fast is not hovering.
        if (body.getVelocity().lengthSquared() > 0.01D) return false;
        Location at = body.getLocation();
        World world = at.getWorld();
        int x = at.getBlockX(), z = at.getBlockZ();
        for (int y = at.getBlockY(); y >= at.getBlockY() - depth && y > world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getBlockData() instanceof Ageable crop && isCrop(block.getType())) {
                world.spawnParticle(Particle.FALLING_WATER, at, 6, 0.25D, 0.05D, 0.25D, 0D);
                if (crop.getAge() < crop.getMaximumAge() && ThreadLocalRandom.current().nextDouble() < chance) grow(block, crop);
                return true;
            }
            if (!block.getType().isAir()) return false;
        }
        return false;
    }

    private static boolean isCrop(Material material) {
        return Tag.CROPS.isTagged(material) || material == Material.NETHER_WART || material == Material.SWEET_BERRY_BUSH;
    }

    private static void grow(Block block, Ageable crop) {
        Ageable grown = (Ageable) crop.clone();
        grown.setAge(crop.getAge() + 1);
        BlockState next = block.getState();
        next.setBlockData(grown);
        BlockGrowEvent event = new BlockGrowEvent(block, next);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        event.getNewState().update(true);
        block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5D, 0.5D, 0.5D), 4, 0.3D, 0.3D, 0.3D, 0D);
    }

    /**
     * What pet behavior modules get warned about, each said once per load however many pets hit it: a failing c.pet
     * property or method once per species and name, with its stack trace, and an unknown potion effect once per
     * species and effect. Whatever builds the pets makes one per load and hands it to every pet table of that load,
     * so a reload says everything again. Server thread only.
     */
    static final class Warnings {
        /** The most distinct warnings one load says; a module inventing names past that stays quiet. */
        private static final int MAX_SAID = 256;
        private final BiConsumer<String, Throwable> sink;
        private final Set<String> said = new HashSet<>();

        /** Warnings to the server log, a failure's with its stack trace. */
        Warnings() {
            this((message, failure) -> {
                Logger.warn(message);
                if (failure != null) failure.printStackTrace();
            });
        }

        /** {@code sink} gets each warning's text and, for a failure, its cause; otherwise null. */
        Warnings(BiConsumer<String, Throwable> sink) {
            this.sink = sink;
        }

        /** A c.pet call failed in Java, inside the module with this id. */
        void failure(String species, String module, String call, Throwable failure) {
            say("failure\n" + species + "\n" + call, "The " + species + " pet behavior " + module + " called " + call
                    + ", which failed: " + failure, failure);
        }

        /** A c.pet call was given a potion effect name that names none. */
        void unknownEffect(String species, String module, String call, String effect) {
            say("effect\n" + species + "\n" + effect.toLowerCase(Locale.ROOT), "The " + species + " pet behavior "
                    + module + " gave " + call + " the potion effect " + effect + ", which does not exist; it applies none.",
                    null);
        }

        private void say(String key, String message, Throwable failure) {
            if (said.size() >= MAX_SAID || !said.add(key)) return;
            sink.accept(message, failure);
        }
    }

    /**
     * A read-only Lua table of named fields. Every read runs the field's supplier, so a value is fresh on each access;
     * a method is a field holding a function, called with a dot or with a colon on this table. Assigning to the
     * table, rawset on it and setmetatable on it raise a Lua error naming the field. A Java failure inside a field or
     * method, linkage errors included, goes to the report with the field's name and reaches Lua as a short error; Lua
     * errors, such as bad arguments, pass through unreported.
     */
    static final class Fields {
        private record Field(String label, Supplier<LuaValue> supplier) {
        }

        private final String name;
        private final BiConsumer<String, Throwable> report;
        private final Map<LuaValue, Field> fields = new HashMap<>();
        private final LuaTable view = new Sealed(this);

        /** {@code name} is how Lua reaches the table, such as c.pet; {@code report} gets each failing name and failure. */
        Fields(String name, BiConsumer<String, Throwable> report) {
            this.name = name;
            this.report = report;
            LuaTable metatable = new LuaTable();
            metatable.rawset(LuaValue.INDEX, new TwoArgFunction() {
                @Override
                public LuaValue call(LuaValue table, LuaValue key) {
                    Field field = fields.get(key);
                    return field == null ? LuaValue.NIL : guarded(field.label(), field.supplier());
                }
            });
            metatable.rawset(LuaValue.NEWINDEX, new ThreeArgFunction() {
                @Override
                public LuaValue call(LuaValue table, LuaValue key, LuaValue value) {
                    throw refused(key);
                }
            });
            // getmetatable reads false, and setmetatable refuses to replace it.
            metatable.rawset(LuaValue.METATABLE, LuaValue.FALSE);
            view.setmetatable(metatable);
        }

        /** The table Lua sees. */
        LuaTable view() {
            return view;
        }

        /** A field whose value the supplier gives on every read. */
        void value(String field, Supplier<LuaValue> supplier) {
            add(field, new Field(name + "." + field, supplier));
        }

        /** A method; body gets the arguments after the table when it is called with a colon. */
        void method(String field, Function<Varargs, LuaValue> body) {
            String label = name + ":" + field;
            LuaValue function = new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs args) {
                    Varargs own = LuaTableSupport.stripMethodSelf(args, view);
                    return guarded(label, () -> body.apply(own));
                }
            };
            add(field, new Field(label, () -> function));
        }

        private void add(String field, Field entry) {
            if (fields.putIfAbsent(LuaValue.valueOf(field), entry) != null)
                throw new IllegalStateException(name + " has two fields named " + field);
        }

        // LuaJ and the Mind scheduler catch only exceptions; these bodies used to run where every Throwable was caught.
        private LuaValue guarded(String label, Supplier<LuaValue> body) {
            try {
                LuaValue value = body.get();
                return value == null ? LuaValue.NIL : value;
            } catch (LuaError error) {
                throw error;
            } catch (RuntimeException | LinkageError failure) {
                report.accept(label, failure);
                throw new LuaError(label + " failed; the server log has the cause");
            }
        }

        private LuaError refused(LuaValue key) {
            return new LuaError(name + "." + key.tojstring() + " cannot be set; store per-pet values in c.memory");
        }
    }

    /** A table that holds nothing: Lua assignment reaches its __newindex, and rawset and table.insert land here. */
    private static final class Sealed extends LuaTable {
        private final Fields fields;

        private Sealed(Fields fields) {
            this.fields = fields;
        }

        @Override
        public void rawset(int key, LuaValue value) {
            throw fields.refused(LuaValue.valueOf(key));
        }

        @Override
        public void rawset(LuaValue key, LuaValue value) {
            throw fields.refused(key);
        }
    }
}
