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

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.config.Config;
import io.helidon.service.registry.Service;

import static io.helidon.jsonrpc.core.JsonRpcError.INTERNAL_ERROR;

@Service.Singleton
final class McpTaskCoordinator {
    private static final ScheduledThreadPoolExecutor EXPIRER = createExpirer();

    private final Set<String> taskIds = new HashSet<>();
    private final McpTasksConfig config;
    private final Lock lock = new ReentrantLock();

    McpTaskCoordinator() {
        this(McpTasksConfig.create());
    }

    @Service.Inject
    McpTaskCoordinator(Config config) {
        this(McpTasksConfig.create(config.get(McpTasksConfigBlueprint.CONFIG_ROOT)));
    }

    McpTaskCoordinator(McpTasksConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    McpTasksConfig config() {
        return config;
    }

    boolean tryReserve(String taskId) {
        Objects.requireNonNull(taskId);
        lock.lock();
        try {
            if (taskIds.contains(taskId)) {
                return false;
            }
            if (taskIds.size() >= config.maxTasks()) {
                throw new McpInternalException(INTERNAL_ERROR, "Task capacity reached");
            }
            taskIds.add(taskId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    Future<?> scheduleExpiration(Runnable expiration, long ttl) {
        return EXPIRER.schedule(expiration, ttl, TimeUnit.MILLISECONDS);
    }

    void release(String taskId) {
        lock.lock();
        try {
            taskIds.remove(taskId);
        } finally {
            lock.unlock();
        }
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
