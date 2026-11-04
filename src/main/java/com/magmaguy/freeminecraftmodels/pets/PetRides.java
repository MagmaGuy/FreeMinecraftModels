package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.commands.MountCommand;
import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
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
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saddles and riders for species with a {@code riding:} section. The owner saddles an adult, rides it with an empty
 * hand and takes the saddle back with shears. While ridden, the pet's Mind is released: its behaviors stop and the
 * rider's client steers the horse body through vanilla, at the species' ridden speed for the block it is on. When the
 * rider gets off the Mind takes the body back.
 */
final class PetRides {
    /** The bone a rideable pet's model draws its saddle with, shown only while it wears one. */
    static final String SADDLE_BONE = "saddle";
    private static final double SPEED_STEP = 1.0E-4D;
    private final PetManager manager;
    /** Each ridden pet with the movement speed and jump strength its body had before. */
    private final Map<PetControl, Unridden> ridden = new IdentityHashMap<>();

    private record Unridden(double speed, double jump) {
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
     */
    static void prepare(PetControl control, LivingEntity body, DynamicEntity modeled) {
        PetRiding riding = control.species().riding();
        if (riding == null || control.isBaby()) return;
        modeled.liftSaddleToHeight(riding.seatHeight());
        modeled.getAnimationComponent().setRiddenAnimation(riding.animation());
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
        ridden.put(control, before);
        // A staying pet the owner rides away follows again afterwards rather than walking back to its spot.
        if (control.isStaying()) manager.follow(player, control);
        return true;
    }

    /** Every tick: speeds each ridden pet up on its species' surfaces, and hands a pet nobody rides back to its Mind. */
    void tick() {
        if (ridden.isEmpty()) return;
        for (Map.Entry<PetControl, Unridden> entry : new ArrayList<>(ridden.entrySet())) {
            PetControl control = entry.getKey();
            LivingEntity body = control.body();
            if (body == null || !body.isValid() || rider(body) == null) {
                getOff(control);
                continue;
            }
            PetRiding riding = control.species().riding();
            Block feet = body.getLocation().getBlock();
            double speed = riding.speed() * control.stats().speed
                    * riding.multiplier(feet.getType(), feet.getRelative(BlockFace.DOWN).getType());
            setBase(body, Attribute.MOVEMENT_SPEED, speed);
        }
    }

    /** The rider got off, or the body is gone: its old speed and jump come back and its behaviors take over again. */
    void getOff(PetControl control) {
        Unridden before = ridden.remove(control);
        if (before == null) return;
        LivingEntity body = control.body();
        if (body != null && body.isValid()) restore(body, before);
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
     * FreeMinecraftModels' invisible saddle, on a tamed body its owner owns, as a vanilla horse needs to carry a
     * steering rider. The record holds the real saddle; this one never leaves the body.
     */
    private static void putSaddleOn(LivingEntity body, UUID owner) {
        if (!(body instanceof AbstractHorse horse)) return;
        horse.setTamed(true);
        if (owner != null) {
            Player player = org.bukkit.Bukkit.getPlayer(owner);
            if (player != null) horse.setOwner(player);
        }
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
