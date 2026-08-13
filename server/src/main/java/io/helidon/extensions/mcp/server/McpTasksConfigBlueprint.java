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

import java.time.Duration;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Application-wide MCP task configuration.
 */
@Prototype.Blueprint(isPublic = false, decorator = McpTasksSupport.class)
@Prototype.Configured(McpTasksConfigBlueprint.CONFIG_ROOT)
interface McpTasksConfigBlueprint {
    String CONFIG_ROOT = "mcp.server.tasks";

    /**
     * Page size used when listing tasks. A value of {@code 0} disables pagination.
     *
     * @return task page size
     */
    @Option.Configured
    @Option.DefaultInt(100)
    int pageSize();

    /**
     * Poll interval advertised to clients. A value of {@link Duration#ZERO} omits the poll interval.
     *
     * @return task poll interval
     */
    @Option.Configured
    @Option.Default("PT1S")
    Duration pollInterval();

    /**
     * Minimum task time to live.
     *
     * @return minimum task time to live
     */
    @Option.Configured
    @Option.Default("PT1S")
    Duration minTtl();

    /**
     * Default task time to live.
     *
     * @return default task time to live
     */
    @Option.Configured
    @Option.Default("PT1H")
    Duration defaultTtl();

    /**
     * Maximum task time to live.
     *
     * @return maximum task time to live
     */
    @Option.Configured
    @Option.Default("PT24H")
    Duration maxTtl();

    /**
     * Maximum number of retained tasks across all MCP sessions.
     *
     * @return maximum number of tasks
     */
    @Option.Configured
    @Option.DefaultInt(1000)
    int maxTasks();

    /**
     * Maximum number of retained tasks for one MCP session.
     *
     * @return maximum number of tasks per session
     */
    @Option.Configured
    @Option.DefaultInt(200)
    int maxTasksPerSession();
}
