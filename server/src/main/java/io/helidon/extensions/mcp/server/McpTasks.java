/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.extensions.mcp.server;

import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.helidon.common.context.Context;
import io.helidon.service.registry.Services;

import static io.helidon.jsonrpc.core.JsonRpcError.INTERNAL_ERROR;
import static io.helidon.jsonrpc.core.JsonRpcError.INVALID_PARAMS;

final class McpTasks {
    private final Map<String, McpTask> tasks = new HashMap<>();
    private final Map<McpTask, TaskEntry> retainedTasks = new IdentityHashMap<>();
    private final McpTaskCoordinator coordinator;
    private final int pageSize;
    private final long pollInterval;
    private final long minTtl;
    private final long defaultTtl;
    private final long maxTtl;
    private final int maxTasksPerSession;
    private final Consumer<McpTask> taskAdded;
    private final Predicate<McpTask> taskCancellation;
    private final Consumer<McpTask> taskExpiration;
    private final Consumer<McpTask> taskRemoved;
    private final Lock lock = new ReentrantLock();

    private boolean active = true;

    McpTasks() {
        this(Services.get(McpTaskCoordinator.class), task -> { }, McpTask::cancel, McpTask::expire, task -> { });
    }

    McpTasks(McpTaskCoordinator coordinator) {
        this(coordinator, task -> { }, McpTask::cancel, McpTask::expire, task -> { });
    }

