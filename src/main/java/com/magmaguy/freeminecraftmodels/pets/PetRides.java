package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.commands.MountCommand;
import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.SwimMounts;
import com.magmaguy.freeminecraftmodels.dataconverter.HitboxBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import com.magmaguy.magmacore.ai.MindHandle;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * Saddles and riders for species with a {@code riding:} section. The owner saddles an adult, rides it with an empty
 * hand and takes the saddle back with shears. While ridden, the pet's Mind is released: its behaviors stop and the
 * rider's client steers the horse body through vanilla, at the species' ridden speed for the block it is on, and the
 * pet pushes the mobs it runs into out of its way. A species with a swim speed dives in deep water: the pet moves onto
 * a nautilus body its rider steers as a vanilla nautilus, and back onto a body of its own kind at the shore. When the
 * rider gets off the Mind takes the body back.
 */
final class PetRides {
    /** The bone a rideable pet's model draws its saddle with, shown only while it wears one. */
    static final String SADDLE_BONE = "saddle";
    private static final double SPEED_STEP = 1.0E-4D;
    /**
     * The least a ridden pet moves in a tick, in blocks, to push the mobs it overlaps aside: the step its model counts
     * as moving, so it pushes exactly while it plays its ridden animation.
     */
    private static final double PUSHING_STEP = 1.0E-3D;
    /**
     * The share of its speed a mob keeps each tick it slides on ordinary ground: a block's slipperiness (0.6) times air
     * drag (0.91), as vanilla moves it. A mob sent off at speed v so comes to rest v / (1 - this) blocks away.
     */
    private static final double GROUND_SLIDE = 0.6D * 0.91D;
    /**
     * A diving pet surfaces once the water under it is this many blocks shallower than it dives at, so it never flips
     * between its bodies at the depth it dives at. A seated rider's eyes are only about 1.6 blocks above the pet's
     * feet, so a whole block would keep it diving in the shallowest water, until it climbed out at the bank.
     */
    private static final double SURFACE_MARGIN = 0.5D;
    private final PetManager manager;
    /** Each ridden pet with the movement speed and jump strength its body had before, and where it stood last tick. */
    private final Map<PetControl, Ride> ridden = new IdentityHashMap<>();
    // While a dive or surfacing moves a rider between bodies, so the dismount it causes is not vanilla's.
    private boolean swapping;

    private record Unridden(double speed, double jump) {
    }

    private static final class Ride {
        private final Unridden before;
        // The rider's client moves the body and the server only snaps it there, so it has no velocity to read.
        private double lastX = Double.NaN, lastZ = Double.NaN;
        // Whether the pet swims as its diving body now, its rider steering it as a vanilla nautilus.
        private boolean diving;

        private Ride(Unridden before) {
            this.before = before;
        }
    }

    PetRides(PetManager manager) {
        this.manager = manager;
    }

    /** Whether this pet can wear a saddle and carry its owner: an adult of a species that can be ridden. */
    static boolean rideable(PetControl control) {
        return control.species().riding() != null && !control.isBaby() && control.record() != null;
    }

    /**
     * Gets a new body ready: a rideable adult's saddle sits at the species' seat height, its model plays the ridden
     * animation while ridden and moving, and one whose record has a saddle wears it, its model's saddle bone with it.
     * On its diving body it swims under its rider instead, as it swims on its own, and leans with the dive.
     */
    static void prepare(PetControl control, LivingEntity body, DynamicEntity modeled) {
        PetRiding riding = control.species().riding();
        if (riding == null || control.isBaby()) return;
        modeled.liftSaddleToHeight(riding.seatHeight());
        if (SwimMounts.isSwimMount(body)) {
            PetSwimming swimming = control.species().swimming();
            modeled.getAnimationComponent().setRiddenAnimation(swimming != null ? swimming.animation() : PetSwimming.ANIMATION);
            modeled.getAnimationComponent().setRiddenIdleAnimation(
                    swimming != null ? swimming.idleAnimation() : PetSwimming.IDLE_ANIMATION);
            modeled.setLeansWhileRidden(true);
        } else {
            modeled.getAnimationComponent().setRiddenAnimation(riding.animation());
            modeled.getAnimationComponent().setRiddenIdleAnimation(riding.idleAnimation());
        }
        boolean saddled = control.record() != null && control.record().saddled;
        modeled.setBoneHidden(SADDLE_BONE, !saddled);
        if (saddled) putSaddleOn(body, control.ownerId());
    }

