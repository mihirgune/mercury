/*

    Copyright 2018-2021 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.core.util;

import com.sleepycat.je.*;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.system.Platform;
import org.platformlambda.core.system.PostOffice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class ElasticQueue implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ElasticQueue.class);

    private static final Utility util = Utility.getInstance();
    private static final AtomicInteger counter = new AtomicInteger(0);
    private static final AtomicInteger generation = new AtomicInteger(0);

    public static final int MEMORY_BUFFER = 10;
    private static final String RUNNING = "RUNNING";
    private static final String CLEAN_UP_TASK = "elastic.queue.cleanup";
    private static final String SLASH = "/";
    private static final int MAX_EVENTS = 100000000;
    private static final int ONE_MINUTE = 60 * 1000;
    private static final int ONE_HOUR = 60 * ONE_MINUTE;
    private static final int ONE_DAY = 24 * ONE_HOUR;
    private static boolean loaded = false;
    private static Database db;
    private static Environment dbEnv;
    private static File dbFolder;
    private static KeepAlive alive;
    private static Boolean runningInCloud;
    private long readCounter, writeCounter;
    private boolean empty = false;
    private byte[] peeked = null;
    private int currentVersion = generation.get();
    private final String id;
    private final ConcurrentLinkedQueue<byte[]> memory = new ConcurrentLinkedQueue<>();

    /**
     * Two-stage elastic queue using memory and disk
     *
     * @param id service route path
     */
    public ElasticQueue(String id) {
        this.id = util.validServiceName(id)? id : util.filteredServiceName(id);
        resetCounter();
        if (counter.incrementAndGet() == 1 && !loaded) {
            loaded = true;
            Platform platform = Platform.getInstance();
            try {
                platform.registerPrivate(CLEAN_UP_TASK, new Cleanup(), 1);
            } catch (IOException e) {
                log.error("Unable to register {} - {}", CLEAN_UP_TASK, e.getMessage());
            }
            Runtime.getRuntime().addShutdownHook(new Thread(ElasticQueue::shutdown));
            AppConfigReader config = AppConfigReader.getInstance();
            runningInCloud = "true".equals(config.getProperty("running.in.cloud", "false"));
            File tmpRoot = new File(config.getProperty("transient.data.store", "/tmp/reactive"));
            if (runningInCloud) {
                dbFolder = tmpRoot;
            } else {
                String instanceId = platform.getName() + "-" + platform.getOrigin();
                dbFolder = new File(tmpRoot, instanceId);
            }
            scanExpiredStores(tmpRoot);
            setupCommitLog(dbFolder);
        }
    }

    public String getId() {
        return id;
    }

    @Override
    public void close() {
        if (!isClosed()) {
            if (dbEnv != null) {
                if (readCounter < writeCounter && writeCounter > MEMORY_BUFFER) {
                    try {
                        PostOffice.getInstance().send(CLEAN_UP_TASK, id + SLASH + currentVersion);
                    } catch (IOException e) {
                        log.error("Unable to run {} - {}", CLEAN_UP_TASK, e.getMessage());
                    }
                } else {
                    dbEnv.cleanLog();
                }
            }
            resetCounter();
        }
    }

    /**
     * This method may be called when the route supported by this elastic queue is no longer in service
     */
    public void destroy() {
        close();
        if (dbEnv != null) {
            // perform final clean up
            try {
                PostOffice.getInstance().send(CLEAN_UP_TASK, id);
            } catch (IOException e) {
                log.error("Unable to run {} - {}", CLEAN_UP_TASK, e.getMessage());
            }
        }
    }

    public boolean isClosed() {
        return writeCounter == 0;
    }

    private static void shutdown() {
        if (db != null && dbEnv != null) {
            if (alive != null) {
                alive.shutdown();
            }
            try {
                db.close();
            } catch (Exception e) {
                log.warn("Exception while closing - {}", e.getMessage());
            }
            try {
                dbEnv.close();
            } catch (Exception e) {
                log.warn("Exception while closing - {}", e.getMessage());
            }
            util.cleanupDir(dbFolder, runningInCloud);
            log.info("Holding area {} cleared", dbFolder);
        }
    }

    private void resetCounter() {
        if (!empty) {
            empty = true;
            readCounter = writeCounter = 0;
            memory.clear();
            currentVersion = generation.incrementAndGet();
        }
    }

    private void setupCommitLog(File dir) {
        try {
            if (!dir.exists()) {
                if (dir.mkdirs()) {
                    log.debug("{} created", dir);
                }
            }
            long t1 = System.currentTimeMillis();
            dbEnv = new Environment(dir,
                    new EnvironmentConfig()
                            .setAllowCreate(true)
                            .setConfigParam(EnvironmentConfig.MAX_DISK, "0")
                            .setConfigParam(EnvironmentConfig.FREE_DISK, "0"));
            dbEnv.checkpoint(new CheckpointConfig().setMinutes(1));
            db = dbEnv.openDatabase(null, "kv",
                    new DatabaseConfig().setAllowCreate(true).setTemporary(false));
            long diff = System.currentTimeMillis() - t1;
            alive = new KeepAlive(dir);
            alive.start();
            log.info("Created holding area {} in {} ms", dir, diff);

        } catch (Exception e) {
            log.error("Unable to create holding area in {} - {}", dir, e.getMessage());
            System.exit(-1);
        }
    }

    public void write(byte[] event) {
        if (writeCounter < MEMORY_BUFFER) {
            // for highest performance, save to memory for the first few blocks
            memory.offer(event);
        } else {
            // otherwise, save to disk
            String key = id + SLASH + currentVersion + SLASH + util.zeroFill(writeCounter, MAX_EVENTS);
            DatabaseEntry k = new DatabaseEntry(util.getUTF(key));
            DatabaseEntry v = new DatabaseEntry(event);
            db.put(null, k, v);
        }
        writeCounter++;
        empty = false;
    }

    public byte[] peek() {
        if (peeked != null) {
            return peeked;
        }
        peeked = read();
        return peeked;
    }

    public byte[] read() {
        if (peeked != null) {
            byte[] result = peeked;
            peeked = null;
            return result;
        }
        if (readCounter >= writeCounter) {
            // catch up with writes and thus nothing to read
            close();
            return null;
        }
        if (readCounter < MEMORY_BUFFER) {
            byte[] event = memory.poll();
            if (event != null) {
                readCounter++;
            }
            return event;
        }
        boolean hasRecord = false;
        String key = id + SLASH + currentVersion + SLASH + util.zeroFill(readCounter, MAX_EVENTS);
        DatabaseEntry k = new DatabaseEntry(util.getUTF(key));
        DatabaseEntry v = new DatabaseEntry();
        try {
            OperationStatus status = db.get(null, k, v, LockMode.DEFAULT);
            if (status == OperationStatus.SUCCESS) {
                // must be an exact match
                String ks = util.getUTF(k.getData());
                if (ks.equals(key)) {
                    hasRecord = true;
                    readCounter++;
                    return v.getData();
                } else {
                    log.error("Expected {}, Actual: {}", key, ks);
                }
            }
            return null;
        } finally {
            if (hasRecord) {
                db.delete(null, k);
            }
        }
    }

    private void scanExpiredStores(File tmpRoot) {
        if (runningInCloud) {
            removeExpiredStore(tmpRoot);
        } else {
            File[] dirs = tmpRoot.listFiles();
            if (dirs != null) {
                for (File d : dirs) {
                    if (d.isDirectory()) {
                        removeExpiredStore(d);
                    }
                }
            }
        }
    }

    private void removeExpiredStore(File folder) {
        Utility util = Utility.getInstance();
        File f = new File(folder, RUNNING);
        if (f.exists() && System.currentTimeMillis() - f.lastModified() > 60000) {
            util.cleanupDir(folder, runningInCloud);
            log.info("Holding area {} expired", folder);
        }
    }

    private static class Cleanup implements LambdaFunction {

        @Override
        public Object handleEvent(Map<String, String> headers, Object body, int instance) {
            if (body instanceof String && db != null && dbEnv != null) {
                Utility util = Utility.getInstance();
                int n = 0;
                String prefix = body + SLASH;
                DatabaseEntry k = new DatabaseEntry(util.getUTF(prefix));
                DatabaseEntry v = new DatabaseEntry();
                try (Cursor cursor = db.openCursor(null, new CursorConfig())) {
                    OperationStatus status = cursor.getSearchKeyRange(k, v, LockMode.DEFAULT);
                    while (status == OperationStatus.SUCCESS) {
                        String ks = util.getUTF(k.getData());
                        if (!ks.startsWith(prefix)) {
                            break;
                        }
                        db.delete(null, k);
                        n++;
                        status = cursor.getNext(k, v, LockMode.DEFAULT);
                    }
                    if (n > 0) {
                        dbEnv.cleanLog();
                        log.info("Cleared {} unread event{} for {}", n, n == 1? "" : "s", body);
                    }
                } catch (Exception e) {
                    log.warn("Unable to scan {} - {}", body, e.getMessage());
                }
                // remove older statistics files except current one
                List<File> outdated = new ArrayList<>();
                File[] files = dbFolder.listFiles();
                if (files != null) {
                    long now = System.currentTimeMillis();
                    for (File f : files) {
                        String name = f.getName();
                        if (name.startsWith("je.stat.") && name.endsWith(".csv") && !name.equals("je.stat.csv")) {
                            if (now - f.lastModified() > ONE_DAY) {
                                outdated.add(f);
                            }
                        }
                    }
                    for (File f : outdated) {
                        if (f.delete()) {
                            log.info("Outdated {} deleted", f);
                        }
                    }
                }
            }
            return true;
        }
    }

    private static class KeepAlive extends Thread {

        private static final long INTERVAL = 20000;
        private boolean normal = true;
        private final File dir;

        public KeepAlive(File dir) {
            this.dir = dir;
        }

        @Override
        public void run() {
            log.info("Commit log started");
            long t1 = 0;
            while (normal) {
                long now = System.currentTimeMillis();
                if (now - t1 > INTERVAL) {
                    t1 = now;
                    util.str2file(new File(dir, RUNNING), util.getTimestamp());
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // ok to ignore
                }
            }
            log.info("Commit log stopped");
        }

        public void shutdown() {
            normal = false;
        }
    }

}
