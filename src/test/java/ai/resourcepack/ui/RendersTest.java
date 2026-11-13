package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Cpu;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render jobs go to the pool one per thread, the most urgent kind first: on a busy server a page just opened must not
 * wait behind every hover, animation frame and drawing ahead already queued.
 */
class RendersTest {
    @AfterEach
    void reset() {
        Cpu.configure(0);
    }

    @Test
    void theMostUrgentKindGoesFirst() throws InterruptedException {
        Cpu.configure(1);
        Renders r = new Renders();
        CountDownLatch busy = new CountDownLatch(1), started = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        r.execute(Renders.Kind.PICTURE, () -> {
            started.countDown();
            await(busy);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        // Queued while the one thread is busy, least urgent first.
        r.execute(Renders.Kind.AHEAD, () -> order.add("ahead"));
        r.execute(Renders.Kind.MOTION, () -> order.add("motion 1"));
        r.execute(Renders.Kind.MOTION, () -> order.add("motion 2"));
        r.execute(Renders.Kind.PICTURE, () -> order.add("hover"));
        r.execute(Renders.Kind.SHOW, () -> order.add("show"));
        busy.countDown();
        awaitJobs(r, 6);
        assertEquals(List.of("show", "hover", "motion 1", "motion 2", "ahead"), order);
    }

    @Test
    void neverMoreJobsInThePoolThanThreads() throws InterruptedException {
        Cpu.configure(2);
        Renders r = new Renders();
        AtomicInteger now = new AtomicInteger(), most = new AtomicInteger();
        for (int i = 0; i < 40; i++) {
            r.execute(Renders.Kind.values()[i % Renders.Kind.values().length], () -> {
                most.accumulateAndGet(now.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    now.decrementAndGet();
                }
            });
        }
        awaitJobs(r, 40);
        assertTrue(most.get() <= 2, "at most one job a thread, saw " + most.get());
    }

    @Test
    void closeDropsWhatWaits() throws InterruptedException {
        Cpu.configure(1);
        Renders r = new Renders();
        CountDownLatch busy = new CountDownLatch(1), started = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        r.execute(Renders.Kind.PICTURE, () -> {
            started.countDown();
            await(busy);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) r.execute(Renders.Kind.SHOW, ran::incrementAndGet);
        // Let the busy job go only once close() has begun: released any earlier, the pool may start a waiting job first.
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            busy.countDown();
        }).start();
        r.close(5, TimeUnit.SECONDS);
        assertEquals(0, ran.get(), "nothing waiting runs after close");
        r.execute(Renders.Kind.SHOW, ran::incrementAndGet);
        Thread.sleep(50);
        assertEquals(0, ran.get(), "nothing new is taken after close");
    }

    /** Waits until [n] jobs have run (close() would drop those still waiting). */
    private static void awaitJobs(Renders r, long n) throws InterruptedException {
        for (int i = 0; i < 1000 && totalJobs(r) < n; i++) Thread.sleep(5);
        assertEquals(n, totalJobs(r), "jobs run");
    }

    private static long totalJobs(Renders r) {
        long n = 0;
        for (Renders.Kind k : Renders.Kind.values()) {
            String t = r.times(k);
            n += Long.parseLong(t.substring(t.indexOf(':') + 1, t.indexOf(',')));
        }
        return n;
    }

    private static void await(CountDownLatch l) {
        try {
            l.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
