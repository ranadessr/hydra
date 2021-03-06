/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.task.map;


import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.addthis.basis.jvm.Shutdown;
import com.addthis.basis.util.Parameter;

import com.addthis.bundle.core.Bundle;
import com.addthis.hydra.task.source.TaskDataSource;

import com.google.common.base.Throwables;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MapFeederForkJoin implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MapFeederForkJoin.class);

    private static final int QUEUE_DEPTH = Parameter.intValue("task.queue.depth", 100);

    // state control
    private final AtomicBoolean errored = new AtomicBoolean(false);
    private boolean hasClosedStreams = false; // not shared with MapperTasks

    // enclosing task
    private final StreamMapper task;
    private final TaskDataSource source;

    // mapper task controls
    private final ForkJoinPool mapperPool;
    private final int parallelism;
    private final Semaphore enqueuePermits;

    private long start = System.currentTimeMillis();
    private long totalBundles = 0;

    public MapFeederForkJoin(StreamMapper task, TaskDataSource source, int parallelism) {
        this.source = source;
        this.task = task;
        this.parallelism = parallelism;
        this.mapperPool = new ForkJoinPool(parallelism,
                                           ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                                           (Thread t, Throwable e) -> handleUncaughtThrowable(e),
                                           true);
        this.enqueuePermits = new Semaphore(QUEUE_DEPTH);
    }

    @Override public void run() {
        log.info("starting {} thread(s) for src={}", parallelism, source);
        try {
            if (source.isEnabled()) {
                while (fillBuffer()) {
                    if (Thread.interrupted()) {
                        closeSourceIfNeeded();
                    }
                }
            }
            closeSourceIfNeeded();
            // run in different threads to isolate them from interrupts. ie. "taskCompleteUninterruptibly"
            // join awaits completion, is uninterruptible, and will propagate any exception
            CompletableFuture.runAsync(this::joinProcessors).join();
            log.info("all ({}) task threads exited; sending taskComplete", parallelism);
            logBundleThroughput();
            CompletableFuture.runAsync(task::taskComplete).join();
        } catch (Throwable t) {
            logBundleThroughput();
            handleUncaughtThrowable(t);
        }
        log.debug("task feeder exited");
    }

    private void logBundleThroughput() {
        long elapse = (System.currentTimeMillis() - start) / 1000;
        log.info("{} bundles processed in {} seconds (avg rate={}/s)", totalBundles, elapse, totalBundles / elapse);
    }

    /**
     * Immediately halting is the best way we have of ensuring errors are reported without a significantly
     * more involved system.
     */
    private void handleUncaughtThrowable(Throwable t) {
        if (errored.compareAndSet(false, true)) {
            log.error("unrecoverable error in task feeder or one of its mapper threads. immediately halting jvm", t);
            Shutdown.exit(1);
        }
    }

    /**
     * Push the next bundle onto the work queue. Returns true
     * to continue processing. If another thread has interrupted
     * ourselves then set our interrupt status. This will close
     * the current source and continue to consume any remaining elements
     * from the queue.
     */
    private boolean fillBuffer() {
        boolean status = false;
        try {
            enqueuePermits.acquire();
            Bundle p = source.next();
            if (p == null) {
                log.info("exiting on null bundle from {}", source);
            } else {
                totalBundles++;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> task.process(p), mapperPool);
                future.whenComplete((v, e) -> enqueuePermits.release());
                status = true;
            }
        } catch (NoSuchElementException ignored) {
            log.info("exiting on premature stream termination");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            status = true;
        }
        return status;
    }

    private void joinProcessors() {
        mapperPool.shutdown();
        try {
            if (!mapperPool.awaitTermination(task.getTaskFinishTimeout(), TimeUnit.SECONDS)) {
                throw new RuntimeException("Mapper pool did not terminate after " +
                                           task.getTaskFinishTimeout() + " seconds.");
            }
        } catch (InterruptedException ex) {
            log.error("Interrupted while waiting for mapper pool termination.");
            Throwables.propagate(ex);
        }
    }

    private void closeSourceIfNeeded() {
        if (!hasClosedStreams) {
            hasClosedStreams = true;
            log.info("closing stream {}", source.getClass().getSimpleName());
            // TODO: better API/ force sources to behave more sensibly
            CompletableFuture.runAsync(source::close).join();
        }
    }

}