    /**
     * The owner's right-click with this item, for the rules riding adds: a saddle on an unsaddled adult, shears on a
     * saddled one, and an empty hand (or another saddle) on a saddled adult to get on. Returns whether the click was
     * used; the click then does nothing else.
     */
    boolean click(Player player, PetControl control, ItemStack held) {
        if (control.species().riding() == null) return false;
        Material type = held.getType();
        if (type == Material.SADDLE && control.isBaby()) {
            player.sendMessage(ChatColorConverter.convert(PetsConfig.saddleTooYoungMessage
                    .replace("$pet", manager.displayName(control.record()))));
            return true;
        }
        if (!rideable(control)) return false;
        PetRecord record = control.record();
        if (type == Material.SADDLE && !record.saddled) {
            saddle(player, control, held);
            return true;
        }
        if (type == Material.SHEARS && record.saddled) {
            unsaddle(player, control);
            return true;
        }
        if ((type.isAir() || type == Material.SADDLE) && record.saddled) {
            mount(player, control);
            return true;
        }
        return false;
    }

    private void saddle(Player player, PetControl control, ItemStack held) {
        LivingEntity body = control.body();
        if (body == null || !body.isValid()) return;
        if (player.getGameMode() != GameMode.CREATIVE) held.setAmount(held.getAmount() - 1);
        control.record().saddled = true;
        manager.collection(player).save();
        putSaddleOn(body, control.ownerId());
        showSaddle(control, true);
        body.getWorld().playSound(body.getLocation(), Sound.ENTITY_HORSE_SADDLE, 1F, 1F);
        player.sendMessage(ChatColorConverter.convert(PetsConfig.saddledMessage
                .replace("$pet", manager.displayName(control.record()))));
    }

    private void unsaddle(Player player, PetControl control) {
        control.record().saddled = false;
        manager.collection(player).save();
        LivingEntity body = control.body();
        Location at = body != null && body.isValid() ? body.getLocation() : player.getLocation();
        if (body instanceof AbstractHorse horse) horse.getInventory().setSaddle(null);
        showSaddle(control, false);
        at.getWorld().dropItemNaturally(at, new ItemStack(Material.SADDLE));
        at.getWorld().playSound(at, Sound.ENTITY_SHEEP_SHEAR, 1F, 1F);
        player.sendMessage(ChatColorConverter.convert(PetsConfig.unsaddledMessage
                .replace("$pet", manager.displayName(control.record()))));
    }

    /** Seats the owner on the pet, handing its body from its behaviors to the rider. Returns whether they got on. */
    private boolean mount(Player player, PetControl control) {
        LivingEntity body = control.body();
        DynamicEntity modeled = control.modeled();
        MindHandle mind = control.mind();
        if (body == null || !body.isValid() || modeled == null || mind == null || ridden.containsKey(control)) return false;
        if (!modeled.isSteerableMount()) putSaddleOn(body, control.ownerId());
        PetRiding riding = control.species().riding();
        Unridden before = new Unridden(base(body, Attribute.MOVEMENT_SPEED), base(body, Attribute.JUMP_STRENGTH));
        mind.setReleased(true);
        setBase(body, Attribute.MOVEMENT_SPEED, riding.speed() * control.stats().speed);
        setBase(body, Attribute.JUMP_STRENGTH, riding.jump());
        if (!modeled.seatSteeringRider(player)) {
            restore(body, before);
            mind.setReleased(false);
            return false;
        }
        ridden.put(control, new Ride(before));
        // A staying pet the owner rides away follows again afterwards rather than walking back to its spot.
        if (control.isStaying()) manager.follow(player, control);
        return true;
    }

