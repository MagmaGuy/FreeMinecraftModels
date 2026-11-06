package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindHandle;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pets walking in single file. The head of a line is a pet on a walk of its own, running one of the behaviors the
 * followers name; each follower walks behind the one ahead of it, so the line goes the head's way. A follower keeps its
 * place by asking for it every tick through {@link #ahead}; one that has not asked for {@link #KEEP_TICKS} drops out,
 * and the pets behind it lose their line, as they do when the head stops leading. Server thread only.
 */
final class PetLines {
    /** How long a follower keeps its place without asking for it. */
    static final long KEEP_TICKS = 10L;
    /** How often a follower checks that its line's head still leads. */
    private static final long HEAD_CHECK_TICKS = 10L;
    /** The most pets a line walk follows, so a cycle left by places dropping at once cannot loop. */
    private static final int MAX_LINE = 64;

    /** A follower, the pet ahead of it, when it last asked for its place, and when it last checked its head. */
    private static final class Place {
        final LivingEntity self, ahead;
        long seen, headChecked;

        Place(LivingEntity self, LivingEntity ahead, long now) {
            this.self = self;
            this.ahead = ahead;
            seen = now;
            headChecked = now;
        }
    }

    // Each follower's place, by its body's id.
    private static final Map<UUID, Place> places = new HashMap<>();
    private static long sweptAt = Long.MIN_VALUE;

    private PetLines() {
    }

    /**
     * Kin: pets of one species, both wild or both of one owner, the other carrying no rider. A pet is not its own kin.
     */
    static boolean kin(PetControl pet, PetControl other) {
        if (other == null || other == pet || !other.species().id().equals(pet.species().id())) return false;
        if (other.isWild() != pet.isWild()) return false;
        if (!pet.isWild() && !pet.ownerId().equals(other.ownerId())) return false;
        LivingEntity body = other.body();
        return body != null && body.isValid() && body.isEmpty();
    }

    /**
     * Where the pet ahead of this one in its line is, after keeping its place there; with no place yet, it joins the
     * end of the line of the nearest kin within radius that runs one of the leaders behaviors. Null when it has no
     * line: none near to join, its line's head stopped leading, or the pet ahead is gone or more than radius off.
     */
    static Location ahead(PetControl pet, LivingEntity body, double radius, Set<String> leaders, long now) {
        sweep(now);
        UUID id = body.getUniqueId();
        Place place = places.get(id);
        if (place != null) {
            if (place.self == body && near(place.ahead, body, radius) && (now - place.headChecked < HEAD_CHECK_TICKS
                    || leads(pet, head(place.ahead), leaders))) {
                if (now - place.headChecked >= HEAD_CHECK_TICKS) place.headChecked = now;
                place.seen = now;
                return place.ahead.getLocation();
            }
            places.remove(id);
            return null;
        }
        if (leaders.isEmpty()) return null;
        PetManager manager = PetManager.get();
        if (manager == null) return null;
        Location here = body.getLocation();
        LivingEntity nearest = null;
        double best = radius * radius;
        for (Entity entity : body.getWorld().getNearbyEntities(here, radius, radius, radius,
                candidate -> candidate instanceof LivingEntity && candidate != body)) {
            if (places.containsKey(entity.getUniqueId())) continue;
            double distance = entity.getLocation().distanceSquared(here);
            if (distance > best) continue;
            PetControl other = manager.pet(entity);
            if (!kin(pet, other) || !runs(other, leaders)) continue;
            best = distance;
            nearest = (LivingEntity) entity;
        }
        if (nearest == null) return null;
        LivingEntity tail = tail(nearest);
        if (tail == null || !near(tail, body, radius)) return null;
        places.put(id, new Place(body, tail, now));
        return tail.getLocation();
    }

    /** Forgets every line. */
    static void clear() {
        places.clear();
        sweptAt = Long.MIN_VALUE;
    }

    /** Drops the followers that stopped asking for their places, or whose bodies went, once a tick. */
    private static void sweep(long now) {
        if (now == sweptAt) return;
        sweptAt = now;
        places.values().removeIf(place -> now - place.seen > KEEP_TICKS || !place.self.isValid());
    }

    private static boolean near(LivingEntity ahead, LivingEntity body, double radius) {
        return ahead.isValid() && !ahead.isDead() && ahead.getWorld() == body.getWorld()
                && ahead.getLocation().distanceSquared(body.getLocation()) <= radius * radius;
    }

    /** The front of the line this pet is in: the first pet ahead with no place of its own; null past MAX_LINE. */
    private static LivingEntity head(LivingEntity from) {
        LivingEntity at = from;
        for (int step = 0; step < MAX_LINE; step++) {
            Place place = places.get(at.getUniqueId());
            if (place == null) return at;
            at = place.ahead;
        }
        return null;
    }

    /** The back of the line this head leads: the last pet following it, or the head alone; null past MAX_LINE. */
    private static LivingEntity tail(LivingEntity head) {
        LivingEntity at = head;
        for (int step = 0; step < MAX_LINE; step++) {
            LivingEntity behind = null;
            for (Place place : places.values()) {
                if (place.ahead == at) {
                    behind = place.self;
                    break;
                }
            }
            if (behind == null) return at;
            at = behind;
        }
        return null;
    }

    /** Whether the head is kin of the follower, alive, and runs one of the leaders behaviors. */
    private static boolean leads(PetControl pet, LivingEntity head, Set<String> leaders) {
        PetManager manager = PetManager.get();
        if (head == null || manager == null || !head.isValid()) return false;
        PetControl control = manager.pet(head);
        return kin(pet, control) && runs(control, leaders);
    }

    /**
     * Whether the pet's Mind runs one of these behaviors now. Asked of every kin in reach when a pet looks for a line,
     * so it asks the Mind directly rather than building a snapshot of it.
     */
    private static boolean runs(PetControl control, Set<String> behaviors) {
        MindHandle mind = control.mind();
        if (mind == null) return false;
        for (String behavior : behaviors) if (mind.isRunning(behavior)) return true;
        return false;
    }
}
