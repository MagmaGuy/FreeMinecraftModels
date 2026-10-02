package com.magmaguy.freeminecraftmodels.particles.runtime;

import com.magmaguy.freeminecraftmodels.particles.molang.MolangContext;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint.*;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.SnowstormParser;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Simulates one Snowstorm emitter the way Blockbench's Wintersky preview does: emitter variables,
 * live particles, then spawning, then the emitter lifetime. Shape offsets and initial velocity
 * follow the emitter's rotation at spawn time. Not thread-safe; one owner ticks it.
 */
public final class ParticleEmitter {
    private static final double EPSILON = 1e-9;

    private enum State {ACTIVE, SLEEPING, STOPPED}

    private final ParticleEffectBlueprint effect;
    private final RandomGenerator random;
    private final int maxParticlesCap;
    private final MolangContext context;
    private final List<SimulatedParticle> particles = new ArrayList<>();
    private final List<SimulatedParticle> expired = new ArrayList<>();
    private final Vector3d scratch = new Vector3d();
    private State state = State.STOPPED;
    private boolean permanentlyStopped;
    private boolean pendingBurst;
    private int ageTicks;
    private int sleepTicks;
    private double activeTime;
    private double sleepTime;
    private double spawnAccumulator;
    private int spawned;
    private int nextId;

    public ParticleEmitter(ParticleEffectBlueprint effect, RandomGenerator random, int maxParticlesCap) {
        this.effect = effect;
        this.random = random;
        this.maxParticlesCap = Math.max(0, maxParticlesCap);
        this.context = new MolangContext(random);
        if (!effect.curves().isEmpty()) {
            context.setResolver((name, reading) -> {
                Curve curve = effect.curves().get(name);
                return curve == null ? null : curve.evaluate(reading);
            });
        }
    }

    public ParticleEffectBlueprint effect() {
        return effect;
    }

    /** Starts or restarts the emitter clock. Live particles are kept. */
    public void start() {
        if (permanentlyStopped) return;
        state = State.ACTIVE;
        ageTicks = 0;
        sleepTicks = 0;
        spawnAccumulator = 0;
        for (int i = 1; i <= 4; i++) context.set("variable.emitter_random_" + i, random.nextDouble());
        context.set("variable.emitter_age", 0);
        switch (effect.emitter().lifetime()) {
            case Lifetime.Once once -> activeTime = once.active().evaluate(context);
            case Lifetime.Looping looping -> {
                activeTime = looping.active().evaluate(context);
                sleepTime = looping.sleep().evaluate(context);
            }
            case Lifetime.Expression ignored -> activeTime = 0;
        }
        context.set("variable.emitter_lifetime", activeTime);
        effect.emitter().creation().evaluate(context);
        pendingBurst = effect.emitter().rate() instanceof Rate.Instant;
    }

    /** No further spawning or restarts; the emitter finishes when its particles die. */
    public void stopSpawning() {
        permanentlyStopped = true;
        state = State.STOPPED;
        pendingBurst = false;
    }

    public boolean isFinished() {
        return state == State.STOPPED && particles.isEmpty();
    }

    public List<SimulatedParticle> particles() {
        return Collections.unmodifiableList(particles);
    }

    /** Particles that expired since the last call. */
    public List<SimulatedParticle> drainExpired() {
        if (expired.isEmpty()) return List.of();
        List<SimulatedParticle> drained = List.copyOf(expired);
        expired.clear();
        return drained;
    }

    public int spawnedCount() {
        return spawned;
    }

    public void tick(Vector3d emitterPosition, Quaterniond emitterRotation, double dt) {
        context.set("variable.emitter_age", ageTicks * dt);
        effect.emitter().perUpdate().evaluate(context);

        for (SimulatedParticle particle : particles) {
            effect.particle().perUpdate().evaluate(particle.context);
            tickParticle(particle, dt);
        }
        particles.removeIf(particle -> {
            if (!particle.expired) return false;
            expired.add(particle);
            return true;
        });

        if (state == State.ACTIVE) {
            ageTicks++;
            context.set("variable.emitter_age", ageTicks * dt);
            if (pendingBurst) {
                pendingBurst = false;
                Rate.Instant instant = (Rate.Instant) effect.emitter().rate();
                spawn((int) Math.round(instant.count().evaluate(context)), maxParticlesCap, emitterPosition, emitterRotation, dt);
            }
            if (effect.emitter().rate() instanceof Rate.Steady steady) {
                spawnAccumulator += steady.rate().evaluate(context) * dt;
                int count = (int) Math.floor(spawnAccumulator + EPSILON);
                spawnAccumulator -= count;
                int max = (int) Math.min(maxParticlesCap, Math.round(steady.max().evaluate(context)));
                spawn(count, max, emitterPosition, emitterRotation, dt);
            }
            updateActiveLifetime(dt);
        } else if (state == State.SLEEPING) {
            updateSleepingLifetime(dt);
        }
    }

