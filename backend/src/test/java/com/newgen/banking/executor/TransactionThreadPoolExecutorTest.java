package com.newgen.banking.executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TransactionThreadPoolExecutor}.
 *
 * <p>These tests run entirely in-memory without Spring context or PostgreSQL.
 * They verify the pool's configuration, task execution, bounded queue behavior,
 * rejection policy, and graceful shutdown.</p>
 */
class TransactionThreadPoolExecutorTest {

    private TransactionExecutorProperties props;
    private TransactionThreadPoolExecutor pool;

    @BeforeEach
    void setUp() {
        props = new TransactionExecutorProperties();
        props.setCorePoolSize(2);
        props.setMaxPoolSize(4);
        props.setQueueCapacity(10);
        props.setKeepAliveSeconds(10);
        props.setShutdownTimeoutSeconds(5);
        props.setThreadNamePrefix("test-worker-");

        pool = new TransactionThreadPoolExecutor(props);
        pool.start();
    }

    @AfterEach
    void tearDown() {
        pool.stop();
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Pool reports correct core/max/queue config from properties")
    void poolConfiguration_ShouldMatchProperties() {
        assertEquals(2, pool.getCorePoolSize());
        assertEquals(4, pool.getMaxPoolSize());
        assertEquals(10, pool.getQueueCapacity());
    }

    @Test
    @DisplayName("Pool is running after start()")
    void start_ShouldMarkPoolAsRunning() {
        assertTrue(pool.isRunning());
    }

    @Test
    @DisplayName("Pool is not running after stop()")
    void stop_ShouldMarkPoolAsNotRunning() {
        pool.stop();
        assertFalse(pool.isRunning());
    }

    // ── Task submission ───────────────────────────────────────────────────────

    @Test
    @DisplayName("submit() returns a non-null Future")
    void submit_ShouldReturnFuture() {
        Future<String> future = pool.submit(() -> "hello");
        assertNotNull(future);
    }

    @Test
    @DisplayName("submit() executes the callable and returns its result")
    void submit_ShouldExecuteCallableAndReturnResult() throws Exception {
        Future<Integer> future = pool.submit(() -> 42);
        assertEquals(42, future.get());
    }

    @Test
    @DisplayName("Multiple tasks complete successfully and independently")
    void submit_MultipleTasksShouldAllComplete() throws Exception {
        int taskCount = 8;
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            final int value = i;
            futures.add(pool.submit(() -> value * 2));
        }
        int sum = 0;
        for (int i = 0; i < taskCount; i++) {
            sum += futures.get(i).get();
        }
        // 0+2+4+6+8+10+12+14 = 56
        assertEquals(56, sum);
    }

    @Test
    @DisplayName("Worker threads are named with the configured prefix")
    void submit_ShouldUseNamedThreads() throws Exception {
        Future<String> future = pool.submit(() -> Thread.currentThread().getName());
        String threadName = future.get();
        assertTrue(threadName.startsWith("test-worker-"),
                "Thread name should start with 'test-worker-' but was: " + threadName);
    }

    // ── Exception handling ────────────────────────────────────────────────────

    @Test
    @DisplayName("Exception in one task does not prevent other tasks from running")
    void submit_ExceptionInOneTask_ShouldNotBlockOthers() throws Exception {
        Future<String> failing = pool.submit(() -> { throw new RuntimeException("boom"); });
        Future<String> passing = pool.submit(() -> "ok");

        // The passing task must still complete
        assertEquals("ok", passing.get());
        // The failing task's future should report the exception via ExecutionException
        assertThrows(java.util.concurrent.ExecutionException.class, failing::get);
    }

    // ── Bounded queue / rejection ─────────────────────────────────────────────

    @Test
    @DisplayName("TaskRejectedException thrown when pool + queue are both saturated")
    void submit_WhenPoolAndQueueFull_ShouldThrowTaskRejectedException() throws Exception {
        // Use a tiny pool (1 core, 1 max, 2 queue) to force saturation easily
        TransactionExecutorProperties tinyProps = new TransactionExecutorProperties();
        tinyProps.setCorePoolSize(1);
        tinyProps.setMaxPoolSize(1);
        tinyProps.setQueueCapacity(2);
        tinyProps.setShutdownTimeoutSeconds(5);

        TransactionThreadPoolExecutor tinyPool = new TransactionThreadPoolExecutor(tinyProps);
        tinyPool.start();

        try {
            AtomicInteger rejectedCount = new AtomicInteger(0);
            List<Future<Void>> futures = new ArrayList<>();

            // Fill: 1 thread running + 2 queued = 3 slots used. The 4th should be rejected.
            for (int i = 0; i < 10; i++) {
                try {
                    futures.add(tinyPool.submit(() -> {
                        Thread.sleep(200);
                        return null;
                    }));
                } catch (TaskRejectedException ex) {
                    rejectedCount.incrementAndGet();
                }
            }
            // At least one submission should have been rejected
            assertTrue(rejectedCount.get() > 0,
                    "Expected at least one TaskRejectedException");
        } finally {
            tinyPool.stop();
        }
    }

    // ── Graceful shutdown ─────────────────────────────────────────────────────

    @Test
    @DisplayName("In-flight task completes before stop() returns")
    void stop_ShouldWaitForInFlightTasksToComplete() throws Exception {
        AtomicInteger completedCount = new AtomicInteger(0);
        Future<Void> inFlight = pool.submit(() -> {
            Thread.sleep(100);
            completedCount.incrementAndGet();
            return null;
        });

        pool.stop();
        // After stop returns, the task should have completed
        assertEquals(1, completedCount.get(),
                "In-flight task should complete before stop() returns");
    }

    @Test
    @DisplayName("submit() after stop() throws IllegalStateException")
    void submit_AfterStop_ShouldThrowIllegalStateException() {
        pool.stop();
        assertThrows(IllegalStateException.class, () -> pool.submit(() -> "nope"));
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getCompletedTaskCount() increments after tasks finish")
    void completedTaskCount_ShouldIncrementAfterExecution() throws Exception {
        Future<Void> f1 = pool.submit(() -> null);
        Future<Void> f2 = pool.submit(() -> null);
        f1.get();
        f2.get();

        // Poll briefly — completedTaskCount is eventually consistent
        Thread.sleep(50);
        assertTrue(pool.getCompletedTaskCount() >= 2,
                "Expected at least 2 completed tasks");
    }
}
