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
import java.util.Map;
import java.util.stream.Stream;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpTasksConfigurationTest {
    @Test
    void loadsTaskConfigurationFromGlobalRoot() {
        Config root = Config.just(ConfigSources.create(Map.of(
                "mcp.server.tasks.page-size", "2",
                "mcp.server.tasks.poll-interval", "PT0.25S",
                "mcp.server.tasks.min-ttl", "PT2S",
                "mcp.server.tasks.default-ttl", "PT3S",
                "mcp.server.tasks.max-ttl", "PT4S",
                "mcp.server.tasks.max-tasks", "5",
                "mcp.server.tasks.max-tasks-per-session", "2")));

        McpTasksConfig config = McpTasksConfig.create(root.get(McpTasksConfigBlueprint.CONFIG_ROOT));

        assertThat(McpTasksConfigBlueprint.CONFIG_ROOT, is("mcp.server.tasks"));
        assertThat(config.pageSize(), is(2));
        assertThat(config.pollInterval(), is(Duration.ofMillis(250)));
        assertThat(config.minTtl(), is(Duration.ofSeconds(2)));
        assertThat(config.defaultTtl(), is(Duration.ofSeconds(3)));
        assertThat(config.maxTtl(), is(Duration.ofSeconds(4)));
        assertThat(config.maxTasks(), is(5));
        assertThat(config.maxTasksPerSession(), is(2));
    }

    @Test
    void usesDefaultTaskConfiguration() {
        McpTasksConfig config = McpTasksConfig.create();

        assertThat(config.pageSize(), is(100));
        assertThat(config.pollInterval(), is(Duration.ofSeconds(1)));
        assertThat(config.minTtl(), is(Duration.ofSeconds(1)));
        assertThat(config.defaultTtl(), is(Duration.ofHours(1)));
        assertThat(config.maxTtl(), is(Duration.ofHours(24)));
        assertThat(config.maxTasks(), is(1000));
        assertThat(config.maxTasksPerSession(), is(200));
    }

    @Test
    void acceptsDisabledPaginationAndPollingHint() {
        McpTasksConfig config = create(Map.of(
                "page-size", "0",
                "poll-interval", "PT0S"));

        assertThat(config.pageSize(), is(0));
        assertThat(config.pollInterval(), is(Duration.ZERO));
    }

    @Test
    void rejectsNegativePageSize() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> create(Map.of("page-size", "-1")));

        assertThat(exception.getMessage(), is("Task page size must not be negative"));
    }

    @Test
    void rejectsNegativePollInterval() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> create(Map.of("poll-interval", "-PT0.001S")));

        assertThat(exception.getMessage(), is("Task poll interval must not be negative"));
    }

    @Test
    void rejectsPositiveSubMillisecondPollInterval() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> create(Map.of("poll-interval", "PT0.0001S")));

        assertThat(exception.getMessage(), is("Task poll interval must be zero or at least one millisecond"));
    }

    @ParameterizedTest(name = "rejects non-positive {0}")
    @MethodSource("nonPositiveTtlValues")
    void rejectsNonPositiveTtl(String key, String value, String message) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> create(Map.of(key, value)));

        assertThat(exception.getMessage(), is(message));
    }

    @Test
    void rejectsMinimumTtlAboveMaximumTtl() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> create(Map.of(
                                                                   "min-ttl", "PT5S",
                                                                   "default-ttl", "PT5S",
                                                                   "max-ttl", "PT4S")));

        assertThat(exception.getMessage(), is("Task minimum TTL must not exceed maximum TTL"));
    }

    @ParameterizedTest(name = "rejects default TTL {0} configured range")
    @ValueSource(strings = {"PT1S", "PT5S"})
    void rejectsDefaultTtlOutsideConfiguredRange(String defaultTtl) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> create(Map.of(
                                                                   "min-ttl", "PT2S",
                                                                   "default-ttl", defaultTtl,
                                                                   "max-ttl", "PT4S")));

        assertThat(exception.getMessage(), is("Task default TTL must be between minimum and maximum TTL"));
    }

    @Test
    void rejectsDurationThatCannotBeRepresentedInMilliseconds() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> create(Map.of("max-ttl", "P106751991168D")));

        assertThat(exception.getMessage(), is("Task maximum TTL is too large"));
    }

    @ParameterizedTest(name = "rejects maximum task count {0}")
    @ValueSource(ints = {-1, 0})
    void rejectsNonPositiveMaximumTaskCount(int value) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> create(Map.of("max-tasks", Integer.toString(value))));

        assertThat(exception.getMessage(), is("Maximum task count must be greater than zero"));
    }

    @ParameterizedTest(name = "rejects maximum task count per session {0}")
    @ValueSource(ints = {-1, 0})
    void rejectsNonPositiveMaximumTaskCountPerSession(int value) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> create(Map.of("max-tasks-per-session", Integer.toString(value))));

        assertThat(exception.getMessage(), is("Maximum task count per session must be greater than zero"));
    }

    @Test
    void rejectsPerSessionCapacityAboveGlobalCapacity() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> create(Map.of(
                                                                   "max-tasks", "2",
                                                                   "max-tasks-per-session", "3")));

        assertThat(exception.getMessage(), is("Maximum task count per session must not exceed maximum task count"));
    }

    private static Stream<Arguments> nonPositiveTtlValues() {
        return Stream.of(
                Arguments.of("min-ttl", "PT0S", "Task minimum TTL must be greater than zero"),
                Arguments.of("default-ttl", "-PT0.001S", "Task default TTL must be greater than zero"),
                Arguments.of("max-ttl", "PT0.0001S", "Task maximum TTL must be greater than zero"));
    }

    private McpTasksConfig create(Map<String, String> values) {
        Config taskConfig = Config.just(ConfigSources.create(values));
        return McpTasksConfig.create(taskConfig);
    }
}