    /**
     * Every tick: dives a ridden pet that dives into deep water and surfaces it at the shore, sets its speed for the
     * block it is on or its swim, pushes the mobs it runs into aside, and hands a pet nobody rides back to its Mind.
     */
    void tick() {
        if (ridden.isEmpty()) return;
        for (Map.Entry<PetControl, Ride> entry : new ArrayList<>(ridden.entrySet())) {
            PetControl control = entry.getKey();
            Ride ride = entry.getValue();
            LivingEntity body = control.body();
            if (body == null || !body.isValid()) {
                getOff(control);
                continue;
            }
            Player rider = rider(body);
            if (rider == null) {
                dismount(control);
                continue;
            }
            PetRiding riding = control.species().riding();
            if (riding.dives() && SwimMounts.available()) {
                double eyes = rider.getEyeLocation().getY() - body.getLocation().getY();
                Block feet = body.getLocation().getBlock();
                int depth = waterDepth(dy -> isWater(feet.getRelative(0, dy, 0)), (int) Math.ceil(eyes) + 1);
                boolean diving = diving(ride.diving, depth, eyes, isWater(rider.getEyeLocation().getBlock()));
                if (diving != ride.diving) {
                    LivingEntity next = swap(control, rider, diving);
                    if (next == null) continue;
                    body = next;
                    ride.diving = diving;
                    ride.lastX = Double.NaN;
                    // A body that would not seat its rider is handed back to the Mind next tick, as after any dismount.
                    if (rider(body) == null) continue;
                }
            }
            Location at = body.getLocation();
            double speed;
            if (ride.diving) speed = riding.swimMovementSpeed(control.stats().speed);
            else {
                Block feet = at.getBlock();
                speed = riding.speed() * control.stats().speed
                        * riding.multiplier(feet.getType(), feet.getRelative(BlockFace.DOWN).getType());
            }
            setBase(body, Attribute.MOVEMENT_SPEED, speed);
            if (!Double.isNaN(ride.lastX)) pushAside(control, body, at, at.getX() - ride.lastX, at.getZ() - ride.lastZ);
            ride.lastX = at.getX();
            ride.lastZ = at.getZ();
        }
    }

    /**
     * Moves the rider onto the pet's other body: its diving body when it dives, a body of its own kind when it
     * surfaces. Returns the new body, or null when it could not spawn and the rider is seated back on the old one.
     * Either seating can fail, leaving the pet without its rider.
     */
    private LivingEntity swap(PetControl control, Player rider, boolean dive) {
        String carrier = dive ? SwimMounts.CARRIER : control.species().body().getKey().toString();
        swapping = true;
        try {
            rider.leaveVehicle();
            LivingEntity next = manager.rebody(control, carrier);
            DynamicEntity modeled = control.modeled();
            if (modeled != null) modeled.seatSteeringRider(rider);
            if (next != null) setBase(next, Attribute.JUMP_STRENGTH, control.species().riding().jump());
            return next;
        } finally {
            swapping = false;
        }
    }

    /**
     * The rider got off a body that is still there: a diving pet gets a body of its own kind back first, so it swims
     * on its own as it always does.
     */
    private void dismount(PetControl control) {
        Ride ride = ridden.get(control);
        if (ride != null && ride.diving) manager.rebody(control, control.species().body().getKey().toString());
        getOff(control);
    }

    /**
     * Whether vanilla must keep this rider on: a horse throws off a rider whose eyes go under water, and a ridden pet
     * that dives takes them under instead, in its diving body on the next tick.
     */
    boolean keepsUnderwaterRider(PetControl control, Player rider) {
        PetRiding riding = control.species().riding();
        return !swapping && ridden.containsKey(control) && riding != null && riding.dives() && SwimMounts.available()
                && isWater(rider.getEyeLocation().getBlock());
    }

