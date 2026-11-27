package com.newgen.banking.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, named, lifecycle-managed thread pool for concurrent transaction processing.
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><b>Bounded queue</b> — {@link LinkedBlockingQueue} with a fixed capacity prevents
 *       unbounded memory growth when producers outpace workers.</li>
 *   <li><b>Named threads</b> — every worker is named {@code txn-worker-N} so stack traces
 *       and thread dumps are immediately readable in logs.</li>
 *   <li><b>Reject policy</b> — when both the pool and the queue are full, the submitter
 *       receives a {@link TaskRejectedException} (not a silent discard), allowing callers
 *       to record the rejection in the batch result.</li>
 *   <li><b>Spring lifecycle</b> — implements {@link SmartLifecycle} so the executor starts
 *       with the application context and shuts down gracefully (waits up to
 *       {@code shutdownTimeoutSeconds} for in-flight tasks) before the context closes.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>The underlying {@link ThreadPoolExecutor} is thread-safe by design. This wrapper
 * adds no mutable state; {@link #submit(java.util.concurrent.Callable)} delegates directly
 * to the pool.</p>
 */
@Component
@EnableConfigurationProperties(TransactionExecutorProperties.class)
public class TransactionThreadPoolExecutor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TransactionThreadPoolExecutor.class);

    private final TransactionExecutorProperties props;
    private volatile ThreadPoolExecutor executor;
    private volatile boolean running = false;

    public TransactionThreadPoolExecutor(TransactionExecutorProperties props) {
        this.props = props;
    }

    // ── SmartLifecycle ────────────────────────────────────────────────────────

    @Override
    public void start() {
        BlockingQueue<Runnable> workQueue =
                new LinkedBlockingQueue<>(props.getQueueCapacity());

        ThreadFactory namedFactory = new BankingThreadFactory(props.getThreadNamePrefix());

        RejectedExecutionHandler rejectHandler = (runnable, pool) -> {
            throw new TaskRejectedException(
                    "Transaction task rejected — pool and queue are both saturated. "
                            + "Active=" + pool.getActiveCount()
                            + " Queue=" + pool.getQueue().size()
                            + "/" + props.getQueueCapacity());
        };

        executor = new ThreadPoolExecutor(
                props.getCorePoolSize(),
                props.getMaxPoolSize(),
                props.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                workQueue,
                namedFactory,
                rejectHandler
        );

        // Allow core threads to time out when idle, saving resources under low load
        executor.allowCoreThreadTimeOut(false);

        running = true;
        log.info("TransactionThreadPoolExecutor started — core={} max={} queueCapacity={}",
                props.getCorePoolSize(), props.getMaxPoolSize(), props.getQueueCapacity());
    }

    @Override
    public void stop() {
        if (executor == null || executor.isShutdown()) return;

        log.info("TransactionThreadPoolExecutor shutting down — waiting up to {}s for in-flight tasks",
                props.getShutdownTimeoutSeconds());
        executor.shutdown();
        try {
            if (!executor.awaitTermination(props.getShutdownTimeoutSeconds(), TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate in time — forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            log.warn("Interrupted during executor shutdown — forcing shutdown");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        running = false;
        log.info("TransactionThreadPoolExecutor stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Submits a {@link java.util.concurrent.Callable} to the thread pool.
     *
     * @throws TaskRejectedException if both the pool and queue are full
     * @throws IllegalStateException if the executor has not been started
     */
    public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
        if (executor == null || executor.isShutdown()) {
            throw new IllegalStateException("TransactionThreadPoolExecutor is not running");
        }
        return executor.submit(task);
    }

    // ── Metrics accessors (for benchmarking and batch result) ─────────────────

    public int getActiveCount()   { return executor != null ? executor.getActiveCount() : 0; }
    public int getQueueSize()     { return executor != null ? executor.getQueue().size() : 0; }
    public int getCorePoolSize()  { return props.getCorePoolSize(); }
    public int getMaxPoolSize()   { return props.getMaxPoolSize(); }
    public int getQueueCapacity() { return props.getQueueCapacity(); }
    public long getCompletedTaskCount() {
        return executor != null ? executor.getCompletedTaskCount() : 0;
    }

    // ── Inner: named thread factory ───────────────────────────────────────────

    private static final class BankingThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        BankingThreadFactory(String prefix) { this.prefix = prefix; }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + counter.getAndIncrement());
            t.setDaemon(false); // non-daemon — JVM waits for these during shutdown
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.error("Uncaught exception in thread {}", thread.getName(), ex));
            return t;
        }
    }
}
