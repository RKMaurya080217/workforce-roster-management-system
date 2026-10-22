package com.weeklyroster.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Coordinates database write operations to prevent SQLite snapshot conflicts (SQLITE_BUSY_SNAPSHOT).
 * SQLite WAL mode allows concurrent readers, but strictly forbids upgrading a deferred read transaction
 * to a write if another transaction committed concurrently.
 * 
 * For SQLite environments, this coordinator serializes write operations across application threads.
 * For MySQL (production environments), operations execute directly with zero locking overhead.
 */
@Component
public class DatabaseWriteCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseWriteCoordinator.class);

    // Fair lock ensures orderly FIFO execution of write transactions
    private final ReentrantLock writeLock = new ReentrantLock(true);

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    public boolean isSqlite() {
        return datasourceUrl != null && datasourceUrl.contains("sqlite");
    }

    /**
     * Executes a write operation within a single-writer lock for SQLite,
     * including bounded retries for transient lock contentions.
     */
    public <T> T executeWrite(Supplier<T> writeAction) {
        if (!isSqlite()) {
            return writeAction.get();
        }

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            boolean acquired = false;
            try {
                acquired = writeLock.tryLock(30, TimeUnit.SECONDS);
                if (!acquired) {
                    throw new CannotAcquireLockException("Timed out waiting for SQLite write lock after 30 seconds");
                }
                return writeAction.get();
            } catch (org.springframework.dao.TransientDataAccessException e) {
                log.warn("[DatabaseWriteCoordinator] Transient write lock contention on attempt {}/{}: {}",
                        attempt, maxAttempts, e.getMessage());
                if (attempt == maxAttempts) {
                    throw e;
                }
                try {
                    Thread.sleep(150L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CannotAcquireLockException("Interrupted during lock backoff", ie);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CannotAcquireLockException("Thread interrupted while acquiring SQLite write lock", e);
            } finally {
                if (acquired) {
                    writeLock.unlock();
                }
            }
        }
        throw new CannotAcquireLockException("Exceeded maximum write retry attempts");
    }

    public void executeWrite(Runnable writeAction) {
        executeWrite(() -> {
            writeAction.run();
            return null;
        });
    }

    public void executeWriteVoid(Runnable writeAction) {
        executeWrite(() -> {
            writeAction.run();
            return null;
        });
    }
}
