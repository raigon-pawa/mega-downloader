/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 *
 * @author tonikelope
 */
public class BoundedExecutor {

    private final ExecutorService exec;
    private final Semaphore semaphore;

    public BoundedExecutor(ExecutorService exec, int bound) {
        this.exec = exec;
        this.semaphore = new Semaphore(bound);
    }

    public void submitTask(final Runnable command)
            throws InterruptedException {

        semaphore.acquire();
        boolean submitted = false;
        try {
            exec.execute(new Runnable() {
                public void run() {
                    try {
                        command.run();
                    } finally {
                        semaphore.release();
                    }
                }
            });
            submitted = true;
        } finally {
            if (!submitted) {
                semaphore.release();
            }
        }

    }
}
