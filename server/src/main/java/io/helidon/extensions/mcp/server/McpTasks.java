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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.common.context.Context;
import io.helidon.config.Config;
import io.helidon.service.registry.Service;

import static io.helidon.jsonrpc.core.JsonRpcError.INTERNAL_ERROR;
import static io.helidon.jsonrpc.core.JsonRpcError.INVALID_PARAMS;

@Service.Singleton
final class McpTasks {
    static final int DEFAULT_PAGE_SIZE = 100;
    static final long DEFAULT_POLL_INTERVAL = 1000;
    static final long MIN_TTL = TimeUnit.SECONDS.toMillis(1);
    static final long DEFAULT_TTL = TimeUnit.HOURS.toMillis(1);
    static final long MAX_TTL = TimeUnit.DAYS.toMillis(1);
    static final int MAX_TASKS = 1000;
    static final int MAX_TASKS_PER_SESSION = 200;

    private static final ScheduledThreadPoolExecutor EXPIRER = createExpirer();

    private final ConcurrentMap<String, McpTask> tasks = new ConcurrentHashMap<>();
    private final Set<McpTask> activeExecutions = ConcurrentHashMap.newKeySet();
    private final int pageSize;
    private final long pollInterval;
    private final long minTtl;
    private final long defaultTtl;
    private final long maxTtl;
    private final int maxTasks;
    private final int maxTasksPerSession;
    private final ReentrantLock lock = new ReentrantLock();

    McpTasks() {
        this(McpTasksConfig.create());
    }

    @Service.Inject
    McpTasks(Config config) {
        this(McpTasksConfig.create(config.get(McpTasksConfigBlueprint.CONFIG_ROOT)));
    }

    McpTasks(int maxTasks, int maxTasksPerSession) {
        this(McpTasksConfig.builder()
                     .maxTasks(maxTasks)
                     .maxTasksPerSession(maxTasksPerSession)
                     .buildPrototype());
    }

    McpTasks(McpTasksConfig config) {
        this.pageSize = config.pageSize();
        this.pollInterval = config.pollInterval().toMillis();
        this.minTtl = config.minTtl().toMillis();
        this.defaultTtl = config.defaultTtl().toMillis();
        this.maxTtl = config.maxTtl().toMillis();
        this.maxTasks = config.maxTasks();
        this.maxTasksPerSession = config.maxTasksPerSession();
    }

    McpTask create(McpSession session, Context requestContext) {
        lock.lock();
        try {
            return createTask(new McpTaskOwner(session, requestContext), defaultTtl);
        } finally {
            lock.unlock();
        }
    }

    McpTask create(McpSession session, Context requestContext, long ttl) {
        lock.lock();
        try {
            return createTask(new McpTaskOwner(session, requestContext), Math.clamp(ttl, minTtl, maxTtl));
        } finally {
            lock.unlock();
        }
    }

    McpTask get(String taskId, McpSession session, Context requestContext) {
        McpTask task = tasks.get(taskId);
        if (task == null) {
            throw new McpInternalException(INVALID_PARAMS, "Task not found: " + taskId);
        }
        if (task.expired(System.currentTimeMillis())) {
            expire(task);
            throw new McpInternalException(INVALID_PARAMS, "Task not found: " + taskId);
        }
        if (!task.ownedBy(new McpTaskOwner(session, requestContext))) {
            throw new McpInternalException(INVALID_PARAMS, "Task not found: " + taskId);
        }
        return task;
    }

    McpTask cancel(String taskId, McpSession session, Context requestContext) {
        lock.lock();
        try {
            McpTask task = get(taskId, session, requestContext);
            if (!task.cancel()) {
                throw new McpInternalException(INVALID_PARAMS, "Task is already in a terminal state: " + taskId);
            }
            tasks.remove(taskId, task);
            task.delete();
            task.fail(INVALID_PARAMS, "Task not found: " + taskId);
            task.cancelExecution("The task was cancelled by request.");
            return task;
        } finally {
            lock.unlock();
        }
    }

    void remove(McpSession session) {
        lock.lock();
        try {
            for (McpTask task : tasks.values()) {
                if (task.ownedBy(session.id()) && tasks.remove(task.id(), task)) {
                    task.expire();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    McpPage<McpTask> list(McpSession session, Context requestContext) {
        return pagination(new McpTaskOwner(session, requestContext)).firstPage();
    }

    McpPage<McpTask> list(McpSession session, Context requestContext, String cursor) {
        McpPage<McpTask> page = pagination(new McpTaskOwner(session, requestContext)).page(cursor);
        if (page == null) {
            throw new McpInternalException(INVALID_PARAMS, "Invalid task cursor: " + cursor);
        }
        return page;
    }

    void start(McpTask task, Thread executionThread, McpFeatures features) {
        lock.lock();
        try {
            activeExecutions.add(task);
            boolean started = false;
            try {
                started = task.start(executionThread, features);
            } finally {
                if (!started) {
                    activeExecutions.remove(task);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    void executionFinished(McpTask task) {
        lock.lock();
        try {
            activeExecutions.remove(task);
            task.executionFinished();
        } finally {
            lock.unlock();
        }
    }

    private McpTask createTask(McpTaskOwner owner, long ttl) {
        cleanupExpired();
        Set<McpTask> retainedTasks = retainedTasks();
        if (retainedTasks.size() >= maxTasks) {
            throw new McpInternalException(INTERNAL_ERROR, "Task capacity reached");
        }
        long sessionTaskCount = retainedTasks.stream()
                .filter(task -> task.ownedBy(owner.sessionId()))
                .count();
        if (sessionTaskCount >= maxTasksPerSession) {
            throw new McpInternalException(INTERNAL_ERROR, "Task capacity reached for this session");
        }

        McpTask task;
        do {
            String id = UUID.randomUUID().toString();
            task = new McpTask(id, owner, ttl, pollInterval);
        } while (tasks.putIfAbsent(task.id(), task) != null);

        McpTask expiringTask = task;
        task.expiration(EXPIRER.schedule(() -> expire(expiringTask), ttl, TimeUnit.MILLISECONDS));
        return task;
    }

    private McpPagination<McpTask> pagination(McpTaskOwner owner) {
        cleanupExpired();
        List<McpTask> visibleTasks = tasks.values().stream()
                .filter(task -> task.ownedBy(owner))
                .sorted(Comparator.comparing(McpTask::createdAt).thenComparing(McpTask::id))
                .toList();
        return new McpMutablePagination(visibleTasks, pageSize);
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        for (McpTask task : tasks.values()) {
            if (task.expired(now)) {
                expire(task);
            }
        }
    }

    private void expire(McpTask task) {
        lock.lock();
        try {
            if (tasks.remove(task.id(), task)) {
                task.expire();
            }
        } finally {
            lock.unlock();
        }
    }

    private Set<McpTask> retainedTasks() {
        Set<McpTask> retainedTasks = new HashSet<>(tasks.values());
        retainedTasks.addAll(activeExecutions);
        return retainedTasks;
    }

    private static ScheduledThreadPoolExecutor createExpirer() {
        var executor = new ScheduledThreadPoolExecutor(1,
                                                        Thread.ofPlatform()
                                                                .daemon()
                                                                .name("mcp-task-expirer")
                                                                .factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

}
