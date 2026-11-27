package com.newgen.banking.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the transaction thread pool executor.
 * All values are externalized to application.properties under the prefix
 * {@code banking.executor}.
 *
 * <p>Example application.properties:</p>
 * <pre>
 * banking.executor.core-pool-size=5
 * banking.executor.max-pool-size=20
 * banking.executor.queue-capacity=500
 * banking.executor.keep-alive-seconds=60
 * banking.executor.shutdown-timeout-seconds=30
 * banking.executor.thread-name-prefix=txn-worker-
 * </pre>
 */
@ConfigurationProperties(prefix = "banking.executor")
public class TransactionExecutorProperties {

    private static final Logger log = LoggerFactory.getLogger(TransactionExecutorProperties.class);

    /** Number of threads always kept alive, even when idle. */
    private int corePoolSize = 5;

    /** Maximum number of threads that can be active simultaneously. */
    private int maxPoolSize = 20;

    /** Maximum number of pending tasks waiting in the bounded queue. */
    private int queueCapacity = 500;

    /** Seconds an idle non-core thread survives before being terminated. */
    private int keepAliveSeconds = 60;

    /** Seconds to wait for in-flight tasks to finish during graceful shutdown. */
    private int shutdownTimeoutSeconds = 30;

    /** Prefix for worker thread names (useful in logs and stack traces). */
    private String threadNamePrefix = "txn-worker-";

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public int getCorePoolSize() { return corePoolSize; }
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 1) throw new IllegalArgumentException("corePoolSize must be >= 1");
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int maxPoolSize) {
        if (maxPoolSize < 1) throw new IllegalArgumentException("maxPoolSize must be >= 1");
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be >= 1");
        this.queueCapacity = queueCapacity;
    }

    public int getKeepAliveSeconds() { return keepAliveSeconds; }
    public void setKeepAliveSeconds(int keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public int getShutdownTimeoutSeconds() { return shutdownTimeoutSeconds; }
    public void setShutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    public String getThreadNamePrefix() { return threadNamePrefix; }
    public void setThreadNamePrefix(String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
    }
}
