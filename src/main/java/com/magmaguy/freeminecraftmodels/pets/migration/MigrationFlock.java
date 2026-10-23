package com.magmaguy.freeminecraftmodels.pets.migration;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.easyminecraftgoals.thirdparty.BedrockChecker;
import com.magmaguy.freeminecraftmodels.customentity.StaticEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.pets.PetSpecies;
import com.magmaguy.freeminecraftmodels.pets.interactions.PetInteractions;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One migrating flock: a few creatures crossing the sky along a straight path that passes over a player halfway
 * through its flight. Nothing in it is a mob. Members move as boids (they keep apart, match their neighbours'
 * velocity, close on the flock's middle and on its moving anchor, and hold the shared heading) under one task that
 * runs every tick, and they are lit fully bright. A member is either a whole FreeMinecraftModels model, which FMM
 * shows and batches with its other models, or, when the species names a bone, one packet display of that bone,
 * which this flock batches into one bundle per viewer per tick and shows only to Java players within view.
 */
public final class MigrationFlock {
    // Boid weights per tick; distances in blocks.
    private static final double NEIGHBOUR_RADIUS = 7D, SEPARATION_RADIUS = 2.5D;
    private static final double SEPARATION = 0.035D, ALIGNMENT = 0.05D, COHESION = 0.0015D, ANCHOR = 0.0025D,
            HEADING = 0.06D, ALTITUDE = 0.01D;
    private static final double BOB_HEIGHT = 1.5D, BOB_PERIOD_TICKS = 140D, PULSE_PERIOD_TICKS = 45D;
    private static final int VIEWER_REFRESH_TICKS = 10;

    private interface Member {
        void place(Location at, double pulse, AbstractPacketBundle bundle);

        default void show(Player player) {
        }

        default void hide(UUID player) {
        }

        void remove();
    }

    private final World world;
    private final Vector heading;
    private final Vector anchorStart;
    private final double speed;
    private final double altitude;
    private final int durationTicks;
    private final int viewDistance;
    private final Member[] members;
    private final double[] x, y, z, vx, vy, vz, phase;
    private final boolean drawsBones;
    private final Set<UUID> viewers = new HashSet<>();
    private final NMSAdapter adapter;
    private BukkitTask task;
    private int age;
    private boolean done;

    private MigrationFlock(World world, Vector heading, Vector anchorStart, double speed, double altitude,
                           int durationTicks, int viewDistance, int size, boolean drawsBones, NMSAdapter adapter) {
        this.world = world;
        this.heading = heading;
        this.anchorStart = anchorStart;
        this.speed = speed;
        this.altitude = altitude;
        this.durationTicks = durationTicks;
        this.viewDistance = viewDistance;
        this.members = new Member[size];
        this.x = new double[size];
        this.y = new double[size];
        this.z = new double[size];
        this.vx = new double[size];
        this.vy = new double[size];
        this.vz = new double[size];
        this.phase = new double[size];
        this.drawsBones = drawsBones;
        this.adapter = adapter;
    }

    /** Puts a flock of the species in the air on a path over the player, or returns null when it cannot be drawn. */
    static MigrationFlock launch(Plugin plugin, Player player, PetSpecies species, PetInteractions.Migration look,
                                 PetMigrations.Settings settings) {
        NMSAdapter adapter = NMSManager.getAdapter();
        if (adapter == null || FileModelConverter.getModel(look.model()) == null) {
            Logger.warn("A " + species.id() + " migration could not start: model " + look.model() + " is not loaded.");
            return null;
        }
        String boneModel = look.bone() == null || VersionChecker.serverVersionOlderThan(21, 4) ? null : boneModelId(look);
        if (look.bone() != null && boneModel == null)
            Logger.warn("Migration bone " + look.bone() + " is not a visible bone of " + look.model() + "; drawing the whole model.");
        World world = player.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble() * Math.PI * 2D;
        Vector heading = new Vector(Math.cos(angle), 0D, Math.sin(angle));
        Location over = player.getLocation();
        int ground = world.getHighestBlockYAt(over.getBlockX(), over.getBlockZ(), HeightMap.MOTION_BLOCKING);
        double height = Math.min(world.getMaxHeight() - 4D, Math.max(over.getY(), ground) + settings.altitude());
        // Halfway through the flight the anchor passes over the player; it starts inside the view distance.
        double approach = Math.min(settings.speed() * settings.durationTicks() / 2D, settings.viewDistance() * 0.9D);
        Vector start = over.toVector().subtract(heading.clone().multiply(approach)).setY(height);
        int size = settings.minFlockSize() + random.nextInt(settings.maxFlockSize() - settings.minFlockSize() + 1);
        MigrationFlock flock = new MigrationFlock(world, heading, start, settings.speed(), height,
                settings.durationTicks(), settings.viewDistance(), size, boneModel != null, adapter);
        double spread = 2D + 1.2D * Math.sqrt(size);
        double scale = species.scale() * look.scale();
        for (int index = 0; index < size; index++) {
            double around = random.nextDouble() * Math.PI * 2D, out = Math.sqrt(random.nextDouble()) * spread;
            flock.x[index] = start.getX() + Math.cos(around) * out;
            flock.y[index] = height + (random.nextDouble() - 0.5D) * 4D;
            flock.z[index] = start.getZ() + Math.sin(around) * out;
            double pace = settings.speed() * (0.9D + random.nextDouble() * 0.2D);
            flock.vx[index] = heading.getX() * pace;
            flock.vz[index] = heading.getZ() * pace;
            flock.phase[index] = random.nextDouble() * Math.PI * 2D;
            Location at = flock.location(index);
            Member member = boneModel != null
                    ? new BoneMember(adapter, at, boneModel, scale, settings.viewDistance())
                    : ModelMember.spawn(look, at, scale, settings.viewDistance());
            if (member == null) {
                flock.remove();
                Logger.warn("A " + species.id() + " migration could not start: model " + look.model() + " did not spawn.");
                return null;
            }
            flock.members[index] = member;
        }
        flock.task = Bukkit.getScheduler().runTaskTimer(plugin, flock::tick, 1L, 1L);
        return flock;
    }

    private static String boneModelId(PetInteractions.Migration look) {
        FileModelConverter model = FileModelConverter.getModel(look.model());
        if (model == null || model.getSkeletonBlueprint() == null) return null;
        for (BoneBlueprint bone : model.getSkeletonBlueprint().getBoneMap().values()) {
            if (!look.bone().equalsIgnoreCase(bone.getOriginalBoneName()) && !look.bone().equalsIgnoreCase(bone.getBoneName()))
                continue;
            return bone.isDisplayModel() ? bone.getModelID() : null;
        }
        return null;
    }

    public boolean isDone() {
        return done;
    }

    /** Whether the flock's middle is within the distance of the location. */
    boolean isNear(Location location, double distance) {
        if (done || location.getWorld() != world) return false;
        Vector middle = middle();
        double dx = middle.getX() - location.getX(), dz = middle.getZ() - location.getZ();
        return dx * dx + dz * dz <= distance * distance;
    }

    private Location location(int index) {
        Location at = new Location(world, x[index], y[index], z[index]);
        Vector velocity = new Vector(vx[index], vy[index], vz[index]);
        if (velocity.lengthSquared() > 1.0E-6D) at.setDirection(velocity);
        return at;
    }

    private Vector middle() {
        double sumX = 0D, sumY = 0D, sumZ = 0D;
        for (int index = 0; index < members.length; index++) {
            sumX += x[index];
            sumY += y[index];
            sumZ += z[index];
        }
        return new Vector(sumX / members.length, sumY / members.length, sumZ / members.length);
    }

    private void tick() {
        if (done) return;
        age++;
        if (age > durationTicks || world.getPlayers().isEmpty()) {
            remove();
            return;
        }
        steer();
        AbstractPacketBundle bundle = drawsBones ? adapter.createPacketBundle() : null;
        if (drawsBones && age % VIEWER_REFRESH_TICKS == 1) refreshViewers();
        for (int index = 0; index < members.length; index++) {
            double pulse = Math.sin(age * Math.PI * 2D / PULSE_PERIOD_TICKS + phase[index]);
            members[index].place(location(index), pulse, bundle);
        }
        if (bundle != null) bundle.send();
    }

    /** One boid step for every member. */
    private void steer() {
        double anchorX = anchorStart.getX() + heading.getX() * speed * age;
        double anchorZ = anchorStart.getZ() + heading.getZ() * speed * age;
        double maxAcceleration = speed * 0.08D;
        int size = members.length;
        double[] ax = new double[size], ay = new double[size], az = new double[size];
        for (int self = 0; self < size; self++) {
            double sepX = 0D, sepY = 0D, sepZ = 0D, aliX = 0D, aliY = 0D, aliZ = 0D, cohX = 0D, cohY = 0D, cohZ = 0D;
            int neighbours = 0;
            for (int other = 0; other < size; other++) {
                if (other == self) continue;
                double dx = x[self] - x[other], dy = y[self] - y[other], dz = z[self] - z[other];
                double distanceSquared = dx * dx + dy * dy + dz * dz;
                if (distanceSquared > NEIGHBOUR_RADIUS * NEIGHBOUR_RADIUS) continue;
                neighbours++;
                aliX += vx[other];
                aliY += vy[other];
                aliZ += vz[other];
                cohX += x[other];
                cohY += y[other];
                cohZ += z[other];
                if (distanceSquared < SEPARATION_RADIUS * SEPARATION_RADIUS && distanceSquared > 1.0E-6D) {
                    sepX += dx / distanceSquared;
                    sepY += dy / distanceSquared;
                    sepZ += dz / distanceSquared;
                }
            }
            double accX = sepX * SEPARATION, accY = sepY * SEPARATION, accZ = sepZ * SEPARATION;
            if (neighbours > 0) {
                accX += (aliX / neighbours - vx[self]) * ALIGNMENT + (cohX / neighbours - x[self]) * COHESION;
                accY += (aliY / neighbours - vy[self]) * ALIGNMENT + (cohY / neighbours - y[self]) * COHESION;
                accZ += (aliZ / neighbours - vz[self]) * ALIGNMENT + (cohZ / neighbours - z[self]) * COHESION;
            }
            // The anchor keeps the flock on its path; the heading keeps it travelling.
            accX += (anchorX - x[self]) * ANCHOR + (heading.getX() * speed - vx[self]) * HEADING;
            accZ += (anchorZ - z[self]) * ANCHOR + (heading.getZ() * speed - vz[self]) * HEADING;
            double bob = Math.sin(age * Math.PI * 2D / BOB_PERIOD_TICKS + phase[self]) * BOB_HEIGHT;
            accY += (altitude + bob - y[self]) * ALTITUDE - vy[self] * 0.1D;
            double magnitude = Math.sqrt(accX * accX + accY * accY + accZ * accZ);
            if (magnitude > maxAcceleration) {
                double shrink = maxAcceleration / magnitude;
                accX *= shrink;
                accY *= shrink;
                accZ *= shrink;
            }
            ax[self] = accX;
            ay[self] = accY;
            az[self] = accZ;
        }
        for (int index = 0; index < size; index++) {
            vx[index] += ax[index];
            vy[index] += ay[index];
            vz[index] += az[index];
            double pace = Math.sqrt(vx[index] * vx[index] + vy[index] * vy[index] + vz[index] * vz[index]);
            double clamped = Math.max(speed * 0.6D, Math.min(speed * 1.4D, pace));
            if (pace > 1.0E-6D && clamped != pace) {
                double ratio = clamped / pace;
                vx[index] *= ratio;
                vy[index] *= ratio;
                vz[index] *= ratio;
            }
            x[index] += vx[index];
            y[index] += vy[index];
            z[index] += vz[index];
        }
    }

    /** Java players within view of the flock's middle see its bone displays; Bedrock clients cannot draw them. */
    private void refreshViewers() {
        Vector middle = middle();
        double range = (double) viewDistance * viewDistance;
        Set<UUID> now = new HashSet<>();
        for (Player player : world.getPlayers()) {
            if (BedrockChecker.isBedrock(player)) continue;
            Location at = player.getLocation();
            double dx = at.getX() - middle.getX(), dy = at.getY() - middle.getY(), dz = at.getZ() - middle.getZ();
            if (dx * dx + dy * dy + dz * dz > range) continue;
            now.add(player.getUniqueId());
            if (viewers.contains(player.getUniqueId())) continue;
            for (Member member : members) member.show(player);
        }
        for (UUID viewer : viewers) {
            if (now.contains(viewer)) continue;
            for (Member member : members) member.hide(viewer);
        }
        viewers.clear();
        viewers.addAll(now);
    }

    /** Takes the whole flock out of the sky. */
    public void remove() {
        if (done) return;
        done = true;
        if (task != null) task.cancel();
        for (Member member : members) if (member != null) member.remove();
        viewers.clear();
    }

    /** A whole model, lit fully bright and shown farther than models usually are. FMM handles its viewers. */
    private static final class ModelMember implements Member {
        private final StaticEntity model;

        private ModelMember(StaticEntity model) {
            this.model = model;
        }

        static ModelMember spawn(PetInteractions.Migration look, Location at, double scale, int viewDistance) {
            StaticEntity model = StaticEntity.create(look.model(), at);
            if (model == null) return null;
            model.setViewDistanceOverride(viewDistance);
            model.setBrightnessOverride(15, 15);
            model.setScaleModifier(scale);
            // Without a named animation the model's own idle and walk follow its motion.
            if (look.animation() != null) model.playAnimation(look.animation(), false, true);
            return new ModelMember(model);
        }

        @Override
        public void place(Location at, double pulse, AbstractPacketBundle bundle) {
            if (!model.isRemoved()) model.teleport(at, false);
        }

        @Override
        public void remove() {
            if (!model.isRemoved()) model.remove();
        }
    }

    /**
     * One packet display carrying a single bone's item model: the cheapest member, for a rig too heavy to fly in
     * numbers. Its bell-like pulse is a squash and stretch of its scale.
     */
    private static final class BoneMember implements Member {
        // FMM draws bone item models at 2.5 times their resource pack size.
        private static final double BONE_SCALE = 2.5D;
        private final PacketModelEntity display;
        private final double scale;

        private BoneMember(NMSAdapter adapter, Location at, String modelId, double scale, int viewDistance) {
            this.scale = scale * BONE_SCALE;
            display = adapter.createPacketDisplayEntity(at);
            display.initializeModel(at, modelId);
            display.setBrightness(15, 15);
            // View range counts in steps of 64 blocks; the bone default already reaches far, never lower it.
            display.setViewRange(Math.max(30F, viewDistance / 64F + 1F));
            display.setTeleportDuration(2);
        }

        @Override
        public void place(Location at, double pulse, AbstractPacketBundle bundle) {
            if (!display.hasViewers()) return;
            float stretch = (float) (scale * (1D + 0.08D * pulse));
            float squash = (float) (scale * (1D - 0.04D * pulse));
            // Bone displays face yaw 180 and turn by the negative model yaw, as FMM places its own bones.
            Location placed = at.clone();
            placed.setYaw(180F);
            placed.setPitch(0F);
            EulerAngle rotation = new EulerAngle(Math.toRadians(at.getPitch()) * 0.3D, -Math.toRadians(at.getYaw() + 180F), 0D);
            display.generateLocationAndRotationAndScalePackets(bundle, placed, rotation, squash, stretch, squash);
        }

        @Override
        public void show(Player player) {
            display.displayTo(player.getUniqueId());
        }

        @Override
        public void hide(UUID player) {
            display.hideFrom(player);
        }

        @Override
        public void remove() {
            display.remove();
        }
    }
}