    /**
     * Pushes the mobs a moving ridden pet overlaps off its path. Vanilla never stops a ridden horse at a mob and nudges
     * the mob only 0.05 blocks a tick, so a pet sliding at speed went straight through animals, monsters and other
     * pets. Each mob inside the model's bounds is sent sideways, away from the line the pet travels, just fast enough
     * that ground friction stops it clear of the pet; its knockback resistance takes its share off, as it does a hit's
     * knockback. Players, mounts, riders and mobs that do not collide are left to vanilla.
     */
    private static void pushAside(PetControl control, LivingEntity body, Location at, double stepX, double stepZ) {
        double step = Math.sqrt(stepX * stepX + stepZ * stepZ);
        if (step < PUSHING_STEP) return;
        // The left of the direction the pet travels.
        double leftX = -stepZ / step, leftZ = stepX / step;
        // The body's box, or the model's when that is bigger: its hitbox bone, else its cubes. A ridden horse body is
        // scaled down to put its saddle on the seat, so it is usually the smaller.
        BoundingBox own = body.getBoundingBox();
        double half = own.getWidthX() / 2D, height = own.getHeight();
        DynamicEntity modeled = control.modeled();
        SkeletonBlueprint blueprint = modeled == null ? null : modeled.getSkeletonBlueprint();
        if (blueprint != null) {
            double scale = modeled.getScaleModifier();
            HitboxBlueprint hitbox = blueprint.getHitbox();
            double width = hitbox != null ? Math.max(hitbox.getWidthX(), hitbox.getWidthZ()) : blueprint.getModelWidth();
            half = Math.max(half, width * scale / 2D);
            height = Math.max(height, (hitbox != null ? hitbox.getHeight() : blueprint.getModelTop()) * scale);
        }
        BoundingBox bounds = new BoundingBox(at.getX() - half, at.getY(), at.getZ() - half,
                at.getX() + half, at.getY() + height, at.getZ() + half);
        for (Entity entity : body.getWorld().getNearbyEntities(bounds, candidate -> candidate instanceof Mob)) {
            Mob mob = (Mob) entity;
            if (mob == body || mob.isDead() || !mob.isCollidable() || mob.isInsideVehicle()
                    || !mob.getPassengers().isEmpty()) continue;
            double share = 1D - knockbackResistance(mob);
            if (share <= 0D) continue;
            Location spot = mob.getLocation();
            double side = (spot.getX() - at.getX()) * leftX + (spot.getZ() - at.getZ()) * leftZ;
            double clear = half + mob.getBoundingBox().getWidthX() / 2D - Math.abs(side);
            if (clear <= 0D) continue;
            // Dead ahead, each mob takes the side its id gives it, so a crowd splits instead of all going one way.
            double away = Math.abs(side) > 1.0E-3D ? Math.signum(side) : (mob.getEntityId() & 1) == 0 ? 1D : -1D;
            double wanted = clear * (1D - GROUND_SLIDE) * share;
            Vector velocity = mob.getVelocity();
            double sideways = (velocity.getX() * leftX + velocity.getZ() * leftZ) * away;
            if (sideways >= wanted) continue;
            double added = (wanted - sideways) * away;
            mob.setVelocity(velocity.add(new Vector(leftX * added, 0D, leftZ * added)));
        }
    }

    private static double knockbackResistance(LivingEntity entity) {
        AttributeInstance instance = entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        return instance == null ? 0D : Math.max(0D, Math.min(1D, instance.getValue()));
    }

    /**
     * Whether a ridden pet that dives swims as its diving body next tick. It dives where the water through its feet is
     * as deep as its rider's eyes are above them, where a pet standing on the bottom would hold them under; its rider's
     * eyes alone cannot tell, since its small land body floats and keeps them above the surface. It surfaces with its
     * rider's head out of the water once the water is SURFACE_MARGIN shallower, in the shallows of a beach, so an
     * underwater tunnel never surfaces it.
     */
    static boolean diving(boolean diving, int depth, double eyeHeight, boolean eyesInWater) {
        if (!diving) return depth >= eyeHeight;
        return eyesInWater || depth >= eyeHeight - SURFACE_MARGIN;
    }

