package com.magmaguy.freeminecraftmodels.customentity.core;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrientedBoundingBoxConcurrencyTest {

    @Test
    void concurrentPerpendicularRaysCannotCorruptSharedIntersectionState() throws Exception {
        OrientedBoundingBox box = new OrientedBoundingBox(
                new Vector3d(0D, 1D, 5D), 2D, 2D, 2D);
        Location forwardRay = new Location(null, 0D, 1D, 0D, 0F, 0F);
        Location sideRay = new Location(null, 10D, 1D, 5D, 90F, 0F);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> forward = executor.submit(() -> assertEveryRayHits(box, forwardRay, start));
            Future<?> side = executor.submit(() -> assertEveryRayHits(box, sideRay, start));
            start.countDown();
            forward.get();
            side.get();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void compoundAabbQueryUsesOnePoseWhileYawUpdatesOffThread() throws Exception {
        OrientedBoundingBox box = new OrientedBoundingBox(
                new Location(null, 0D, 0D, 0D), 4D, 2D, 4D);
        BoundingBox centeredTarget = new BoundingBox(-.2D, .5D, -.2D, .2D, 1.5D, .2D);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> updater = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 25_000; i++) {
                    box.update(new Location(null, 0D, 0D, 0D, (i & 1) == 0 ? 0F : 90F, 0F));
                }
            });
            Future<?> reader = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 25_000; i++) {
                    assertTrue(box.intersectsAABBCoherently(centeredTarget),
                            "compound collision mixed two asynchronously published poses");
                }
            });
            start.countDown();
            updater.get();
            reader.get();
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertEveryRayHits(
            OrientedBoundingBox box,
            Location ray,
            CountDownLatch start) {
        try {
            start.await();
            for (int i = 0; i < 25_000; i++) {
                assertTrue(box.rayIntersection(ray, 20D) > 0D,
                        "a valid ray observed another thread's partial query state");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("ray stress test interrupted", interrupted);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrency test interrupted", interrupted);
        }
    }
}