    private void updateActiveLifetime(double dt) {
        double age = ageTicks * dt;
        switch (effect.emitter().lifetime()) {
            case Lifetime.Once ignored -> {
                if (age + EPSILON >= activeTime) state = State.STOPPED;
            }
            case Lifetime.Looping ignored -> {
                if (age + EPSILON >= activeTime) {
                    state = State.SLEEPING;
                    sleepTicks = 0;
                }
            }
            case Lifetime.Expression expression -> {
                if (expression.expiration().evaluate(context) != 0) {
                    state = State.SLEEPING;
                    sleepTicks = 0;
                }
            }
        }
    }

    private void updateSleepingLifetime(double dt) {
        if (permanentlyStopped) return;
        sleepTicks++;
        switch (effect.emitter().lifetime()) {
            case Lifetime.Looping ignored -> {
                if (sleepTicks * dt + EPSILON >= sleepTime) start();
            }
            case Lifetime.Expression expression -> {
                if (expression.activation().evaluate(context) != 0) start();
            }
            case Lifetime.Once ignored -> state = State.STOPPED;
        }
    }

    private void spawn(int count, int max, Vector3d emitterPosition, Quaterniond emitterRotation, double dt) {
        int allowed = Math.min(count, max - particles.size());
        for (int i = 0; i < allowed; i++) {
            SimulatedParticle particle = createParticle(emitterPosition, emitterRotation);
            particles.add(particle);
            spawned++;
            tickParticle(particle, dt);
        }
    }

    private SimulatedParticle createParticle(Vector3d emitterPosition, Quaterniond emitterRotation) {
        Emitter emitterSettings = effect.emitter();
        Particle settings = effect.particle();
        SimulatedParticle particle = new SimulatedParticle(nextId++, new MolangContext(context, random),
                emitterSettings.localPosition(), emitterSettings.localRotation());
        MolangContext variables = particle.context;
        for (int i = 1; i <= 4; i++) variables.set("variable.particle_random_" + i, random.nextDouble());
        variables.set("variable.particle_age", 0);
        particle.lifetime = settings.maxLifetime() == null ? 0 : settings.maxLifetime().evaluate(variables);
        variables.set("variable.particle_lifetime", particle.lifetime);
        particle.initialRotation = settings.initialRotation().evaluate(variables);
        particle.rotationRate = settings.rotationRate().evaluate(variables);
        particle.rotation = particle.initialRotation;

        Shape shape = emitterSettings.shape();
        Vector3d local = shapePosition(shape, variables, new Vector3d());
        Vector3d velocity;
        if (settings.velocity() != null) {
            velocity = settings.velocity().evaluate(variables, new Vector3d());
        } else {
            velocity = direction(shape, local, variables).mul(settings.linearSpeed().evaluate(variables));
        }
        local.add(shape.offset().evaluate(variables, scratch));

        if (emitterSettings.localPosition()) {
            if (emitterSettings.localRotation()) {
                particle.position.set(local);
                particle.speed.set(velocity);
            } else {
                emitterRotation.transform(particle.position.set(local));
                emitterRotation.transform(particle.speed.set(velocity));
            }
        } else {
            emitterRotation.transform(particle.position.set(local)).add(emitterPosition);
            emitterRotation.transform(particle.speed.set(velocity));
        }

        TextureRegion region = settings.billboard().region();
        if (region != null) {
            particle.uvU = region.u().evaluate(variables);
            particle.uvV = region.v().evaluate(variables);
        }
        return particle;
    }

    private Vector3d shapePosition(Shape shape, MolangContext variables, Vector3d result) {
        switch (shape.type()) {
            case BOX -> {
                Vector3d half = shape.halfDimensions().evaluate(variables, new Vector3d());
                result.set(randomBetween(-half.x, half.x), randomBetween(-half.y, half.y), randomBetween(-half.z, half.z));
                if (shape.surfaceOnly()) {
                    int face = random.nextInt(3);
                    double sign = random.nextBoolean() ? 1 : -1;
                    result.setComponent(face, half.get(face) * sign);
                }
            }
            case SPHERE -> {
                double radius = shape.radius().evaluate(variables);
                randomUnit(result).mul(shape.surfaceOnly() ? radius : radius * random.nextDouble());
            }
            case DISC -> {
                double radius = shape.radius().evaluate(variables);
                double angle = random.nextDouble() * Math.PI * 2;
                double distance = shape.surfaceOnly() ? radius : radius * Math.sqrt(random.nextDouble());
                result.set(distance * Math.cos(angle), 0, distance * Math.sin(angle));
                Vector3d normal = shape.planeNormal().evaluate(variables, new Vector3d());
                if (normal.lengthSquared() > EPSILON && !normal.normalize().equals(0, 1, 0)) {
                    new Quaterniond().rotationTo(new Vector3d(0, 1, 0), normal).transform(result);
                }
            }
            case POINT -> result.set(0, 0, 0);
        }
        return result;
    }