    McpTasks(McpTaskCoordinator coordinator,
             Consumer<McpTask> taskAdded,
             Predicate<McpTask> taskCancellation,
             Consumer<McpTask> taskExpiration,
             Consumer<McpTask> taskRemoved) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.taskAdded = Objects.requireNonNull(taskAdded);
        this.taskCancellation = Objects.requireNonNull(taskCancellation);
        this.taskExpiration = Objects.requireNonNull(taskExpiration);
        this.taskRemoved = Objects.requireNonNull(taskRemoved);
        McpTasksConfig config = coordinator.config();
        this.pageSize = config.pageSize();
        this.pollInterval = config.pollInterval().toMillis();
        this.minTtl = config.minTtl().toMillis();
        this.defaultTtl = config.defaultTtl().toMillis();
        this.maxTtl = config.maxTtl().toMillis();
        this.maxTasksPerSession = config.maxTasksPerSession();
    }

    McpTask create(Context requestContext) {
        lock.lock();
        try {
            ensureActive();
            return registerTask(new McpTaskOwner(requestContext), defaultTtl);
        } finally {
            lock.unlock();
        }
    }

    McpTask create(Context requestContext, long ttl) {
        lock.lock();
        try {
            ensureActive();
            return registerTask(new McpTaskOwner(requestContext), Math.clamp(ttl, minTtl, maxTtl));
        } finally {
            lock.unlock();
        }
    }

    McpTask get(String taskId, Context requestContext) {
        lock.lock();
        try {
            ensureActive();
            return getTask(taskId, new McpTaskOwner(requestContext));
        } finally {
            lock.unlock();
        }
    }

    McpTask cancel(String taskId, Context requestContext) {
        lock.lock();
        try {
            ensureActive();
            McpTask task = getTask(taskId, new McpTaskOwner(requestContext));
            if (!taskCancellation.test(task)) {
                throw new McpInternalException(INVALID_PARAMS, "Task is already in a terminal state: " + taskId);
            }
            TaskEntry entry = retainedTasks.get(task);
            unregister(entry);
            try {
                task.delete();
                task.cancelExecution("The task was cancelled by request.");
            } finally {
                taskRemoved.accept(task);
                releaseIfUnused(entry);
            }
            return task;
        } finally {
            lock.unlock();
        }
    }

    McpPage<McpTask> list(Context requestContext) {
        return pagination(new McpTaskOwner(requestContext)).firstPage();
    }

    McpPage<McpTask> list(Context requestContext, String cursor) {
        McpPage<McpTask> page = pagination(new McpTaskOwner(requestContext)).page(cursor);
        if (page == null) {
            throw new McpInternalException(INVALID_PARAMS, "Invalid task cursor: " + cursor);
        }
        return page;
    }

    void start(McpTask task, Thread executionThread, McpFeatures features) {
        lock.lock();
        try {
            ensureActive();
            TaskEntry entry = retainedTasks.get(task);
            if (entry == null || !entry.registered || tasks.get(task.id()) != task) {
                throw new McpInternalException(INVALID_PARAMS, "Task not found: " + task.id());
            }
            if (entry.executing) {
                throw new McpInternalException(INTERNAL_ERROR, "Task execution already started: " + task.id());
            }
            entry.executing = true;
            boolean started = false;
            try {
                started = task.start(executionThread, features);
            } finally {
                if (!started) {
                    entry.executing = false;
                    releaseIfUnused(entry);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    void executionFinished(McpTask task) {
        lock.lock();
        try {
            task.executionFinished();
            TaskEntry entry = retainedTasks.get(task);
            if (entry != null) {
                entry.executing = false;
                releaseIfUnused(entry);
            }
        } finally {
            lock.unlock();
        }
    }

    void close() {
        lock.lock();
        try {
            if (!active) {
                return;
            }
            active = false;
            for (McpTask task : List.copyOf(tasks.values())) {
                expireTask(task);
            }
        } finally {
            lock.unlock();
        }
    }

    private McpTask registerTask(McpTaskOwner owner, long ttl) {
        cleanupExpired();
        if (retainedTasks.size() >= maxTasksPerSession) {
            throw new McpInternalException(INTERNAL_ERROR, "Task capacity reached for this session");
        }

        McpTask task;
        do {
            task = McpTask.create(owner, ttl, pollInterval);
            if (coordinator.tryReserve(task.id())) {
                break;
            }
        } while (true);

        TaskEntry entry = new TaskEntry(task);
        tasks.put(task.id(), task);
        retainedTasks.put(task, entry);
        try {
            taskAdded.accept(task);
            McpTask expiringTask = task;
            task.expiration(coordinator.scheduleExpiration(() -> expire(expiringTask), ttl));
            return task;
        } catch (RuntimeException | Error e) {
            tasks.remove(task.id(), task);
            retainedTasks.remove(task, entry);
            try {
                taskRemoved.accept(task);
            } finally {
                task.delete();
                coordinator.release(task.id());
            }
            throw e;
        }
    }

    private McpPagination<McpTask> pagination(McpTaskOwner owner) {
        lock.lock();
        try {
            ensureActive();
            cleanupExpired();
            List<McpTask> visibleTasks = tasks.values().stream()
                    .filter(task -> task.ownedBy(owner))
                    .sorted(Comparator.comparing(McpTask::createdAt).thenComparing(McpTask::id))
                    .toList();
            return new McpMutablePagination(visibleTasks, pageSize);
        } finally {
            lock.unlock();
        }
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        for (McpTask task : List.copyOf(tasks.values())) {
            if (task.expired(now)) {
                expireTask(task);
            }
        }
    }

    private void expire(McpTask task) {
        lock.lock();
        try {
            expireTask(task);
        } finally {
            lock.unlock();
        }
    }

    private void expireTask(McpTask task) {
        TaskEntry entry = retainedTasks.get(task);
        if (entry == null || !entry.registered) {
            return;
        }
        unregister(entry);
        try {
            taskExpiration.accept(task);
        } finally {
            taskRemoved.accept(task);
            releaseIfUnused(entry);
        }
    }

    private McpTask getTask(String taskId, McpTaskOwner owner) {
        McpTask task = tasks.get(taskId);
        if (task == null) {
            throw new McpInternalException(INVALID_PARAMS, "Task not found: " + taskId);
        }
        if (task.expired(System.currentTimeMillis())) {
            expireTask(task);
            throw new McpInternalException(INVALID_PARAMS, "Task not found: " + taskId);
        }
        if (!task.ownedBy(owner)) {
            throw new McpInternalException(INVALID_PARAMS, "Task not found: " + taskId);
        }
        return task;
    }

    private void unregister(TaskEntry entry) {
        if (entry.registered) {
            tasks.remove(entry.task.id(), entry.task);
            entry.registered = false;
        }
    }

    private void releaseIfUnused(TaskEntry entry) {
        if (!entry.registered && !entry.executing && retainedTasks.remove(entry.task, entry)) {
            coordinator.release(entry.task.id());
        }
    }

    private void ensureActive() {
        if (!active) {
            throw new McpInternalException("Session disconnected");
        }
    }

    private static final class TaskEntry {
        private final McpTask task;
        private boolean registered = true;
        private boolean executing;

        private TaskEntry(McpTask task) {
            this.task = task;
        }
    }
}