    /**
     * The water blocks in the column through the feet block (offset 0), counted up and down from it, at most limit
     * each way: diving only needs to know whether it reaches the rider's eyes. 0 when the feet are out of water.
     */
    static int waterDepth(IntPredicate waterAt, int limit) {
        if (!waterAt.test(0)) return 0;
        int depth = 1;
        for (int dy = 1; dy <= limit && waterAt.test(dy); dy++) depth++;
        for (int dy = -1; dy >= -limit && waterAt.test(dy); dy--) depth++;
        return depth;
    }

    /** Whether this block holds water: water itself, a bubble column, water plants, or a waterlogged block. */
    static boolean isWater(Block block) {
        Material type = block.getType();
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN || type == Material.KELP
                || type == Material.KELP_PLANT || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS) return true;
        return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    /** The rider got off, or the body is gone: its old speed and jump come back and its behaviors take over again. */
    void getOff(PetControl control) {
        Ride ride = ridden.remove(control);
        if (ride == null) return;
        LivingEntity body = control.body();
        if (body != null && body.isValid()) restore(body, ride.before);
        MindHandle mind = control.mind();
        if (mind == null) return;
        try {
            mind.setReleased(false);
        } catch (IllegalStateException closed) {
            // Its Mind closed with its body.
        }
    }

    /** Whether the owner rides this pet now. */
    boolean isRidden(PetControl control) {
        return ridden.containsKey(control);
    }

    /** Drops the saddle a pet wore where it was, when it dies or is set free; its record no longer has one. */
    static void dropSaddle(PetRecord record, Location at) {
        if (record == null || !record.saddled || at == null || at.getWorld() == null) return;
        record.saddled = false;
        at.getWorld().dropItemNaturally(at, new ItemStack(Material.SADDLE));
    }

    void clear() {
        for (PetControl control : new ArrayList<>(ridden.keySet())) getOff(control);
    }

    /**
     * FreeMinecraftModels' invisible saddle, on a tamed body its owner owns, as a vanilla horse or nautilus needs to
     * carry a steering rider. The record holds the real saddle; this one never leaves the body.
     */
    private static void putSaddleOn(LivingEntity body, UUID owner) {
        Player player = owner == null ? null : org.bukkit.Bukkit.getPlayer(owner);
        if (SwimMounts.isSwimMount(body)) {
            SwimMounts.saddle(body, player, MountCommand.unseenSaddle());
            return;
        }
        if (!(body instanceof AbstractHorse horse)) return;
        horse.setTamed(true);
        if (player != null) horse.setOwner(player);
        horse.getInventory().setSaddle(MountCommand.unseenSaddle());
    }

    private static void showSaddle(PetControl control, boolean shown) {
        DynamicEntity modeled = control.modeled();
        if (modeled != null) modeled.setBoneHidden(SADDLE_BONE, !shown);
    }

    private static Player rider(LivingEntity body) {
        List<Entity> passengers = body.getPassengers();
        return !passengers.isEmpty() && passengers.getFirst() instanceof Player player ? player : null;
    }

    private static double base(LivingEntity body, Attribute attribute) {
        AttributeInstance instance = body.getAttribute(attribute);
        return instance == null ? Double.NaN : instance.getBaseValue();
    }

    private static void setBase(LivingEntity body, Attribute attribute, double value) {
        AttributeInstance instance = body.getAttribute(attribute);
        // Each change is sent to the rider's client; only a real one goes out.
        if (instance != null && Math.abs(instance.getBaseValue() - value) > SPEED_STEP) instance.setBaseValue(value);
    }

    private static void restore(LivingEntity body, Unridden before) {
        if (!Double.isNaN(before.speed())) setBase(body, Attribute.MOVEMENT_SPEED, before.speed());
        if (!Double.isNaN(before.jump())) setBase(body, Attribute.JUMP_STRENGTH, before.jump());
    }
}
