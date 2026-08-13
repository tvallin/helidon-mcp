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

import io.helidon.builder.api.Prototype;

/**
 * Validates the application-wide task configuration before it is built.
 */
final class McpTasksSupport implements Prototype.BuilderDecorator<McpTasksConfig.BuilderBase<?, ?>> {
    @Override
    public void decorate(McpTasksConfig.BuilderBase<?, ?> builder) {
        if (builder.pageSize() < 0) {
            throw new IllegalArgumentException("Task page size must not be negative");
        }
        validateNonNegativeDuration(builder.pollInterval(), "Task poll interval");
        validatePositiveDuration(builder.minTtl(), "Task minimum TTL");
        validatePositiveDuration(builder.defaultTtl(), "Task default TTL");
        validatePositiveDuration(builder.maxTtl(), "Task maximum TTL");

        if (builder.minTtl().compareTo(builder.maxTtl()) > 0) {
            throw new IllegalArgumentException("Task minimum TTL must not exceed maximum TTL");
        }
        if (builder.defaultTtl().compareTo(builder.minTtl()) < 0
                || builder.defaultTtl().compareTo(builder.maxTtl()) > 0) {
            throw new IllegalArgumentException("Task default TTL must be between minimum and maximum TTL");
        }
        if (builder.maxTasks() <= 0) {
            throw new IllegalArgumentException("Maximum task count must be greater than zero");
        }
        if (builder.maxTasksPerSession() <= 0) {
            throw new IllegalArgumentException("Maximum task count per session must be greater than zero");
        }
        if (builder.maxTasksPerSession() > builder.maxTasks()) {
            throw new IllegalArgumentException("Maximum task count per session must not exceed maximum task count");
        }
    }

    private void validateNonNegativeDuration(Duration duration, String name) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        long milliseconds = validateMillisecondRange(duration, name);
        if (!duration.isZero() && milliseconds == 0) {
            throw new IllegalArgumentException(name + " must be zero or at least one millisecond");
        }
    }

    private void validatePositiveDuration(Duration duration, String name) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        if (validateMillisecondRange(duration, name) == 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }

    private long validateMillisecondRange(Duration duration, String name) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " is too large", e);
        }
    }
}
