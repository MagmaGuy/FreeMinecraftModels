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
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
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
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The c.pet table a behavior module reads: the pet's state, and the Java helpers that keep per-tick work fast. One
 * table per pet; {@link #bind} points it at the current callback's Mind context first.
 * <p>
 * Its properties are a snapshot per pet tick: each is read on its first use in a tick and kept
 * through every callback of that tick. A new pet tick, game tick or body reads them afresh, and so does every read after
 * {@code c.pet:teleport_near_owner} or {@code c.pet:stop}. Nothing else does: after {@code c.entity:teleport},
 * {@code set_velocity} or {@code push}, or the stop MagmaCore makes when a behavior gives up its controls, the
 * properties show the change on the next tick. {@code speed} is the exception, read fresh every time, since that stop
 * changes it. A location property gives a fresh table on every read, so a module may change the one it gets.
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
    public static final Set<String> PROPERTIES = Set.of("wild", "staying", "settled", "baby", "model",
            "species", "position", "home", "anchor", "stay_anchor", "owner_location", "owner_distance", "follow_range",
            "roam_range", "night", "day", "raining", "snowing", "speed", "threat", "attacker", "hurt_count",
            "march_requested", "marching", "stuck_ticks");
    /** How far from its owner an owned pet looks for someone to defend them against. */
    static final double THREAT_RANGE = 16D;
    /** How long a wild pet keeps the player who hurt it in mind: 30 seconds. */
    static final long ATTACKER_TICKS = 20L * 30L;
    /** Every method a module can call on c.pet. */
    public static final Set<String> METHODS = Set.of("steer_to", "stop", "hover_point", "rest_point", "ground_below",
            "ground_point", "water_point", "trail_point", "teleport_near_owner", "distance_to", "find_perch", "nearest_campfire", "campfire_usable", "set_glow", "clear_glow", "scale_toward",
            "sting_contacts", "sting_enemies", "strike_attacker", "touching_attacker", "drizzle", "owner_effect", "play_animation",
            "kin_center", "march_call", "march_spot", "gift", "nearest_item", "eat_item", "slide_point",
            "set_walk_animation", "steer_onto", "splash", "set_bone_hidden");
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
    // A sound carries 16 blocks per unit of volume, so 4 reaches 64, the farthest a pet model is drawn. Clients play
    // pitches from 0.5 to 2 only.
    private static final double MAX_VOLUME = 4D, MIN_PITCH = 0.5D, MAX_PITCH = 2D;
    /**
     * How far past an attacker's box a defending pet that stopped beside it may drift before it closes in again: the
     * sting module's own reach, so a pet that holds still keeps stinging. See {@link #approach}.
     */
    static final double BESIDE_REACH = 0.5D;
    /** The sting effect name that freezes its victim, as powder snow does, instead of giving a potion effect. */
    static final String FREEZE = "FREEZE";
    private static final int MAX_DRIZZLE_DEPTH = 8;
    // A huddle or a march counts kin this far off at most; a gift, an eaten item and a slide stay within these.
    private static final double MAX_KIN_RADIUS = 32D, MAX_GIFT_RANGE = 8D, MAX_ITEM_RADIUS = 16D, MAX_SLIDE_LENGTH = 24D;
    // A march lasts 10 seconds to half an hour, its marchers 0.5 to 8 blocks apart.
    private static final double MIN_MARCH_SECONDS = 10D, MAX_MARCH_SECONDS = 1800D, MIN_SPACING = 0.5D, MAX_SPACING = 8D;
    // Half hearts one eaten item heals at most: a full health bar of 20 twice over.
    private static final double MAX_HEAL = 40D;
    /** A slide is traced in steps this long, so it never skips a block. */
    private static final double SLIDE_STEP = 0.5D;
    /** The pickup delay vanilla gives an item nobody may pick up; display items from plugins use it or more. */
    private static final int NEVER_PICKED_UP = 32767;
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
    private final Ticks ticks = new Ticks();
    private MindContext context;
    private String module = "";
    private final LocationTables locations = new LocationTables();
    private final WalkPath path = new WalkPath();
    // The body's location this tick, shared by c.pet.position and distance_to.
    private final Supplier<Location> position;
    private boolean raining, snowing;
    private long nextRainCheck = Long.MIN_VALUE, nextSnowCheck = Long.MIN_VALUE;
    private double scale = Double.NaN;
    private DynamicEntity glowModel;
    private boolean glowAnimating;
    private final Map<UUID, Long> stingReadyAt = new HashMap<>();
    // The attacker c.pet.threat last stood for, and the point it gave for it: a steer at that point is a steer at them.
    private LivingEntity threatEntity;
    private Location threatPoint;
    // The attacker this pet stopped beside, holding still, or null while it closes in or does something else.
    private UUID besideThreat;
    // The player c.pet.attacker last gave, and the point it gave for them: their middle at that tick.
    private Player attackerEntity;
    private Location attackerPoint;
    // trail_point's and march_spot's answers this tick (see Ticks#stamp), so a running behavior's can_continue and
    // tick, which both ask, work each out once; trail_point's for the arguments it was asked with.
    private long trailStamp = Ticks.UNBOUND, marchStamp = Ticks.UNBOUND;
    private double trailBack, trailHeight;
    private Location trail, march;
    // A flying body's stuck count; MagmaCore's navigation tracker keeps a walking or swimming body's.
    private final FlightTrack flight = new FlightTrack();
    // The item, block and campfire kinds the lists a module passes name, kept while a list's entries stay the same.
    private final MaterialLists itemLists = new MaterialLists(), blockLists = new MaterialLists(),
            campfireLists = new MaterialLists();
    // The last campfire call's answer and the soul variant entries it was made from (see #campfire).
    private Campfire campfire;
    private LuaValue[] campfireSoul;

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
        property("wild", ticks.perTick(() -> LuaValue.valueOf(control.isWild())));
        property("staying", ticks.perTick(() -> LuaValue.valueOf(control.isStaying())));
        property("settled", ticks.perTick(() -> LuaValue.valueOf(PetMotion.settled(control, body()))));
        property("baby", ticks.perTick(() -> LuaValue.valueOf(control.isBaby())));
        property("model", ticks.perTick(() ->
                control.model() == null ? LuaValue.NIL : LuaValue.valueOf(control.model())));
        // Locations are kept as Java locations, and each read makes its own table from one.
        position = ticks.perTick(() -> body().getLocation());
        Supplier<Location> home = ticks.perTick(control::home), anchor = ticks.perTick(this::anchor),
                stayAnchor = ticks.perTick(control::stayAnchor),
                ownerLocation = ticks.perTick(() -> control.owner().map(Player::getLocation).orElse(null));
        property("position", () -> locations.table(position.get()));
        property("home", () -> location(home.get()));
        property("anchor", () -> location(anchor.get()));
        property("stay_anchor", () -> location(stayAnchor.get()));
        property("owner_location", () -> location(ownerLocation.get()));
        property("owner_distance", ticks.perTick(() -> LuaValue.valueOf(control.ownerDistance(body()))));
        // How far from its owner the pet ranges before following: species.follow_distance while the owner is on the
        // move, the pet file's follow.idleDistance while they stand about. roam_range is how far from its anchor a
        // drift, walk or swim may end: the roam radius when wild, and within that range around the owner.
        property("follow_range", ticks.perTick(() -> LuaValue.valueOf(control.followRange())));
        property("roam_range", ticks.perTick(() -> LuaValue.valueOf(control.roamRange())));
        property("night", ticks.perTick(() -> LuaValue.valueOf(PetSky.isNight(body().getWorld()))));
        property("day", ticks.perTick(() -> LuaValue.valueOf(PetSky.isDay(body().getWorld()))));
        property("raining", ticks.perTick(() -> LuaValue.valueOf(raining())));
        property("snowing", ticks.perTick(() -> LuaValue.valueOf(snowing())));
        // Not kept: the stop MagmaCore makes when a behavior gives up its controls changes it within the tick.
        property("speed", () -> LuaValue.valueOf(body().getVelocity().length()));
        // The middle of the nearest attacker its owner's pets defend against within THREAT_RANGE of the owner: a
        // player or one of their pets, a mob or a wild pet that hurt the owner or their pets lately. nil for a wild
        // pet, or when nobody did.
        Supplier<Location> threat = ticks.perTick(this::threat);
        property("threat", () -> location(threat.get()));
        // The middle of the player who last hurt this wild pet, for 30 seconds after the hit while they are alive, can
        // be hurt and are in its world; nil otherwise. hurt_count is how many player hits it has taken, so a module can
        // answer each one once.
        Supplier<Location> attacker = ticks.perTick(this::attacker);
        property("attacker", () -> location(attacker.get()));
        property("hurt_count", ticks.perTick(() -> LuaValue.valueOf(control.hurtCount())));
        // Whether an admin's /fmm petadmin march asked this pet to call a march, and the request still waits: a
        // module may skip its dice and its time of day for it.
        property("march_requested", ticks.perTick(() ->
                LuaValue.valueOf(PetMarches.requested(body(), PetManager.ticks()))));
        // Whether this pet is in a march under way. Unlike march_spot it does not keep the pet's place in line, so a
        // module asks it to learn whether the pet marches, and march_spot only while it walks the march.
        property("marching", ticks.perTick(() -> LuaValue.valueOf(PetMarches.marching(body()))));
        // How many ticks in a row the pet has steered somewhere without getting anywhere: its body has not moved 0.05
        // blocks from where it last did. 0 while it steers nowhere, once it comes within half a block of the point it
        // steers at, and after c.pet:stop; a new steer_to point keeps the count. A walking or swimming body's count is
        // MagmaCore's navigation tracker's, from each path request; a flying body's is kept the same way from its
        // steer_to and steer_onto calls, and ends when a tick passes without one or another module steers.
        property("stuck_ticks", ticks.perTick(() -> LuaValue.valueOf(stuckTicks())));

        // steer_to(point, cruise): one tick toward the point; false when the body cannot go there. cruise is 0 to 4
        // (positive infinity means 4 and negative infinity 0). A flying body flies at cruise blocks per tick. A walking
        // or swimming body paths there natively, at 4 x cruise times its body type's movement speed, never under a
        // quarter of it; a cruise of 0 stops it and reads false. The pet's speed stat multiplies the cruise. Steered at
        // c.pet.threat, it stops beside the attacker instead of walking into them (see approach).
        // Needs a behavior holding MOVE and LOOK.
        method("steer_to", args -> {
            Location target = at(args, 1);
            double cruise = cruise(args, 2);
            if (target == null) return LuaValue.FALSE;
            return LuaValue.valueOf(leased("c.pet:steer_to", "ai.controls.move and ai.controls.look",
                    () -> approach(target, cruise)));
        });
        // steer_onto(point, cruise): as steer_to, but it ends on the point rather than about a block short of it, for
        // a pet that must reach something small, such as an item, or stand on an exact spot. Within 2 blocks of the
        // point, on its level with firm ground all the way, a walking body walks straight at it, easing off over the
        // last block, and stands still on it. Needs a behavior holding MOVE and LOOK.
        method("steer_onto", args -> {
            Location target = at(args, 1);
            double cruise = cruise(args, 2);
            if (target == null) return LuaValue.FALSE;
            return LuaValue.valueOf(leased("c.pet:steer_onto", "ai.controls.move and ai.controls.look",
                    () -> steerOnto(target, cruise)));
        });
        // stop(): needs a behavior holding MOVE.
        method("stop", args -> {
            path.forget();
            leased("c.pet:stop", "ai.controls.move", () -> {
                context.actuator().stopMoving();
                return null;
            });
            flight.reset();
            ticks.moved();
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
        // ground_below(point, depth): the ground's top within depth blocks down (1 to 64), nil when unloaded: the
        // highest ground under a body as wide as the pet's standing at the point, so a pet resting on the edge of a
        // block reads that block, not the floor past it.
        method("ground_below", args -> {
            Location point = at(args, 1);
            int depth = whole(args, 2, 1, MAX_GROUND_DEPTH);
            if (point == null || !PetMotion.loaded(point)) return LuaValue.NIL;
            return LuaValue.valueOf(PetMotion.groundBelow(point, depth, PetMotion.floatsOnLiquid(control),
                    PetMotion.halfWidth(control)));
        });
        // rest_point(center, radius): a spot to rest within radius (up to 64) in the body's own medium: in its hover
        // band when it flies, on standing ground when it walks, in water when it swims, on ground or else in water when
        // it is amphibious. nil when nothing fits there, or it is unloaded.
        method("rest_point", args -> {
            Location center = at(args, 1);
            double radius = number(args, 2, 0D, MAX_SPOT_RADIUS);
            if (center == null) return LuaValue.NIL;
            Location point = PetMotion.restPoint(control, center, radius);
            return point != null && PetMotion.loaded(point) ? location(point) : LuaValue.NIL;
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
        // trail_point(back, height): behind the owner, or nil when the owner is away or in another world. Worked out
        // once per tick for the same arguments.
        method("trail_point", args -> {
            double back = number(args, 1), height = number(args, 2);
            long stamp = ticks.stamp();
            if (stamp == Ticks.UNBOUND || stamp != trailStamp || back != trailBack || height != trailHeight) {
                trail = control.owner().map(owner -> PetMotion.trailPoint(control, owner, back, height)).orElse(null);
                trailStamp = stamp;
                trailBack = back;
                trailHeight = height;
            }
            return location(trail);
        });
        // teleport_near_owner(radius): radius up to 64; false when nothing fits beside the owner or the spot is not at
        // least a block nearer them than the body is. A walker's or swimmer's spot is at least a block across from the
        // owner, never on their own block; a flyer's is a hover point. It moves the body directly, so it needs no
        // control.
        method("teleport_near_owner", args -> {
            double radius = number(args, 1, 0D, MAX_SPOT_RADIUS);
            path.forget();
            try {
                return LuaValue.valueOf(PetMotion.teleportNearOwner(control, body(), radius));
            } finally {
                ticks.moved();
            }
        });
        method("distance_to", args -> {
            Location point = at(args, 1);
            return LuaValue.valueOf(point == null ? Double.POSITIVE_INFINITY : position.get().distance(point));
        });
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
                if (sting(player, sting, null, "c.pet:sting_contacts")) stung++;
            }
            return LuaValue.valueOf(stung);
        });
        // sting_enemies(reach, effect, duration, amplifier, damage, cooldown): reach 0 to 4. Hostile mobs, and the
        // attackers its owner's pets defend against (see threat): players and their pets, mobs and wild pets.
        method("sting_enemies", args -> {
            double reach = number(args, 1, 0D, MAX_STING_REACH);
            Sting sting = sting(args, 2);
            PetManager manager = PetManager.get();
            if (manager == null) return LuaValue.valueOf(0);
            LivingEntity body = body();
            Set<UUID> aggressors = manager.aggressors(control.ownerId());
            int stung = 0;
            for (Entity entity : body.getWorld().getNearbyEntities(body.getBoundingBox().expand(reach + 1.5D),
                    candidate -> candidate instanceof LivingEntity && (candidate instanceof Enemy && !(candidate instanceof Player)
                            || !aggressors.isEmpty() && defendsAgainst(manager, aggressors, candidate)))) {
                LivingEntity victim = (LivingEntity) entity;
                if (victim.isDead()) continue;
                if (manager.pet(victim) != null && !defendsAgainst(manager, aggressors, victim)) continue;
                if (touches(victim, reach) && sting(victim, sting, null, "c.pet:sting_enemies")) stung++;
            }
            return LuaValue.valueOf(stung);
        });
        // strike_attacker(reach, effect, duration, amplifier, damage, cooldown, sound, volume, pitch, particle,
        // animation): stings the player who last hurt this wild pet (see attacker) once the model's touch reaches
        // them, as sting_enemies reaches a mob, playing sound (a sound key, a resource pack's own included) at volume
        // 0 to 4 and pitch 0.5 to 2 and showing particle instead of the sting's buzz and sparks; '' or NONE plays or
        // shows none. animation is the model animation it plays as it strikes, attack when left out, as every sting
        // plays; '' or NONE plays none, and a model without that animation plays none. false for an owned pet,
        // without an attacker, out of reach or within the cooldown.
        method("strike_attacker", args -> {
            double reach = number(args, 1, 0D, MAX_STING_REACH);
            Sting sting = sting(args, 2);
            String sound = args.optjstring(7, "");
            float volume = (float) number(args, 8, 0D, MAX_VOLUME), pitch = (float) number(args, 9, MIN_PITCH, MAX_PITCH);
            Look look = new Look(named(sound) ? sound : null, volume, pitch,
                    particle(args.optjstring(10, ""), "c.pet:strike_attacker"),
                    strikeAnimation(args.optjstring(11, STING_ANIMATION)));
            Player player = attackerPlayer();
            return LuaValue.valueOf(player != null && touches(player, reach)
                    && sting(player, sting, look, "c.pet:strike_attacker"));
        });
        // touching_attacker(): whether a wild pet's model, or else its body, touches the player c.pet.attacker gives;
        // the touch that sting_contacts and strike_attacker(0, ...) act on. false for an owned pet or without one.
        method("touching_attacker", args -> {
            Player player = attackerPlayer();
            return LuaValue.valueOf(player != null && touches(player, 0D));
        });
        // drizzle(depth, chance): depth 1 to 8, chance 0 to 1.
        method("drizzle", args -> LuaValue.valueOf(drizzle(whole(args, 1, 1, MAX_DRIZZLE_DEPTH), number(args, 2, 0D, 1D))));
        // owner_effect(effect, duration, amplifier, range): duration 1 to 6000 ticks, amplifier 0 to 255; false when
        // the owner is away, elsewhere or out of range. An effect the owner already has that is at least as strong and
        // lasts at least as long is left alone, so a drunk potion keeps its swirls and no effect event fires.
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
            PotionEffect current = owner.getPotionEffect(effect);
            if (current != null && covers(current.getAmplifier(), current.isInfinite(), current.getDuration(),
                    amplifier, duration)) return LuaValue.TRUE;
            owner.addPotionEffect(new PotionEffect(effect, duration, amplifier, true, false, true));
            return LuaValue.TRUE;
        });
        method("play_animation", args -> {
            String name = args.checkjstring(1);
            DynamicEntity modeled = control.modeled();
            return LuaValue.valueOf(modeled != null && !modeled.isRemoved() && modeled.hasAnimation(name)
                    && modeled.playAnimation(name, true, false));
        });
        // kin_center(radius, min): the middle of this pet and its kin within radius (up to 32), by their feet: pets of
        // its species, wild like it or of its owner, and not ridden. nil when there are fewer than min of
        // them (1 to 64), this pet included.
        method("kin_center", args -> {
            double radius = number(args, 1, 0D, MAX_KIN_RADIUS);
            return location(kinCenter(radius, whole(args, 2, 1, MAX_LIST_ENTRIES)));
        });
        // march_call(radius, min_group, chance, min_seconds, max_seconds, spacing, sound, volume, pitch): calls a
        // march with this pet at its head. Its kin within radius (up to 32) that are free, settled and in no march,
        // with itself, must number min_group (2 to 64); a kin is not free while something that comes before its own
        // behavior with this module's id holds its body; chance (0 to 1) is shared out among them, so a group calls as
        // often whatever its size, and an admin's request (march_requested) skips it. The march sets off along the
        // heading whose straight run of standing ground goes farthest, which must reach 6 blocks, and lasts min_seconds
        // to max_seconds (10 to 1800). Its kin, nearest first, follow in single file spacing blocks apart (0.5 to 8).
        // sound (a sound key, or NONE) plays twice from the caller at volume 0 to 4 and pitch 0.5 to 2. false when it
        // called none.
        method("march_call", args -> {
            double radius = number(args, 1, 0D, MAX_KIN_RADIUS);
            int minGroup = whole(args, 2, 2, PetMarches.MAX_MARCHERS);
            double chance = number(args, 3, 0D, 1D);
            double minSeconds = number(args, 4, MIN_MARCH_SECONDS, MAX_MARCH_SECONDS);
            double maxSeconds = number(args, 5, minSeconds, MAX_MARCH_SECONDS);
            double spacing = number(args, 6, MIN_SPACING, MAX_SPACING);
            String sound = args.optjstring(7, "");
            float volume = (float) number(args, 8, 0D, MAX_VOLUME), pitch = (float) number(args, 9, MIN_PITCH, MAX_PITCH);
            return LuaValue.valueOf(PetMarches.call(control, body(), module, radius, minGroup, chance, minSeconds,
                    maxSeconds, spacing, named(sound) ? sound.trim() : null, volume, pitch, PetManager.ticks()));
        });
        // march_spot(): where this marcher walks to now, which also keeps its place in line for 10 more ticks: the
        // leader's next step along its straight leg, its own spot while the line forms behind it, or a follower's
        // place on the leader's footprints. A follower back after being away goes to the back of the line. nil when it
        // is in no march, or the march ended: back where it was called, too few left, or out of time.
        // Worked out once per tick: a second ask in the same tick would find the same spot.
        method("march_spot", args -> {
            long stamp = ticks.stamp();
            if (stamp == Ticks.UNBOUND || stamp != marchStamp) {
                march = PetMarches.spot(body(), PetManager.ticks());
                marchStamp = stamp;
            }
            return location(march);
        });
        // gift(item, amount, range, sound, volume, pitch): an owned pet drops amount (1 to a stack) of item in front of
        // it, toward its owner, for the owner alone to pick up, playing sound (a sound key, or NONE) at volume 0 to 4
        // and pitch 0.5 to 2. false for a wild pet, an item that names nothing, or an owner away or past range (0 to 8).
        method("gift", args -> {
            String name = args.checkjstring(1);
            int amount = whole(args, 2, 1, 64);
            double range = number(args, 3, 0D, MAX_GIFT_RANGE);
            String sound = args.optjstring(4, "");
            float volume = (float) number(args, 5, 0D, MAX_VOLUME), pitch = (float) number(args, 6, MIN_PITCH, MAX_PITCH);
            Player owner = control.owner().orElse(null);
            LivingEntity body = body();
            if (control.isWild() || owner == null || owner.getWorld() != body.getWorld()
                    || owner.getLocation().distance(body.getLocation()) > range) return LuaValue.FALSE;
            Material material = item(name, "c.pet:gift");
            if (material == null) return LuaValue.FALSE;
            gift(body, owner, new ItemStack(material, Math.min(amount, material.getMaxStackSize())), sound, volume, pitch);
            return LuaValue.TRUE;
        });
        // nearest_item(radius, items): the nearest dropped stack of one of the items (names) within radius (up to 16)
        // that anyone may pick up and that is plain, without a name, lore or other data; an owned pet's must lie within
        // its follow range of its owner. nil when there is none.
        method("nearest_item", args -> {
            double radius = number(args, 1, 0D, MAX_ITEM_RADIUS);
            Item item = nearestItem(radius, items(args.arg(2), "c.pet:nearest_item"));
            return item == null ? LuaValue.NIL : location(item.getLocation());
        });
        // eat_item(reach, items, heal, sound, volume, pitch): eats one item off the nearest such stack its model touches
        // within reach (0 to 4), healing heal half hearts (0 to 40) and playing sound at volume 0 to 4 and pitch 0.5 to
        // 2. A plugin cancelling the pickup event keeps the item. false when nothing was eaten.
        method("eat_item", args -> {
            double reach = number(args, 1, 0D, MAX_STING_REACH);
            Set<Material> kinds = items(args.arg(2), "c.pet:eat_item");
            double heal = number(args, 3, 0D, MAX_HEAL);
            String sound = args.optjstring(4, "");
            float volume = (float) number(args, 5, 0D, MAX_VOLUME), pitch = (float) number(args, 6, MIN_PITCH, MAX_PITCH);
            return LuaValue.valueOf(eatItem(reach, kinds, heal, sound, volume, pitch));
        });
        // slide_point(min_length, max_length, surfaces): the end of the best straight slide from where the pet stands
        // over the surface blocks (names): of 16 headings, the run that goes farthest, each block of descent counting
        // two, up to max_length (up to 24), never climbing a block or dropping more than one, and stopping at water, a
        // wall or an unloaded chunk. nil when it does not stand on such a block or no run reaches min_length. An owned
        // pet's run must end within its follow range of its owner, and a wild pet's within max_length blocks across of
        // its home or nearer home than it starts, so slide after slide down a long slope does not carry it off.
        method("slide_point", args -> {
            double maxLength = number(args, 2, 0D, MAX_SLIDE_LENGTH);
            double minLength = number(args, 1, 0D, maxLength);
            return location(slidePoint(minLength, maxLength, blocks(args.arg(3), "c.pet:slide_point")));
        });
        // set_walk_animation(name): the animation the model plays while the pet walks, in place of its walk; an empty
        // name, or one the model has nothing for, puts its own walk back. false when the model lacks it or is gone.
        method("set_walk_animation", args -> {
            String name = args.optjstring(1, "");
            DynamicEntity modeled = control.modeled();
            if (modeled == null || modeled.isRemoved()) return LuaValue.FALSE;
            boolean has = named(name) && modeled.hasAnimation(name);
            modeled.getAnimationComponent().setWalkAnimation(has ? name : null);
            return LuaValue.valueOf(has);
        });
        // splash(sound, volume, pitch): a snap at a fish. Under water, bubbles burst from the pet's head and the
        // surface above it splashes as a fishing bobber's does when a fish bites; out of water it splashes where the
        // pet is. sound (a sound key, or NONE) plays from the splash at volume 0 to 4 and pitch 0.5 to 2.
        method("splash", args -> {
            String sound = args.optjstring(1, "");
            float volume = (float) number(args, 2, 0D, MAX_VOLUME), pitch = (float) number(args, 3, MIN_PITCH, MAX_PITCH);
            splash(body(), sound, volume, pitch);
            return LuaValue.NIL;
        });
        // set_bone_hidden(name, hidden): hides or shows the model's bones with this Blockbench name, with the bones
        // under them, as a fish carried home in the beak: list the bone under the pet file's hiddenBones so the model
        // starts without it. The saddle belongs to riding. false for the saddle, a model without the bone, or none.
        method("set_bone_hidden", args -> {
            String name = args.checkjstring(1);
            boolean hidden = args.checkboolean(2);
            DynamicEntity modeled = control.modeled();
            if (!named(name) || name.trim().equalsIgnoreCase(PetRides.SADDLE_BONE) || modeled == null
                    || modeled.isRemoved()) return LuaValue.FALSE;
            return LuaValue.valueOf(modeled.setBoneHidden(name.trim(), hidden));
        });

        Set<String> expected = new HashSet<>(PROPERTIES);
        expected.addAll(METHODS);
        if (!registered.equals(expected)) throw new IllegalStateException("PetLuaTable names drifted from its lists");
    }

    /** The read-only table modules see as c.pet. */
    LuaTable table() {
        return fields.view();
    }

    /**
     * Points the table at a callback's context, and names the module it runs for in warnings. A callback on a new pet
     * tick, a new game tick or another body reads every property afresh.
     */
    /**
     * The longest tick interval among the behaviors of this pet's program, so a flying body steered by one that runs
     * every Nth tick still counts as stuck (see {@link FlightTrack}).
     */
    void steerGap(int ticks) {
        flight.allow(ticks);
    }

    void bind(MindContext context, String module) {
        this.context = context;
        this.module = module;
        ticks.bind(PetManager.ticks(), context.gameTick(), context.entity());
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
        return location == null || location.getWorld() != body().getWorld() ? LuaValue.NIL : locations.table(location);
    }

    /** Builds the location tables c.pet returns, keeping the last world's name as a Lua string. */
    static final class LocationTables {
        private World world;
        private LuaString name;

        /** A fresh table of the location, so a module may change it without changing the next one. */
        LuaTable table(Location location) {
            World at = location.getWorld();
            if (at != world) {
                world = at;
                name = at == null ? null : LuaValue.valueOf(at.getName());
            }
            return LuaTableSupport.locationToTable(location, name);
        }
    }

    /** Owned: the owner. Wild: its home, or where it is when it has none. */
    /**
     * Whether an owned pet defends its owner against this entity: a player among the aggressors who can be hurt
     * (alive, not in creative or spectator), or a pet one of them owns.
     */
    private static boolean defendsAgainst(PetManager manager, Set<UUID> aggressors, Entity entity) {
        if (entity instanceof Player player) {
            GameMode mode = player.getGameMode();
            return aggressors.contains(player.getUniqueId()) && !player.isDead() && mode != GameMode.CREATIVE
                    && mode != GameMode.SPECTATOR;
        }
        PetControl pet = manager.pet(entity);
        if (pet != null && !pet.isWild()) return aggressors.contains(pet.ownerId());
        // A mob or a wild pet that hurt the owner or one of their pets.
        return aggressors.contains(entity.getUniqueId()) && !entity.isDead();
    }

    /** The middle of the player who last hurt this wild pet, within ATTACKER_TICKS of the hit, or null. */
    private Location attacker() {
        attackerEntity = attackerPlayer();
        attackerPoint = attackerEntity == null ? null
                : attackerEntity.getLocation().add(0D, attackerEntity.getHeight() * 0.5D, 0D);
        return attackerPoint == null ? null : attackerPoint.clone();
    }

    /** The player who last hurt this wild pet, within ATTACKER_TICKS of the hit, alive, here and hurtable, or null. */
    private Player attackerPlayer() {
        if (!control.isWild()) return null;
        UUID id = control.attacker(PetManager.ticks(), ATTACKER_TICKS);
        Player player = id == null ? null : Bukkit.getPlayer(id);
        if (player == null || player.isDead() || player.getWorld() != body().getWorld()) return null;
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR ? null : player;
    }

    /** The middle of the nearest one its owner's pets defend against, within THREAT_RANGE of the owner, or null. */
    private Location threat() {
        threatEntity = null;
        threatPoint = null;
        PetManager manager = PetManager.get();
        Player owner = control.owner().orElse(null);
        LivingEntity body = body();
        if (manager == null || owner == null || owner.getWorld() != body.getWorld()) return null;
        Set<UUID> aggressors = manager.aggressors(owner.getUniqueId());
        if (aggressors.isEmpty()) return null;
        Location here = body.getLocation();
        Entity nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (Entity entity : owner.getWorld().getNearbyEntities(owner.getLocation(), THREAT_RANGE, THREAT_RANGE, THREAT_RANGE,
                candidate -> candidate instanceof LivingEntity && defendsAgainst(manager, aggressors, candidate))) {
            double distance = entity.getLocation().distanceSquared(here);
            if (distance < best) {
                best = distance;
                nearest = entity;
            }
        }
        if (nearest == null) return null;
        threatEntity = (LivingEntity) nearest;
        threatPoint = nearest.getLocation().add(0D, nearest.getHeight() * 0.5D, 0D);
        return threatPoint.clone();
    }

    private Location anchor() {
        if (!control.isWild()) return control.owner().map(Player::getLocation).orElse(null);
        Location home = control.home();
        return home != null ? home : body().getLocation();
    }

    /** Whether snow falls on the pet, looked up at most every 10 ticks. */
    private boolean snowing() {
        long now = PetManager.ticks();
        if (now >= nextSnowCheck) {
            nextSnowCheck = now + 10L;
            snowing = PetSky.isSnowingOn(body().getLocation());
        }
        return snowing;
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

    /**
     * steer_to's tick. Toward c.pet.threat it closes in as a vanilla melee mob does: once the model touches the
     * attacker's box it stops beside them, facing them, and sets off again only when they are more than
     * {@link #BESIDE_REACH} clear of its touch. Mobs only nudge each other apart, so a pet that kept walking at the
     * attacker's middle pushed its model inside them. Toward c.pet.attacker it walks the last stretch straight at the
     * player (see {@link #steerAt}). Anywhere else it steers there.
     */
    private boolean approach(Location target, double cruise) {
        LivingEntity threat = threatAt(target);
        if (threat == null) {
            besideThreat = null;
            Player attacker = attackerAt(target);
            return attacker == null ? steerTo(target, cruise) : steerAt(target, attacker, cruise);
        }
        boolean beside = threat.getUniqueId().equals(besideThreat);
        if (!touches(threat, beside ? BESIDE_REACH : 0D)) {
            besideThreat = null;
            return steerTo(target, cruise);
        }
        if (!beside) {
            path.forget();
            context.actuator().stopMoving();
            flight.reset();
            ticks.moved();
            besideThreat = threat.getUniqueId();
        }
        context.actuator().lookAt(threat.getEyeLocation());
        return true;
    }

    /**
     * The attacker c.pet.threat gave this point for, while they are alive and in the pet's world; null for any other
     * point. A threat's point is its middle at the tick it was read, so only a steer at that very point matches.
     */
    private LivingEntity threatAt(Location target) {
        LivingEntity threat = threatEntity;
        Location point = threatPoint;
        if (threat == null || point == null || threat.isDead() || threat.getWorld() != body().getWorld()) return null;
        return target.getWorld() == point.getWorld() && target.getX() == point.getX() && target.getY() == point.getY()
                && target.getZ() == point.getZ() ? threat : null;
    }

    /**
     * The player c.pet.attacker gave this point for, while they are alive and in the pet's world; null for any other
     * point. As with a threat, only a steer at that very point matches.
     */
    private Player attackerAt(Location target) {
        Player player = attackerEntity;
        Location point = attackerPoint;
        if (player == null || point == null || player.isDead() || player.getWorld() != body().getWorld()) return null;
        return target.getWorld() == point.getWorld() && target.getX() == point.getX() && target.getY() == point.getY()
                && target.getZ() == point.getZ() ? player : null;
    }

    /**
     * One tick toward the middle of the player a wild pet charges. A path ends up to a block short of where it leads,
     * and a body counts as arrived within half its width of the path's end, so a wide body pathing at a still player
     * stopped about a block and a half from them with nothing left to walk: outside a charge's reach of 1.5 blocks
     * from their middle, where it stood until the charge ran out. A walking body therefore walks the last stretch
     * straight at their feet (see {@link WalkPath#steerAt}); a flying one steers at their middle as before.
     */
    private boolean steerAt(Location target, Player player, double cruise) {
        cruise *= control.stats().speed;
        if (control.species().locomotion() == MindBodyLocomotion.FLYING) return steerFlight(target, cruise);
        return path.steerAt(context, target, player.getLocation(), cruise);
    }

    /** One tick toward the target at the cruise a module asks for, times the pet's speed stat. */
    private boolean steerTo(Location target, double cruise) {
        cruise *= control.stats().speed;
        if (control.species().locomotion() == MindBodyLocomotion.FLYING) return steerFlight(target, cruise);
        return path.steer(context, target, cruise);
    }

    /** A flying body's steer, counted toward its stuck_ticks when the body takes it. */
    private boolean steerFlight(Location target, double cruise) {
        boolean steered = PetMotion.steerFlight(context, target, cruise);
        if (steered) flight.steered(context.gameTick(), position.get(), target, module);
        return steered;
    }

    /** c.pet.stuck_ticks: MagmaCore's count for a walking or swimming body, the FlightTrack's for a flying one. */
    private int stuckTicks() {
        if (context == null) return 0;
        if (control.species().locomotion() == MindBodyLocomotion.FLYING)
            return flight.stuck(context.gameTick(), position.get());
        return context.perception().navigation().consecutiveStuckTicks();
    }

    /** steer_onto's tick: as {@link #steerTo}, ending on the target (see {@link WalkPath#steerOnto}). */
    private boolean steerOnto(Location target, double cruise) {
        cruise *= control.stats().speed;
        if (control.species().locomotion() == MindBodyLocomotion.FLYING) return steerFlight(target, cruise);
        boolean steered = path.steerOnto(context, target, cruise);
        if (path.stoppedNow()) ticks.moved();
        return steered;
    }

    /**
     * A walking or swimming body's path. A request is kept for a second while the target stays within a block, the
     * cruise is the same and the body's navigation still heads for that request: a released MOVE lease, an arrival or
     * another module's request ends that, and the next call asks again. For its first {@link #REPATH_TICKS} a request
     * is also kept for a target that moved further, while that target still lies along it (see {@link #along}): a
     * target that moves a block every 4 or 5 ticks, as behind a walking owner, then costs a path every 10 ticks, as
     * vanilla's follow goal does, not one each time.
     * <p>
     * The request's speed is {@link #speed}: 4 × cruise, at least {@link #MIN_SPEED}, which multiplies the body type's
     * own movement speed. A cruise of 0 or less stops the body instead and reads as false, since it does not go there.
     */
    static final class WalkPath {
        /** The slowest walk or swim, a quarter of the body type's movement speed. */
        static final double MIN_SPEED = 0.25D;
        /**
         * steer_onto walks straight at a point this many blocks across, and this many up or down, or nearer: a path
         * ends about a block short, a waypoint more on a diagonal, and a body steps up 0.6.
         */
        static final double ONTO_REACH = 2D, ONTO_RISE = 0.6D;
        /** Within this many blocks across steer_onto stands still on its point; its share of speed at the very end. */
        static final double ONTO_DONE = 0.15D, ONTO_SLOWEST = 0.3D;
        /** How finely steer_onto checks the ground along its straight line, in blocks. */
        static final double ONTO_SAMPLE = 0.25D;
        /** For this many ticks after a request, a target that moved a block or more can keep it (see {@link #along}). */
        static final long REPATH_TICKS = 10L;
        /** The cosine of the widest angle, about 25 degrees, between a request and a moved target that keeps it. */
        static final double ALONG_COSINE = 0.9D;
        /** Both must lie at least this far from the body, in blocks squared: nearer, the old point leads it astray. */
        static final double ALONG_FAR_SQUARED = 4D;
        private Location target, stoppedOn;
        private boolean stoppedNow;
        private double cruise;
        private long requested, until;
        private boolean accepted;

        boolean steer(MindContext context, Location to, double cruise) {
            if (cruise <= 0D) {
                forget();
                context.actuator().stopMoving();
                return false;
            }
            long now = context.gameTick();
            if (target != null && cruise == this.cruise && now < until && target.getWorld() == to.getWorld()
                    && (target.distanceSquared(to) < 1D || now < requested + REPATH_TICKS && along(context, to))
                    && heading(context)) return accepted;
            target = to.clone();
            this.cruise = cruise;
            requested = now;
            until = now + 20L;
            accepted = context.entity().getLocation().distanceSquared(to) < 0.25D
                    || context.actuator().moveTo(to, speed(cruise));
            return accepted;
        }

        /** The native speed modifier a positive cruise asks for. */
        static double speed(double cruise) {
            return Math.max(MIN_SPEED, cruise * 4D);
        }

        /**
         * steer_onto's walk. It paths as {@link #steer} does until the point is within {@link #ONTO_REACH} blocks
         * across and {@link #ONTO_RISE} up or down, with room and firm ground on the body's level all the way there
         * (see {@link #level}); then it walks straight at it through MindActuator.moveDirect, easing off to
         * {@link #ONTO_SLOWEST} of its speed over the last block but never under {@link #MIN_SPEED}, so a path that
         * ends about a block short does not
         * leave it there. Within {@link #ONTO_DONE} it stops once and stands still on the point. A body in water keeps
         * to its path.
         */
        boolean steerOnto(MindContext context, Location to, double cruise) {
            stoppedNow = false;
            if (cruise <= 0D) {
                stoppedOn = null;
                return steer(context, to, cruise);
            }
            LivingEntity body = context.entity();
            Location here = body.getLocation();
            double dx = to.getX() - here.getX(), dz = to.getZ() - here.getZ();
            double across = Math.sqrt(dx * dx + dz * dz);
            // Standing on the point needs no look at the ground between.
            if (to.getWorld() != here.getWorld() || body.isInWater() || across > ONTO_REACH
                    || Math.abs(to.getY() - here.getY()) > ONTO_RISE || (across > ONTO_DONE && !level(here, to))) {
                stoppedOn = null;
                return steer(context, to, cruise);
            }
            forget();
            if (across <= ONTO_DONE) {
                if (stoppedOn == null || stoppedOn.distanceSquared(to) > 1.0E-4D) {
                    context.actuator().stopMoving();
                    stoppedOn = to.clone();
                    stoppedNow = true;
                }
                return true;
            }
            stoppedOn = null;
            double eased = speed(cruise) * Math.max(ONTO_SLOWEST, Math.min(1D, across));
            return context.actuator().moveDirect(to, Math.max(MIN_SPEED, eased));
        }

        /**
         * A walk at a creature whose middle is to and whose feet are at feet. It paths as {@link #steer} does until
         * the feet are within {@link #ONTO_REACH} blocks across and {@link #ONTO_RISE} up or down, with room and firm
         * ground on the body's level all the way there (see {@link #level}); then it walks straight at them through
         * MindActuator.moveDirect at its full speed, so a path that ends about a block short of the creature does
         * not leave it standing there. Whoever asks decides when it is near enough and stops asking. A body in water
         * keeps to its path.
         */
        boolean steerAt(MindContext context, Location to, Location feet, double cruise) {
            if (cruise <= 0D) return steer(context, to, cruise);
            LivingEntity body = context.entity();
            Location here = body.getLocation();
            if (feet.getWorld() != here.getWorld() || body.isInWater()) return steer(context, to, cruise);
            double dx = feet.getX() - here.getX(), dz = feet.getZ() - here.getZ();
            if (dx * dx + dz * dz > ONTO_REACH * ONTO_REACH || Math.abs(feet.getY() - here.getY()) > ONTO_RISE
                    || !level(here, feet)) return steer(context, to, cruise);
            forget();
            return context.actuator().moveDirect(feet, speed(cruise));
        }

        /** Whether the last steerOnto stopped the body. */
        boolean stoppedNow() {
            return stoppedNow;
        }

        /**
         * Whether a body standing at from can walk straight to to on its own level: every block the line crosses, in
         * steps of {@link #ONTO_SAMPLE}, has room for its feet and head over firm ground at from's feet (see
         * {@link #standing}).
         */
        static boolean level(Location from, Location to) {
            World world = from.getWorld();
            int feet = from.getBlockY();
            double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
            int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / ONTO_SAMPLE));
            for (int step = 1; step <= steps; step++) {
                double share = (double) step / steps;
                int x = (int) Math.floor(from.getX() + dx * share), z = (int) Math.floor(from.getZ() + dz * share);
                if (standing(world, x, feet, z) != feet) return false;
            }
            return true;
        }

        void forget() {
            target = null;
        }

        /**
         * Whether the moved target to still lies along the kept request, seen from the body: within about 25 degrees
         * of it ({@link #ALONG_COSINE}), with both at least 2 blocks away. Walking on toward the old point a few ticks
         * more then takes the body much the same way; a sharper turn, or a target it is nearly at, asks again at once.
         */
        private boolean along(MindContext context, Location to) {
            Location here = context.entity().getLocation();
            if (here.getWorld() != to.getWorld()) return false;
            double ax = target.getX() - here.getX(), ay = target.getY() - here.getY(), az = target.getZ() - here.getZ();
            double bx = to.getX() - here.getX(), by = to.getY() - here.getY(), bz = to.getZ() - here.getZ();
            double kept = ax * ax + ay * ay + az * az, moved = bx * bx + by * by + bz * bz;
            if (kept < ALONG_FAR_SQUARED || moved < ALONG_FAR_SQUARED) return false;
            double dot = ax * bx + ay * by + az * bz;
            return dot > 0D && dot * dot >= ALONG_COSINE * ALONG_COSINE * kept * moved;
        }

        private boolean heading(MindContext context) {
            MindPosition destination = context.perception().navigation().destination().orElse(null);
            return destination != null && destination.world().equals(target.getWorld().getName())
                    && destination.x() == target.getX() && destination.y() == target.getY()
                    && destination.z() == target.getZ();
        }
    }

    /**
     * A flying body's stuck count, kept by MagmaCore's rule for a walking body's (NativeMindNavigationTracker), since
     * MagmaCore tracks no flight: a steer starts it at 0 where the body is, and once a tick it counts up while the body
     * stays within 0.05 blocks of where it last made progress, starts again at 0 when it gets past that, and ends
     * within half a block of the point steered at. A steer at another point keeps the count. Where MagmaCore ends it
     * when the steering behavior gives up its MOVE control, which c.pet does not see, this ends it when the steers
     * stop or another module steers. c.pet:stop ends it as stopMoving ends MagmaCore's. Server thread only.
     * <p>
     * The steers have stopped when more ticks pass without one than {@link #allow} allows: one, unless a behavior of
     * the pet's program runs every Nth tick (ai.behavior's tick_interval), which steers every Nth tick at most. The
     * count is in game ticks either way: a sample taken N ticks after the last counts all N. The allowance is the
     * program's longest interval, not the steering behavior's own, which c.pet cannot tell; so in a program with a
     * slower behavior a flight that just stopped steering keeps its count that many ticks longer.
     */
    static final class FlightTrack {
        private static final double ARRIVED_SQUARED = 0.25D, PROGRESS_SQUARED = 0.0025D;
        private Location destination, progress;
        private String steerer;
        private int stuck;
        private long sampled = Long.MIN_VALUE, steered = Long.MIN_VALUE;
        private long gap = 1L;

        /** The most ticks between two steers of one flight: the longest tick interval of the pet's behaviors. */
        void allow(int ticks) {
            gap = Math.max(1L, ticks);
        }

        /** The count on this tick, for a body here now. */
        int stuck(long tick, Location here) {
            sample(tick, here);
            return stuck;
        }

        /** The body here took a steer toward target on this tick, from this module. */
        void steered(long tick, Location here, Location target, String module) {
            sample(tick, here);
            if (destination == null || !module.equals(steerer)) {
                progress = here.clone();
                stuck = 0;
            }
            destination = target.clone();
            steerer = module;
            steered = tick;
        }

        void reset() {
            destination = null;
            progress = null;
            steerer = null;
            stuck = 0;
        }

        // Once a tick, as MagmaCore's tracker begins each tick.
        private void sample(long tick, Location here) {
            if (tick == sampled) return;
            // Ticks since the last sample; more than one only between the steers of a behavior that runs every Nth.
            long elapsed = sampled == Long.MIN_VALUE ? 1L : Math.max(1L, tick - sampled);
            sampled = tick;
            if (destination == null) return;
            if (steered < tick - gap || here.getWorld() != destination.getWorld()
                    || here.distanceSquared(destination) <= ARRIVED_SQUARED) {
                reset();
            } else if (here.distanceSquared(progress) >= PROGRESS_SQUARED) {
                progress = here.clone();
                stuck = 0;
            } else {
                stuck = (int) Math.min(Integer.MAX_VALUE, stuck + elapsed);
            }
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
        // The ground right below is whatever the body would come down on: a block its middle is just past the edge
        // of holds it up, so the perch is over that block, not over the floor beside it.
        if (leaves == null && ground != null) {
            double under = PetMotion.groundBelow(from, depth + 1, false, PetMotion.halfWidth(control));
            ground.setY(Math.max(ground.getY(), under + height + sink));
        }
        Location chosen = leaves != null ? leaves : ground;
        if (chosen == null || !PetMotion.loaded(chosen) || !withinLeash(chosen)) return null;
        return chosen;
    }

    private boolean withinLeash(Location spot) {
        if (control.isWild()) return true;
        Player owner = control.owner().orElse(null);
        return owner != null && owner.getWorld() == spot.getWorld()
                && owner.getLocation().distance(spot) <= control.followRange();
    }

    /** See slide_point: where a slide from {@code from} may end. */
    private boolean slideLeash(Location from, Location end, double maxLength) {
        if (!control.isWild()) return withinLeash(end);
        Location home = control.home();
        if (home == null || home.getWorld() != end.getWorld()) return true;
        double endX = end.getX() - home.getX(), endZ = end.getZ() - home.getZ();
        double fromX = from.getX() - home.getX(), fromZ = from.getZ() - home.getZ();
        double reach = endX * endX + endZ * endZ;
        return reach <= maxLength * maxLength || reach < fromX * fromX + fromZ * fromZ;
    }

    /** The fire kinds, soul variants and smoke height a campfire call gives, from argument {@code first}. */
    private Campfire campfire(Varargs args, int first) {
        LuaValue list = args.arg(first);
        Set<Material> blocks = campfireLists.kept(list);
        if (blocks == null) blocks = campfireLists.keep(list, materials(list));
        LuaValue soul = args.arg(first + 1);
        double height = number(args, first + 2);
        // A module passes the same settings on every call: the same fire kinds, soul variants and height give the
        // Campfire made for them last time.
        Campfire kept = campfire;
        if (kept != null && kept.blocks == blocks && kept.height == height && MaterialLists.same(campfireSoul, soul))
            return kept;
        campfireSoul = MaterialLists.entries(soul);
        campfire = new Campfire(blocks, texts(soul), height);
        return campfire;
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
            return owner.getLocation().distance(above) <= Math.max(1D, control.followRange() - 1D);
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
        return touches(victim.getBoundingBox(), reach);
    }

    private boolean touches(BoundingBox box, double reach) {
        BoundingBox victimBox = box.clone().expand(reach);
        DynamicEntity modeled = control.modeled();
        if (modeled != null && !modeled.isRemoved()) {
            OrientedBoundingBox hitbox = modeled.getHitboxComponent().getObbHitbox();
            if (hitbox != null) return hitbox.intersectsAABBCoherently(victimBox);
        }
        return body().getBoundingBox().overlaps(victimBox);
    }

    /** The middle of this pet and its kin within radius, by their feet; null when fewer than min of them are there. */
    private Location kinCenter(double radius, int min) {
        PetManager manager = PetManager.get();
        LivingEntity body = body();
        Location here = body.getLocation();
        double x = here.getX(), y = here.getY(), z = here.getZ();
        int count = 1;
        if (manager != null) {
            for (Entity entity : body.getWorld().getNearbyEntities(here, radius, radius, radius,
                    candidate -> candidate instanceof LivingEntity && candidate != body)) {
                Location at = entity.getLocation();
                if (at.distanceSquared(here) > radius * radius || !control.isKin(manager.pet(entity))) continue;
                x += at.getX();
                y += at.getY();
                z += at.getZ();
                count++;
            }
        }
        return count < min ? null : new Location(here.getWorld(), x / count, y / count, z / count);
    }

    /**
     * The item kinds a list names, of its first MAX_LIST_ENTRIES; a name that is no item is warned about once. Read
     * only; the same list gives the same set until its entries change.
     */
    private Set<Material> items(LuaValue list, String call) {
        Set<Material> kinds = itemLists.kept(list);
        if (kinds != null) return kinds;
        kinds = new HashSet<>();
        for (String name : texts(list)) {
            Material material = item(name, call);
            if (material != null) kinds.add(material);
        }
        return itemLists.keep(list, kinds);
    }

    /** The item a name gives. NONE and blanks give none quietly; any other name that is no item is warned about. */
    private Material item(String name, String call) {
        return material(name, call, "item", Material::isItem);
    }

    /** The block kinds a list names, as {@link #items} reads items. */
    private Set<Material> blocks(LuaValue list, String call) {
        Set<Material> kinds = blockLists.kept(list);
        if (kinds != null) return kinds;
        kinds = new HashSet<>();
        for (String name : texts(list)) {
            Material material = material(name, call, "block", Material::isBlock);
            if (material != null) kinds.add(material);
        }
        return blockLists.keep(list, kinds);
    }

    /**
     * The material sets the last few lists of one kind parsed to, each with the entries it was parsed from. A module
     * passes the same settings list on every call, and parsing one looks up every name; a list whose entries are the
     * same as one kept gives that set again, whatever table holds them. Entries are compared raw, one by one, so a
     * list a module changes (c.settings is the pet's own, and writable) is parsed again. A few slots, reused in turn,
     * so lists made afresh on every call cannot pile up. Only tables are kept: anything else reads as an empty list.
     * Each set is read only. Server thread only.
     */
    static final class MaterialLists {
        private static final int SLOTS = 4;
        private final LuaValue[][] entries = new LuaValue[SLOTS][];
        private final List<Set<Material>> sets = new ArrayList<>(Collections.nCopies(SLOTS, null));
        private int next;

        /** The set kept for a list with these entries, or null when none is, or the list is no table. */
        Set<Material> kept(LuaValue list) {
            if (!(list instanceof LuaTable table)) return null;
            int length = Math.min(MAX_LIST_ENTRIES, table.rawlen());
            for (int slot = 0; slot < SLOTS; slot++) if (same(entries[slot], table, length)) return sets.get(slot);
            return null;
        }

        /** Keeps the set parsed from this list, in the oldest slot, and gives it back read only. */
        Set<Material> keep(LuaValue list, Set<Material> parsed) {
            Set<Material> kept = Collections.unmodifiableSet(parsed);
            if (!(list instanceof LuaTable table)) return kept;
            int length = Math.min(MAX_LIST_ENTRIES, table.rawlen());
            LuaValue[] read = new LuaValue[length];
            for (int index = 0; index < length; index++) read[index] = table.rawget(index + 1);
            entries[next] = read;
            sets.set(next, kept);
            next = (next + 1) % SLOTS;
            return kept;
        }

        /** A list's first {@link #MAX_LIST_ENTRIES} entries, read raw; none for anything but a table. */
        static LuaValue[] entries(LuaValue list) {
            if (!(list instanceof LuaTable table)) return new LuaValue[0];
            LuaValue[] read = new LuaValue[Math.min(MAX_LIST_ENTRIES, table.rawlen())];
            for (int index = 0; index < read.length; index++) read[index] = table.rawget(index + 1);
            return read;
        }

        /** Whether a list's entries are raw-equal, one by one, to the ones {@link #entries} read before. */
        static boolean same(LuaValue[] kept, LuaValue list) {
            if (!(list instanceof LuaTable table)) return kept != null && kept.length == 0;
            return same(kept, table, Math.min(MAX_LIST_ENTRIES, table.rawlen()));
        }

        private static boolean same(LuaValue[] kept, LuaTable table, int length) {
            if (kept == null || kept.length != length) return false;
            for (int index = 0; index < length; index++) if (!kept[index].raweq(table.rawget(index + 1))) return false;
            return true;
        }
    }

    private Material material(String name, String call, String kind, Predicate<Material> fits) {
        if (!named(name)) return null;
        Material material = Material.matchMaterial(name.trim());
        if (material != null && !material.isAir() && fits.test(material)) return material;
        warnings.unknownMaterial(control.species().id(), module, call, kind, name);
        return null;
    }

    /** Drops the stack just in front of the body, toward the owner, for the owner alone to pick up. */
    private static void gift(LivingEntity body, Player owner, ItemStack stack, String sound, float volume, float pitch) {
        Location from = body.getLocation();
        Vector toward = owner.getLocation().toVector().subtract(from.toVector()).setY(0D);
        double distance = toward.length();
        Vector step = distance < 1.0E-4D ? new Vector() : toward.multiply(Math.min(0.6D, distance * 0.5D) / distance);
        Item drop = body.getWorld().dropItem(from.clone().add(step).add(0D, 0.3D, 0D), stack);
        drop.setVelocity(new Vector(0D, 0.15D, 0D));
        drop.setOwner(owner.getUniqueId());
        if (named(sound) && volume > 0F) body.getWorld().playSound(from, sound, volume, pitch);
    }

    /**
     * Bubbles at the head and a splash on the water's surface straight above it, the particles a fishing bobber shows
     * when a fish bites; a body whose head is out of water splashes at its feet.
     */
    private static void splash(LivingEntity body, String sound, float volume, float pitch) {
        World world = body.getWorld();
        Location head = body.getEyeLocation();
        double spread = Math.max(0.2D, body.getWidth() * 0.5D);
        Location surface = waterSurfaceAbove(head);
        if (surface == null) {
            surface = body.getLocation().add(0D, 0.1D, 0D);
        } else {
            world.spawnParticle(Particle.BUBBLE, head, 16, spread * 0.5D, 0.15D, spread * 0.5D, 0.1D);
        }
        world.spawnParticle(Particle.SPLASH, surface, 30, spread, 0.05D, spread, 0.2D);
        world.spawnParticle(Particle.FISHING, surface, 10, spread, 0D, spread, 0.03D);
        if (named(sound) && volume > 0F) world.playSound(surface, sound, volume, pitch);
    }

    /** The top of the water over this point, when the point is in water: up to 16 blocks of it; otherwise null. */
    private static Location waterSurfaceAbove(Location point) {
        Block block = point.getBlock();
        if (!PetRides.isWater(block)) return null;
        for (int climbed = 0; climbed < (int) MAX_WATER_DEPTH; climbed++) {
            Block above = block.getRelative(0, 1, 0);
            if (!PetRides.isWater(above)) break;
            block = above;
        }
        return new Location(point.getWorld(), point.getX(), block.getY() + 1.0D, point.getZ());
    }

    /** The nearest loose stack of these kinds within radius, and within an owned pet's leash; null when none is. */
    private Item nearestItem(double radius, Set<Material> kinds) {
        if (kinds.isEmpty()) return null;
        LivingEntity body = body();
        Location here = body.getLocation();
        Item nearest = null;
        double best = radius * radius;
        for (Entity entity : body.getWorld().getNearbyEntities(here, radius, radius, radius,
                candidate -> candidate instanceof Item)) {
            Item item = (Item) entity;
            double distance = item.getLocation().distanceSquared(here);
            if (distance > best || !loose(item, kinds) || !withinLeash(item.getLocation())) continue;
            best = distance;
            nearest = item;
        }
        return nearest;
    }

    /**
     * A dropped stack of these kinds that anyone may pick up, as plain as it comes: an item kept for one player, such
     * as a pet's gift, a display piece nobody may pick up, and one carrying a name, lore or any other data stay put.
     */
    private static boolean loose(Item item, Set<Material> kinds) {
        if (item.isDead() || item.getOwner() != null || item.getPickupDelay() >= NEVER_PICKED_UP) return false;
        ItemStack stack = item.getItemStack();
        return kinds.contains(stack.getType()) && !stack.hasItemMeta();
    }

    /** Eats one item off the nearest loose stack of these kinds the model touches within reach; whether it did. */
    private boolean eatItem(double reach, Set<Material> kinds, double heal, String sound, float volume, float pitch) {
        if (kinds.isEmpty()) return false;
        LivingEntity body = body();
        Location here = body.getLocation();
        Item nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (Entity entity : body.getWorld().getNearbyEntities(body.getBoundingBox().expand(reach + 1.5D),
                candidate -> candidate instanceof Item)) {
            Item item = (Item) entity;
            double distance = item.getLocation().distanceSquared(here);
            if (distance >= best || !loose(item, kinds) || !touches(item.getBoundingBox(), reach)) continue;
            best = distance;
            nearest = item;
        }
        if (nearest == null) return false;
        ItemStack stack = nearest.getItemStack();
        int left = stack.getAmount() - 1;
        EntityPickupItemEvent event = new EntityPickupItemEvent(body, nearest, Math.max(0, left));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        Location at = nearest.getLocation().add(0D, 0.25D, 0D);
        Material type = stack.getType();
        if (left <= 0) {
            nearest.remove();
        } else {
            stack.setAmount(left);
            nearest.setItemStack(stack);
        }
        World world = body.getWorld();
        world.spawnParticle(Particle.ITEM, at, 8, 0.1D, 0.1D, 0.1D, 0.05D, new ItemStack(type));
        if (named(sound) && volume > 0F) world.playSound(here, sound, volume, pitch);
        if (heal > 0D) heal(body, heal);
        return true;
    }

    /** Heals the body as eating does, unless a plugin cancels it; a pet showing its health shows the new one. */
    private void heal(LivingEntity body, double amount) {
        double max = PetManager.maxHealth(body);
        if (body.isDead() || body.getHealth() >= max) return;
        EntityRegainHealthEvent event = new EntityRegainHealthEvent(body, amount,
                EntityRegainHealthEvent.RegainReason.EATING);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.getAmount() <= 0D) return;
        body.setHealth(Math.min(max, body.getHealth() + event.getAmount()));
        PetManager manager = PetManager.get();
        if (manager != null) manager.showHealth(control);
    }

    /** No block in the column a body can stand in. */
    private static final int NO_FOOTING = Integer.MIN_VALUE;

    /** See slide_point. */
    private Location slidePoint(double minLength, double maxLength, Set<Material> surfaces) {
        if (surfaces.isEmpty() || maxLength < SLIDE_STEP) return null;
        LivingEntity body = body();
        Location from = body.getLocation();
        World world = from.getWorld();
        int startX = from.getBlockX(), startZ = from.getBlockZ();
        int start = standing(world, startX, from.getBlockY() + 1, startZ);
        if (start == NO_FOOTING || !surfaces.contains(surface(world, startX, start, startZ))) return null;
        // A random turn of the 16 headings, so slides do not all run along the block grid.
        double turn = ThreadLocalRandom.current().nextDouble(Math.PI / 8D);
        Location best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int heading = 0; heading < 16; heading++) {
            double angle = turn + heading * Math.PI / 8D, dx = Math.cos(angle), dz = Math.sin(angle);
            int feet = start, descent = 0;
            double run = 0D;
            Location end = null;
            for (double along = SLIDE_STEP; along <= maxLength + 1.0E-9D; along += SLIDE_STEP) {
                double x = from.getX() + dx * along, z = from.getZ() + dz * along;
                int blockX = (int) Math.floor(x), blockZ = (int) Math.floor(z);
                if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) break;
                int next = standing(world, blockX, feet, blockZ);
                if (next == NO_FOOTING || !surfaces.contains(surface(world, blockX, next, blockZ))) break;
                descent += feet - next;
                feet = next;
                run = along;
                end = new Location(world, x, next, z);
            }
            if (end == null || run < minLength) continue;
            double score = run + 2D * descent;
            if (score > bestScore && slideLeash(from, end, maxLength)) {
                bestScore = score;
                best = end;
            }
        }
        return best;
    }

    /**
     * The block a body stands in at this column, at feet or one below: room for its feet and head over firm ground.
     * NO_FOOTING when neither fits: a wall, a drop of two or more, or liquid. A snow layer is room, the body standing in it.
     */
    private static int standing(World world, int x, int feet, int z) {
        for (int y = feet; y >= feet - 1; y--) {
            if (y - 1 < world.getMinHeight() || y + 1 >= world.getMaxHeight()) return NO_FOOTING;
            if (!room(world.getBlockAt(x, y, z)) || !firm(world.getBlockAt(x, y - 1, z))) continue;
            return room(world.getBlockAt(x, y + 1, z)) ? y : NO_FOOTING;
        }
        return NO_FOOTING;
    }

    /** A block a body's feet or head fit in: a snow layer, or anything passable but liquid and powder snow. */
    static boolean room(Block block) {
        Material type = block.getType();
        if (type == Material.SNOW) return true;
        return block.isPassable() && !block.isLiquid() && type != Material.POWDER_SNOW;
    }

    /** A block a body stands on: solid, and neither a snow layer, which it stands in, nor powder snow, which it sinks in. */
    static boolean firm(Block block) {
        Material type = block.getType();
        return !block.isPassable() && type != Material.SNOW && type != Material.POWDER_SNOW;
    }

    /** What a body standing in this block slides on: the snow layer it stands in, or the block under it. */
    private static Material surface(World world, int x, int feet, int z) {
        Material at = world.getBlockAt(x, feet, z).getType();
        return at == Material.SNOW ? at : world.getBlockAt(x, feet - 1, z).getType();
    }

    /** A sting's effect name, duration ticks, amplifier, damage and cooldown ticks. */
    private record Sting(String effect, int duration, int amplifier, double damage, int cooldown) {
    }

    /**
     * How a strike sounds and looks: a sound key at a volume and pitch, a particle, and the model animation it plays;
     * null plays or shows none.
     */
    private record Look(String sound, float volume, float pitch, Particle particle, String animation) {
    }

    /** The model animation every sting plays, and a strike that names no other. */
    static final String STING_ANIMATION = "attack";

    /** The animation a strike names, trimmed, or null for a blank one or NONE: it plays none. */
    static String strikeAnimation(String name) {
        return named(name) ? name.trim() : null;
    }

    /** Whether a text names something: not blank, and not NONE. */
    private static boolean named(String text) {
        return text != null && !text.isBlank() && !text.equalsIgnoreCase("NONE");
    }

    /**
     * The particle a module names, or null for none. One that names no particle, or one that needs more than a name
     * (dust needs a colour), is warned about once per species and name per load.
     */
    private Particle particle(String name, String call) {
        if (!named(name)) return null;
        Particle particle = null;
        try {
            particle = Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            // Warned below.
        }
        if (particle != null && particle.getDataType() == Void.class) return particle;
        warnings.unknownParticle(control.species().id(), module, call, name);
        return null;
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

    /** Stings the victim; a null look buzzes and sparks as a sting does. FREEZE freezes it for the duration's ticks. */
    private boolean sting(LivingEntity victim, Sting sting, Look look, String call) {
        long now = PetManager.ticks();
        Long readyAt = stingReadyAt.get(victim.getUniqueId());
        if (readyAt != null && now < readyAt) return false;
        if (stingReadyAt.size() > 32) stingReadyAt.values().removeIf(at -> at <= now);
        stingReadyAt.put(victim.getUniqueId(), now + sting.cooldown());
        // The pet's sting and poison stats scale what the module asks for; a baby's are halved until it grows up.
        PetStats stats = control.stats();
        double share = control.babyShare();
        int duration = (int) Math.max(1L, Math.round(sting.duration() * stats.poison * share));
        if (FREEZE.equalsIgnoreCase(sting.effect())) {
            // Frost creeps in over 140 frozen ticks and thaws 2 a tick, so past 140 the victim is fully frozen (shaking,
            // slowed, hurt every 2 seconds unless it wears leather) for half the excess, then thaws.
            victim.setFreezeTicks(Math.max(victim.getFreezeTicks(), duration));
        } else {
            PotionEffectType effect = knownEffect(sting.effect(), call);
            if (effect != null)
                victim.addPotionEffect(new PotionEffect(effect, duration, sting.amplifier(), false, true, true));
        }
        double damage = sting.damage() * stats.sting * share;
        if (damage > 0D) victim.damage(damage, body());
        Location at = victim.getLocation().add(0D, victim.getHeight() * 0.5D, 0D);
        if (look == null) {
            victim.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 8, 0.25D, 0.3D, 0.25D, 0.05D);
            victim.getWorld().playSound(at, Sound.ENTITY_BEE_STING, 0.6F, 1.6F);
        } else {
            if (look.particle() != null) victim.getWorld().spawnParticle(look.particle(), at, 8, 0.25D, 0.3D, 0.25D, 0.05D);
            if (look.sound() != null && look.volume() > 0F)
                body().getWorld().playSound(body().getLocation(), look.sound(), look.volume(), look.pitch());
        }
        DynamicEntity modeled = control.modeled();
        // Every model that has the animation plays it, babies' included: attack, or the one a strike names.
        String animation = look == null ? STING_ANIMATION : look.animation();
        if (animation != null && modeled != null && !modeled.isRemoved() && modeled.hasAnimation(animation))
            modeled.playAnimation(animation, true, false);
        return true;
    }

    /** The effect a module names; one that names none is warned about once per species and name per load. */
    private PotionEffectType knownEffect(String name, String call) {
        PotionEffectType effect = effect(name);
        if (effect == null && named(name))
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

    /**
     * Whether an effect the owner has, at {@code amplifier} with {@code remaining} ticks left (or infinite), makes a
     * renewal at {@code wantedAmplifier} for {@code wantedDuration} ticks pointless: it is at least as strong and lasts
     * at least as long. A stronger but shorter one does not; vanilla then keeps the renewal hidden beneath it, to take
     * over when it ends.
     */
    static boolean covers(int amplifier, boolean infinite, int remaining, int wantedAmplifier, int wantedDuration) {
        return amplifier >= wantedAmplifier && (infinite || remaining >= wantedDuration);
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

        /** A c.pet call was given an item or block name that names none of that kind. */
        void unknownMaterial(String species, String module, String call, String kind, String name) {
            say("material\n" + species + "\n" + name.toLowerCase(Locale.ROOT), "The " + species + " pet behavior "
                    + module + " gave " + call + " the " + kind + " " + name + ", which does not exist; it is skipped.",
                    null);
        }

        /** A c.pet call was given a particle name that names none, or one that needs more than its name. */
        void unknownParticle(String species, String module, String call, String particle) {
            say("particle\n" + species + "\n" + particle.toLowerCase(Locale.ROOT), "The " + species + " pet behavior "
                    + module + " gave " + call + " the particle " + particle + ", which does not exist or needs more"
                    + " than a name; it shows none.", null);
        }

        private void say(String key, String message, Throwable failure) {
            if (said.size() >= MAX_SAID || !said.add(key)) return;
            sink.accept(message, failure);
        }
    }

    /**
     * The tick c.pet's properties belong to. A property read through {@link #perTick} asks its source once per tick
     * and keeps the answer, failures aside, until a callback binds another pet tick, game tick or body, or
     * {@link #moved} says c.pet moved or stopped the body itself. Before any callback has bound one, every read asks.
     * Server thread only.
     */
    static final class Ticks {
        /** {@link #stamp} outside a callback, which no cache takes as current. */
        static final long UNBOUND = -1L;
        private long petTick, gameTick;
        private Object body;
        private boolean bound;
        private long epoch;

        /** The callback about to run is on this pet tick and game tick, for this body. */
        void bind(long petTick, long gameTick, Object body) {
            if (bound && petTick == this.petTick && gameTick == this.gameTick && body == this.body) return;
            bound = true;
            this.petTick = petTick;
            this.gameTick = gameTick;
            this.body = body;
            epoch++;
        }

        /** c.pet moved or stopped the body, so what its properties read may have changed. */
        void moved() {
            epoch++;
        }

        /**
         * What a cached answer keeps to stay current: it changes with each tick, body and c.pet move, as
         * {@link #perTick} values do. {@link #UNBOUND} outside a callback.
         */
        long stamp() {
            return bound ? epoch : UNBOUND;
        }

        /** {@code source}, asked at most once per tick. */
        <T> Supplier<T> perTick(Supplier<T> source) {
            return new Supplier<>() {
                private long readIn = -1L;
                private T value;

                @Override
                public T get() {
                    if (!bound) return source.get();
                    if (readIn != epoch) {
                        value = source.get();
                        readIn = epoch;
                    }
                    return value;
                }
            };
        }
    }

    /**
     * A read-only Lua table of named fields. Every read asks the field's supplier, and the supplier decides how fresh
     * the value is: most of c.pet's keep theirs for the pet tick through {@link Ticks#perTick}. A method is a field
     * holding a function, called with a dot or with a colon on this table. Assigning to the table, rawset on it and
     * setmetatable on it raise a Lua error naming the field. A Java failure inside a field or method, linkage errors
     * included, goes to the report with the field's name and reaches Lua as a short error; Lua errors, such as bad
     * arguments, pass through unreported.
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