    private Vector3d direction(Shape shape, Vector3d local, MolangContext variables) {
        Vector3d direction = new Vector3d();
        switch (shape.directionMode()) {
            case VECTOR -> {
                shape.direction().evaluate(variables, direction);
                if (direction.lengthSquared() > EPSILON) direction.normalize();
            }
            case OUTWARDS, INWARDS -> {
                if (shape.type() == ShapeType.POINT || local.lengthSquared() < EPSILON) randomUnit(direction);
                else direction.set(local).normalize();
                if (shape.directionMode() == DirectionMode.INWARDS) direction.negate();
            }
        }
        return direction;
    }

    private void tickParticle(SimulatedParticle particle, double dt) {
        Particle settings = effect.particle();
        MolangContext variables = particle.context;
        settings.perRender().evaluate(variables);

        particle.ageTicks++;
        particle.loopTicks++;
        double age = particle.ageTicks * dt;
        variables.set("variable.particle_age", age);
        if (particle.lifetime > 0 && age > particle.lifetime + EPSILON) particle.expired = true;
        if (settings.expiration().evaluate(variables) != 0) particle.expired = true;

        if (settings.dynamicMotion()) {
            double drag = settings.linearDrag().evaluate(variables);
            settings.linearAcceleration().evaluate(variables, particle.acceleration);
            particle.acceleration.fma(-drag, particle.speed);
            particle.speed.fma(dt, particle.acceleration);
            particle.position.fma(dt, particle.speed);
        }

        double rotationAcceleration = settings.rotationAcceleration().evaluate(variables)
                - settings.rotationDrag().evaluate(variables) * particle.rotationRate;
        particle.rotationRate += rotationAcceleration * dt;
        particle.rotation = particle.initialRotation + particle.rotationRate * age;

        Billboard billboard = settings.billboard();
        if (billboard.customDirection()) {
            billboard.direction().evaluate(variables, particle.facingDirection);
            if (particle.facingDirection.lengthSquared() > EPSILON) particle.facingDirection.normalize();
        } else if (particle.speed.length() >= billboard.minSpeedThreshold()) {
            particle.facingDirection.set(particle.speed).normalize();
        }

        particle.width = billboard.width().evaluate(variables);
        particle.height = billboard.height().evaluate(variables);
        updateFrame(particle, billboard.region(), dt);
        particle.color = color(settings.tint(), variables);
    }

    private static void updateFrame(SimulatedParticle particle, TextureRegion region, double dt) {
        if (region == null || region.frames() <= 1) return;
        double framesPerSecond = region.stretchToLifetime() && particle.lifetime > 0
                ? region.frames() / particle.lifetime
                : region.framesPerSecond().evaluate(particle.context);
        int candidate = (int) Math.floor(particle.loopTicks * dt * framesPerSecond + EPSILON);
        if (candidate > particle.frame) particle.frame = candidate;
        if (particle.frame >= region.frames()) {
            if (region.loop()) {
                particle.frame = 0;
                particle.loopTicks = 0;
            } else {
                particle.frame = region.frames() - 1;
            }
        }
    }

    private static int color(Tint tint, MolangContext variables) {
        return switch (tint) {
            case Tint.Static fixed -> fixed.argb();
            case Tint.Expression expression -> SnowstormParser.argb(expression.alpha().evaluate(variables),
                    expression.red().evaluate(variables), expression.green().evaluate(variables),
                    expression.blue().evaluate(variables));
            case Tint.Gradient gradient -> gradient(gradient, variables);
        };
    }

    private static int gradient(Tint.Gradient gradient, MolangContext variables) {
        double position = gradient.interpolant().evaluate(variables) / gradient.range();
        List<Stop> stops = gradient.stops();
        Stop before = null, after = null;
        for (Stop stop : stops) {
            if (stop.position() <= position + EPSILON) before = stop;
            else {
                after = stop;
                break;
            }
        }
        if (before == null) return stops.getFirst().argb();
        if (after == null) return before.argb();
        double mix = (position - before.position()) / (after.position() - before.position());
        return mixColor(before.argb(), after.argb(), mix);
    }

    private static int mixColor(int from, int to, double mix) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int start = (from >>> shift) & 0xFF, end = (to >>> shift) & 0xFF;
            result |= ((int) Math.round(start + (end - start) * mix) & 0xFF) << shift;
        }
        return result;
    }

    private double randomBetween(double low, double high) {
        return low + random.nextDouble() * (high - low);
    }

    private Vector3d randomUnit(Vector3d result) {
        double z = random.nextDouble() * 2 - 1;
        double angle = random.nextDouble() * Math.PI * 2;
        double radius = Math.sqrt(1 - z * z);
        return result.set(radius * Math.cos(angle), radius * Math.sin(angle), z);
    }
}
