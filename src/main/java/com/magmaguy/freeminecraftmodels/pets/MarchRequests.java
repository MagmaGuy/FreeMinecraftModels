package com.magmaguy.freeminecraftmodels.pets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The marches admins asked for with /fmm petadmin march, and the one answer each sender gets.
 * <p>
 * A request waits for its pet until its time is up. The pet's march module calls each time it is free and asked, about
 * twice a second, and most calls of a request that cannot work fail the same way, so a failed call is only kept. The
 * sender is told once: that the march started, on the tick it does, or, when the request runs out, why the latest call
 * came to nothing, or why the pet never got to call. A request with no sender is kept and tells nobody. Server thread
 * only.
 */
public final class MarchRequests {
    /** How long a request waits for its pet to call, in ticks: 20 seconds. */
    static final long WAIT_TICKS = 400L;

    /** What to tell whom. */
    record Answer(UUID sender, String message) {
    }

    private static final class Request {
        final UUID sender;
        final String species;
        final long end;
        // Why the pet's latest call came to nothing; null while it has not called.
        String refusal;

        Request(UUID sender, String species, long end) {
            this.sender = sender;
            this.species = species;
            this.end = end;
        }
    }

    // Each waiting request, by its pet's body.
    private final Map<UUID, Request> byPet = new HashMap<>();

    // ---- what the sender is told ---------------------------------------------------------------------------------

    /**
     * The command's own reply: the pet was asked, what its march needs, and how long the answer may take. The numbers
     * are the pet's own {@code min_group} and {@code radius}, from the settings its march module runs with, as
     * c.pet:march_call takes them: a group of 2 to {@link PetMarches#MAX_MARCHERS} within up to
     * {@link PetLuaTable#MAX_KIN_RADIUS} blocks. A march module without those settings gets no numbers.
     */
    public static String asked(String species, Map<String, Object> marchSettings) {
        String told = " You are told how it went within " + seconds(WAIT_TICKS) + " seconds.";
        if (!(marchSettings.get("min_group") instanceof Number group)
                || !(marchSettings.get("radius") instanceof Number radius))
            return "&aAsked a wild " + species + " to call a march." + told;
        int needed = (int) Math.max(2D, Math.min(PetMarches.MAX_MARCHERS, group.doubleValue()));
        double reach = Math.max(0D, Math.min(PetLuaTable.MAX_KIN_RADIUS, radius.doubleValue()));
        return "&aAsked a wild " + species + " to call a march. It calls one once it is free, with " + needed
                + " of its kind within " + blocks(reach) + " blocks, itself included, and clear ground to set off over."
                + told;
    }

    static String started(String species, int marchers) {
        return "&aThe wild " + species + " called a march: " + marchers + " are in line.";
    }

    /**
     * Too few to set off: {@code near} of its kind were within the radius and {@code free} of them could march, the
     * caller counted in both, where the march needs {@code needed}.
     */
    static String tooFew(String species, int free, int near, int needed, double radius) {
        String found = "&cNo march. The wild " + species + " found " + near + " of its kind within " + blocks(radius)
                + " blocks, itself included, ";
        if (near <= free) return found + "and a march needs " + needed + ".";
        return found + "but only " + free + " were free to march, and a march needs " + needed
                + ". The others were busy or in another march.";
    }

    static String noGround(String species, double minLeg) {
        return "&cNo march. The wild " + species + " has no clear ground to set off over: every way from where it"
                + " stands is shut within " + blocks(minLeg) + " blocks by a wall, a drop, water or leaves.";
    }

    static String notFree(String species) {
        return "&cNo march. The wild " + species + " was not free to call one in " + seconds(WAIT_TICKS)
                + " seconds: something that comes before a march held it, such as a fight or a fish on the ground.";
    }

    static String onAMarch(String species) {
        return "&cNo new march. The wild " + species + " was on a march already for the whole " + seconds(WAIT_TICKS)
                + " seconds.";
    }

    static String gone(String species) {
        return "&cNo march. The wild " + species + " that was asked is gone: it died, was tamed or its chunk unloaded.";
    }

    /**
     * Nobody to ask within {@code blocks}: no wild adult, of the species when one was named, whose behaviors run a
     * march. The wild pets that were there and could not be asked are counted by why.
     */
    public static String noneNear(String species, int blocks, int babies, int migrants, int unmarching) {
        String none = "&cNo wild adult " + (species == null ? "pet" : species) + " with the march behavior is within "
                + blocks + " blocks.";
        List<String> there = new ArrayList<>();
        if (babies > 0) there.add(babies + (babies == 1 ? " baby" : " babies"));
        if (migrants > 0) there.add(migrants + " on a migration");
        if (unmarching > 0) there.add(unmarching + " without the march behavior");
        return there.isEmpty() ? none : none + " The wild ones there: " + String.join(", ", there) + ".";
    }

    /** A distance as a pet file writes it: 12, or 16.5. */
    private static String blocks(double distance) {
        return distance == Math.rint(distance) ? String.valueOf((long) distance)
                : String.format(Locale.ROOT, "%.1f", distance);
    }

    private static long seconds(long ticks) {
        return ticks / 20L;
    }

    // ---- the requests ------------------------------------------------------------------------------------------

    /**
     * The sender asks this pet, of this species, for a march at the tick {@code now}. The request waits
     * {@link #WAIT_TICKS}; an earlier one for the same pet is replaced, and its wait starts over.
     */
    void ask(UUID pet, UUID sender, String species, long now) {
        byPet.put(pet, new Request(sender, species, now + WAIT_TICKS));
    }

    /** Whether this pet was asked for a march and the request still waits. */
    boolean waits(UUID pet, long now) {
        Request request = byPet.get(pet);
        return request != null && now < request.end;
    }

    /** The pet called and no march came of it: kept for the answer, in place of an earlier call's reason. */
    void refused(UUID pet, String why) {
        Request request = byPet.get(pet);
        if (request != null) request.refusal = why;
    }

    /** The pet's march started: its request is over. The answer for its sender, or null with nobody to tell. */
    Answer started(UUID pet, String message) {
        Request request = byPet.remove(pet);
        return request == null || request.sender == null ? null : new Answer(request.sender, message);
    }

    /**
     * Ends the requests whose time is up, and returns the answers for their senders: why the pet's latest call came
     * to nothing, or, for a pet that never called, what {@code idle} says about that pet of that species.
     */
    List<Answer> runOut(long now, BiFunction<UUID, String, String> idle) {
        if (byPet.isEmpty()) return List.of();
        List<Answer> answers = null;
        for (Iterator<Map.Entry<UUID, Request>> each = byPet.entrySet().iterator(); each.hasNext(); ) {
            Map.Entry<UUID, Request> entry = each.next();
            Request request = entry.getValue();
            if (now < request.end) continue;
            each.remove();
            if (request.sender == null) continue;
            if (answers == null) answers = new ArrayList<>();
            answers.add(new Answer(request.sender, request.refusal != null ? request.refusal
                    : idle.apply(entry.getKey(), request.species)));
        }
        return answers == null ? List.of() : answers;
    }

    /** Forgets every request, telling nobody. */
    void clear() {
        byPet.clear();
    }
}
