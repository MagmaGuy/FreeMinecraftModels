package com.magmaguy.freeminecraftmodels.debug;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketSendObserver;
import com.magmaguy.easyminecraftgoals.internal.PacketSizeEstimator;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-shot, version-agnostic per-tick packet sampler for modeled entities.
 *
 * <p>The bundler ({@code PacketBundle.send()} in EasyMinecraftGoals) collapses a
 * tick's packets into one {@code ClientboundBundlePacket} per player, so the wire
 * <i>packet</i> count looks fine — but the bundle <i>payload</i> is the sum of every
 * bone's move + metadata packet, every tick, for every visible model. That payload
 * (and the client-side deserialization cost) is what saturates connections in dense
 * NPC areas. This class makes that payload measurable.</p>
 *
 * <p>How it works: when armed, {@link com.magmaguy.freeminecraftmodels.customentity.ModeledEntitiesClock}
 * wraps the real packet bundle in a {@link CountingPacketBundle} that tallies every
 * {@code addPacket(packet, viewers)} call — total packets, packets <i>delivered</i>
 * (summed across viewers), a breakdown by packet type, and a per-player count. After
 * the configured number of ticks the aggregate is frozen into {@link Sample} for the
 * command to print.</p>
 *
 * <p>Counts are <b>exact</b> (they tally the real addPacket calls). Byte figures are
 * <b>rough estimates</b> from {@link #estimateBytes(String)} — getting the true wire
 * size would require re-serializing each NMS packet through the player's connection
 * codec, which is fragile across versions. The packet <i>count</i> per player per tick
 * is the number that actually explains client saturation, so that's what to watch.</p>
 *
 * <p>Threading: arming happens from the command thread; counting happens on the async
 * model clock thread. Arming/result handoff uses {@code volatile} fields and a
 * {@code synchronized} block; the per-tick wrapper is a fresh object each tick so there
 * is no shared mutable counting state between ticks.</p>
 */
public final class PacketDiagnostics {

    private PacketDiagnostics() {
    }

    private static volatile int armedTicks = 0;
    private static volatile Accumulator current = null;
    private static volatile Sample lastSample = null;
    // The tick's counting wrapper, exposed during a sampled tick so direct (unbundled) sends —
    // observed via PacketSendObserver — fold into the same tick's tally. Null when not sampling.
    private static volatile CountingPacketBundle directSink = null;

    static {
        // Register once so the NMS layer can report direct (unbundled) sends to us. No-op unless
        // a sample is in progress (directSink != null), so the steady-state cost is one volatile read.
        try {
            PacketSendObserver.setImpl(PacketDiagnostics::recordDirectSend);
        } catch (Throwable ignored) {
        }
    }

    /** Routes one observed direct broadcast into the current sampled tick, if any. */
    public static void recordDirectSend(Object packet, int viewerCount) {
        CountingPacketBundle sink = directSink;
        if (sink != null) sink.recordDirect(packet, viewerCount);
    }

    /** Clock calls these to bracket the window during which direct sends should be captured. */
    public static void beginDirectCapture(CountingPacketBundle bundle) {
        directSink = bundle;
    }

    public static void endDirectCapture() {
        directSink = null;
    }

    /**
     * Arms the sampler for the next {@code ticks} model-clock ticks.
     *
     * @param ticks number of ticks to sample (clamped to at least 1)
     */
    public static synchronized void arm(int ticks) {
        if (ticks < 1) ticks = 1;
        current = new Accumulator();
        armedTicks = ticks;
    }

    /**
     * @return true if a sample is currently in progress (checked by the clock each tick)
     */
    public static boolean isArmed() {
        return armedTicks > 0;
    }

    /**
     * Wraps the real bundle so the next tick's packets get counted. Only call when
     * {@link #isArmed()} returned true.
     */
    public static CountingPacketBundle wrap(AbstractPacketBundle real) {
        return new CountingPacketBundle(real);
    }

    /**
     * Folds one tick's counts into the running sample. Called by the clock after
     * {@code bundle.send()}.
     *
     * @param wrapper            the counting wrapper used for this tick
     * @param loadedEntityCount  number of modeled entities loaded this tick (context)
     */
    public static synchronized void endTick(CountingPacketBundle wrapper, int loadedEntityCount) {
        Accumulator acc = current;
        if (acc == null) return;
        acc.addTick(wrapper, loadedEntityCount);
        armedTicks--;
        if (armedTicks <= 0) {
            lastSample = acc.finish();
            current = null;
        }
    }

    /**
     * @return the most recently completed sample, or null if none has finished yet
     */
    public static Sample getLastSample() {
        return lastSample;
    }

    /**
     * Rough per-packet wire-size estimate keyed on the NMS packet's simple class name.
     * Substring matching keeps it stable across mapping/version churn. These are
     * deliberately conservative ballparks, NOT exact wire sizes.
     */
    static int estimateBytes(String packetSimpleName) {
        if (packetSimpleName == null) return 24;
        if (packetSimpleName.contains("Teleport")) return 60;        // pos(3 doubles)+delta+rot+flags
        if (packetSimpleName.contains("SetEntityData")) return 100;  // dominated by the item-display blob
        if (packetSimpleName.contains("AddEntity")) return 32;
        if (packetSimpleName.contains("SetEquipment")) return 30;
        if (packetSimpleName.contains("RemoveEntities")) return 20;
        if (packetSimpleName.contains("Bundle")) return 0;           // framing only
        return 24;
    }

    // ------------------------------------------------------------------
    // Counting wrapper
    // ------------------------------------------------------------------

    /**
     * Decorates a real {@link AbstractPacketBundle}, forwarding every call unchanged
     * while tallying packets. Only ever installed for a tick that is being sampled, so
     * the hot path pays nothing when the sampler is disarmed.
     */
    public static final class CountingPacketBundle implements AbstractPacketBundle {
        private final AbstractPacketBundle delegate;

        // entries = addPacket calls (logical packets queued this tick)
        long entries = 0;
        // deliveries = sum over packets of viewer count (packets actually pushed to sockets)
        long deliveries = 0;
        long estimatedBytes = 0;
        long realMeasuredPackets = 0;  // entries whose size came from real NMS serialization
        long estimatedPackets = 0;     // entries whose size came from the flat fallback estimate
        // Direct (unbundled) sends captured via PacketSendObserver — a subset of the totals above.
        long directDeliveries = 0;
        long directBytes = 0;
        final Map<String, long[]> typeCounts = new HashMap<>();   // simpleName -> {entries, deliveries}
        final Map<UUID, long[]> perPlayer = new HashMap<>();      // uuid -> {packets, bytes}
        final Map<UUID, String> playerNames = new HashMap<>();

        CountingPacketBundle(AbstractPacketBundle delegate) {
            this.delegate = delegate;
        }

        /**
         * Records a direct (unbundled) broadcast observed via {@link PacketSendObserver}. Unlike
         * {@link #addPacket}, we only have a viewer count (not the player list), so these fold into
         * the totals and type breakdown but not the per-player table.
         */
        void recordDirect(Object packet, int viewerCount) {
            if (packet == null || viewerCount <= 0) return;
            String type = packet.getClass().getSimpleName();
            int real = PacketSizeEstimator.sizeOf(packet);
            int est;
            if (real >= 0) {
                est = real;
                realMeasuredPackets++;
            } else {
                est = estimateBytes(type);
                estimatedPackets++;
            }
            long bytes = (long) est * viewerCount;
            entries++;
            deliveries += viewerCount;
            estimatedBytes += bytes;
            directDeliveries += viewerCount;
            directBytes += bytes;
            long[] tc = typeCounts.computeIfAbsent(type, k -> new long[2]);
            tc[0]++;
            tc[1] += viewerCount;
        }

        @Override
        public void addPacket(Object packet, List<Player> viewers) {
            // Real behavior first — counting must never change what gets sent.
            delegate.addPacket(packet, viewers);

            if (packet == null || viewers == null || viewers.isEmpty()) return;
            int v = viewers.size();
            String type = packet.getClass().getSimpleName();

            // Prefer the active NMS module's real serialized size; fall back to a flat estimate.
            int real = PacketSizeEstimator.sizeOf(packet);
            int est;
            if (real >= 0) {
                est = real;
                realMeasuredPackets++;
            } else {
                est = estimateBytes(type);
                estimatedPackets++;
            }

            entries++;
            deliveries += v;
            estimatedBytes += (long) est * v;

            long[] tc = typeCounts.computeIfAbsent(type, k -> new long[2]);
            tc[0]++;
            tc[1] += v;

            for (Player p : viewers) {
                if (p == null) continue;
                UUID id = p.getUniqueId();
                long[] pc = perPlayer.computeIfAbsent(id, k -> new long[2]);
                pc[0]++;
                pc[1] += est;
                playerNames.putIfAbsent(id, p.getName());
            }
        }

        @Override
        public void send() {
            delegate.send();
        }
    }

    // ------------------------------------------------------------------
    // Aggregation across the sampled ticks
    // ------------------------------------------------------------------

    private static final class Accumulator {
        int ticks = 0;
        long entries = 0;
        long deliveries = 0;
        long estimatedBytes = 0;
        long realMeasuredPackets = 0;
        long estimatedPackets = 0;
        long directDeliveries = 0;
        long directBytes = 0;
        long loadedEntitiesSum = 0;
        long peakTickDeliveries = 0;
        long peakSinglePlayerTickPackets = 0;
        String peakSinglePlayerName = "";
        final Map<String, long[]> typeTotals = new HashMap<>();
        final Map<UUID, long[]> playerTotals = new HashMap<>();
        final Map<UUID, String> playerNames = new HashMap<>();

        void addTick(CountingPacketBundle w, int loadedEntityCount) {
            ticks++;
            entries += w.entries;
            deliveries += w.deliveries;
            estimatedBytes += w.estimatedBytes;
            realMeasuredPackets += w.realMeasuredPackets;
            estimatedPackets += w.estimatedPackets;
            directDeliveries += w.directDeliveries;
            directBytes += w.directBytes;
            loadedEntitiesSum += loadedEntityCount;
            peakTickDeliveries = Math.max(peakTickDeliveries, w.deliveries);

            w.typeCounts.forEach((name, c) -> {
                long[] t = typeTotals.computeIfAbsent(name, k -> new long[2]);
                t[0] += c[0];
                t[1] += c[1];
            });
            w.perPlayer.forEach((id, c) -> {
                long[] t = playerTotals.computeIfAbsent(id, k -> new long[2]);
                t[0] += c[0];
                t[1] += c[1];
                if (c[0] > peakSinglePlayerTickPackets) {
                    peakSinglePlayerTickPackets = c[0];
                    peakSinglePlayerName = w.playerNames.getOrDefault(id, id.toString());
                }
            });
            w.playerNames.forEach(playerNames::putIfAbsent);
        }

        Sample finish() {
            return new Sample(this);
        }
    }

    /**
     * Immutable, formatted result of a completed sample. {@link #toLines()} returns the
     * lines the command prints to the sender.
     */
    public static final class Sample {
        private final List<String> lines;

        private Sample(Accumulator a) {
            this.lines = build(a);
        }

        public List<String> toLines() {
            return lines;
        }

        private static List<String> build(Accumulator a) {
            List<String> out = new ArrayList<>();
            int ticks = Math.max(1, a.ticks);
            double perTick = a.deliveries / (double) ticks;
            double bytesPerTick = a.estimatedBytes / (double) ticks;
            double bytesPerSec = bytesPerTick * 20.0;

            out.add("&6&l===== FMM Packet Sample =====");
            out.add("&7Ticks sampled: &f" + a.ticks
                    + "  &7avg loaded models: &f" + (a.loadedEntitiesSum / ticks));
            out.add("&7Logical packets/tick (in bundle): &f" + (a.entries / ticks));
            out.add("&7Packets DELIVERED/tick (x viewers): &e" + String.format("%.0f", perTick)
                    + "  &7peak tick: &c" + a.peakTickDeliveries);
            String sizeSource;
            if (a.estimatedPackets == 0 && a.realMeasuredPackets > 0) sizeSource = "&8(measured)";
            else if (a.realMeasuredPackets == 0) sizeSource = "&8(estimate)";
            else sizeSource = "&8(measured + some estimated)";
            out.add("&7Payload/tick: &e" + humanBytes((long) bytesPerTick)
                    + "  &7~/sec: &c" + humanBytes((long) bytesPerSec) + "/s " + sizeSource);
            out.add("&7  of which UNBUNDLED direct sends/tick: &e" + (a.directDeliveries / ticks)
                    + " &7pkts · " + humanBytes(a.directBytes / ticks)
                    + " &8(0 is good — these bypass the bundler)");
            out.add("&7Worst single player in one tick: &c" + a.peakSinglePlayerTickPackets
                    + " packets &7(&f" + a.peakSinglePlayerName + "&7)");

            out.add("&6Packet types (delivered total):");
            a.typeTotals.entrySet().stream()
                    .sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]))
                    .limit(8)
                    .forEach(e -> out.add("  &8- &f" + e.getKey()
                            + " &7x&f" + e.getValue()[1]
                            + " &8(" + (e.getValue()[1] / ticks) + "/tick)"));

            out.add("&6Top viewers (packets/tick · est bytes/tick):");
            a.playerTotals.entrySet().stream()
                    .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
                    .limit(8)
                    .forEach(e -> {
                        String name = a.playerNames.getOrDefault(e.getKey(), e.getKey().toString());
                        long pkts = e.getValue()[0] / ticks;
                        long bytes = e.getValue()[1] / ticks;
                        out.add("  &8- &f" + name + "&7: &e" + pkts + " &7pkts/tick · &e"
                                + humanBytes(bytes) + "&7/tick");
                    });

            if (a.deliveries == 0) {
                out.add("&8(No model packets were sent during the sample — stand near modeled entities.)");
            }
            return out;
        }

        private static String humanBytes(long bytes) {
            if (bytes < 1024) return bytes + "B";
            double kb = bytes / 1024.0;
            if (kb < 1024) return String.format("%.1fKB", kb);
            return String.format("%.2fMB", kb / 1024.0);
        }
    }
}
