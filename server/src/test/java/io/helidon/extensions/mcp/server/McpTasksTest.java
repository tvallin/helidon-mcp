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

import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.helidon.common.context.Context;
import io.helidon.common.security.SecurityContext;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.json.JsonObject;
import io.helidon.service.registry.Services;

import org.junit.jupiter.api.Test;

import static io.helidon.jsonrpc.core.JsonRpcError.INTERNAL_ERROR;
import static io.helidon.jsonrpc.core.JsonRpcError.INVALID_PARAMS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpTasksTest {
    @Test
    void resolvesAsSingletonService() {
        assertThat(Services.get(McpTasks.class), sameInstance(Services.get(McpTasks.class)));
    }

    @Test
    void createsWorkingTaskWithDefaultRetention() {
        McpSession session = session("one");
        McpTask task = new McpTasks().create(session, requestContext());

        JsonObject json = task.toJson();
        assertThat(json.stringValue("taskId").orElseThrow(), is(task.id()));
        assertThat(json.stringValue("status").orElseThrow(), is("working"));
        assertThat(json.numberValue("ttl").orElseThrow().longValue(), is(McpTasks.DEFAULT_TTL));
        assertThat(json.numberValue("pollInterval").orElseThrow().longValue(),
                   is(McpTasks.DEFAULT_POLL_INTERVAL));
        assertThat(json.stringValue("createdAt").orElseThrow(), is(not("")));
        assertThat(json.stringValue("lastUpdatedAt").orElseThrow(), is(not("")));
    }

    @Test
    void createsTaskWithConfiguredDefaultTtlAndPollInterval() {
        McpTasks tasks = new McpTasks(McpTasksConfig.builder()
                                              .pollInterval(Duration.ofMillis(250))
                                              .minTtl(Duration.ofSeconds(2))
                                              .defaultTtl(Duration.ofSeconds(3))
                                              .maxTtl(Duration.ofSeconds(4))
                                              .buildPrototype());
        McpSession session = session("one");

        try {
            JsonObject json = tasks.create(session, requestContext()).toJson();

            assertThat(json.numberValue("ttl").orElseThrow().longValue(), is(3000L));
            assertThat(json.numberValue("pollInterval").orElseThrow().longValue(), is(250L));
        } finally {
            tasks.remove(session);
        }
    }

    @Test
    void readsTaskConfigurationFromGlobalConfigRoot() {
        Config root = Config.just(ConfigSources.create(Map.of(
                "mcp.server.tasks.poll-interval", "PT0.25S",
                "mcp.server.tasks.default-ttl", "PT2S")));
        McpTasks tasks = new McpTasks(root);
        McpSession session = session("one");

        try {
            JsonObject json = tasks.create(session, requestContext()).toJson();

            assertThat(json.numberValue("ttl").orElseThrow().longValue(), is(2000L));
            assertThat(json.numberValue("pollInterval").orElseThrow().longValue(), is(250L));
        } finally {
            tasks.remove(session);
        }
    }

    @Test
    void completesTaskAndAddsRelatedMetadata() {
        McpTask task = new McpTasks().create(session("one"), requestContext(), 5000);
        JsonObject result = JsonObject.builder().set("value", "done").build();

        task.complete(result, false);

        assertThat(task.toJson().stringValue("status").orElseThrow(), is("completed"));
        assertThat(task.toJson().numberValue("ttl").orElseThrow().longValue(), is(5000L));
        assertThat(task.awaitOutcome(), instanceOf(McpTask.ResultOutcome.class));
        JsonObject related = task.relatedResult(result);
        JsonObject metadata = related.objectValue("_meta").orElseThrow();
        JsonObject relatedTask = metadata.objectValue(McpTask.RELATED_TASK_META_KEY).orElseThrow();
        assertThat(relatedTask.stringValue("taskId").orElseThrow(), is(task.id()));
    }

    @Test
    void errorToolResultFailsTaskButPreservesResult() {
        McpTask task = new McpTasks().create(session("one"), requestContext());
        JsonObject result = JsonObject.builder().set("isError", true).build();

        task.complete(result, true);

        assertThat(task.toJson().stringValue("status").orElseThrow(), is("failed"));
        McpTask.ResultOutcome outcome = (McpTask.ResultOutcome) task.awaitOutcome();
        assertThat(outcome.result(), is(result));
    }

    @Test
    void cancellationDeletesTaskImmediately() {
        McpTasks tasks = new McpTasks();
        McpSession session = session("one");
        Context requestContext = requestContext();
        McpTask task = tasks.create(session, requestContext);

        tasks.cancel(task.id(), session, requestContext);

        assertThat(task.toJson().stringValue("status").orElseThrow(), is("cancelled"));
        McpInternalException getException = assertThrows(McpInternalException.class,
                                                         () -> tasks.get(task.id(), session, requestContext));
        assertThat(getException.code(), is(INVALID_PARAMS));
        assertThat(tasks.list(session, requestContext).components().isEmpty(), is(true));
        assertThrows(McpInternalException.class, () -> tasks.cancel(task.id(), session, requestContext));
    }

    @Test
    void cancellationUnblocksExistingResultWaiter() throws Exception {
        McpTasks tasks = new McpTasks();
        McpSession session = session("one");
        Context requestContext = requestContext();
        McpTask task = tasks.create(session, requestContext);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CompletableFuture<McpTask.Outcome> waiter = CompletableFuture.supplyAsync(() -> {
            waiterStarted.countDown();
            return task.awaitOutcome();
        });

        try {
            assertThat(waiterStarted.await(5, TimeUnit.SECONDS), is(true));

            tasks.cancel(task.id(), session, requestContext);

            McpTask.ErrorOutcome outcome = (McpTask.ErrorOutcome) waiter.get(5, TimeUnit.SECONDS);
            assertThat(outcome.code(), is(INVALID_PARAMS));
        } finally {
            task.fail(INVALID_PARAMS, "Test cleanup");
            waiter.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void throwingCancellationHookDoesNotFailCancellationOrSuppressInterrupt() throws Exception {
        McpTasks tasks = new McpTasks();
        McpSession session = session("one");
        Context requestContext = requestContext();
        McpTask task = tasks.create(session, requestContext);
        McpFeatures features = session.createFeatures(task.transport(), requestContext);
        AtomicBoolean hookCalled = new AtomicBoolean();
        CountDownLatch hookFinished = new CountDownLatch(1);
        features.cancellation().registerCancellationHook(() -> {
            hookCalled.set(true);
            hookFinished.countDown();
            throw new IllegalStateException("Hook failure");
        });
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch executionRelease = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean cancellationPublishedAtInterrupt = new AtomicBoolean();
        Thread executionThread = Thread.ofPlatform().unstarted(() -> {
            executionStarted.countDown();
            try {
                executionRelease.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                cancellationPublishedAtInterrupt.set(features.cancellation().result().isRequested());
                Thread.currentThread().interrupt();
            }
        });
        task.start(executionThread, features);

        try {
            assertThat(executionStarted.await(5, TimeUnit.SECONDS), is(true));

            assertDoesNotThrow(() -> tasks.cancel(task.id(), session, requestContext));

            executionThread.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(hookFinished.await(5, TimeUnit.SECONDS), is(true));
            assertThat(hookCalled.get(), is(true));
            assertThat(interrupted.get(), is(true));
            assertThat(cancellationPublishedAtInterrupt.get(), is(true));
        } finally {
            executionRelease.countDown();
            executionThread.interrupt();
            executionThread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void isolatesTasksBySession() {
        McpTasks tasks = new McpTasks();
        McpSession owner = session("owner");
        Context requestContext = requestContext();
        McpTask task = tasks.create(owner, requestContext);

        assertThat(tasks.get(task.id(), owner, requestContext), sameInstance(task));
        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> tasks.get(task.id(), session("other"), requestContext));
        assertThat(exception.code(), is(INVALID_PARAMS));
    }

    @Test
    void clampsRequestedTtl() {
        McpTasks tasks = new McpTasks();
        McpSession session = session("one");
        Context requestContext = requestContext();
        McpTask shortTask = tasks.create(session, requestContext, 0);
        McpTask longTask = tasks.create(session, requestContext, Long.MAX_VALUE);

        assertThat(shortTask.toJson().numberValue("ttl").orElseThrow().longValue(), is(McpTasks.MIN_TTL));
        assertThat(longTask.toJson().numberValue("ttl").orElseThrow().longValue(), is(McpTasks.MAX_TTL));
    }

    @Test
    void clampsRequestedTtlToConfiguredRange() {
        McpTasks tasks = new McpTasks(McpTasksConfig.builder()
                                              .minTtl(Duration.ofSeconds(2))
                                              .defaultTtl(Duration.ofSeconds(3))
                                              .maxTtl(Duration.ofSeconds(4))
                                              .buildPrototype());
        McpSession session = session("one");
        Context requestContext = requestContext();

        try {
            McpTask shortTask = tasks.create(session, requestContext, 1);
            McpTask longTask = tasks.create(session, requestContext, Long.MAX_VALUE);

            assertThat(shortTask.toJson().numberValue("ttl").orElseThrow().longValue(), is(2000L));
            assertThat(longTask.toJson().numberValue("ttl").orElseThrow().longValue(), is(4000L));
        } finally {
            tasks.remove(session);
        }
    }

    @Test
    void cancelledExecutionRetainsCapacityUntilWorkerExits() throws Exception {
        McpTasks tasks = new McpTasks(1, 1);
        McpSession session = session("one");
        Context requestContext = requestContext();
        McpTask task = tasks.create(session, requestContext);
        McpFeatures features = session.createFeatures(task.transport(), requestContext);
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch executionRelease = new CountDownLatch(1);
        Thread executionThread = Thread.ofVirtual().unstarted(() -> {
            try {
                executionStarted.countDown();
                while (executionRelease.getCount() != 0) {
                    try {
                        executionRelease.await();
                    } catch (InterruptedException e) {
                        // Deliberately ignore cancellation to verify retained execution capacity.
                    }
                }
            } finally {
                tasks.executionFinished(task);
            }
        });
        tasks.start(task, executionThread, features);

        try {
            assertThat(executionStarted.await(5, TimeUnit.SECONDS), is(true));
            tasks.cancel(task.id(), session, requestContext);

            assertThrows(McpInternalException.class, () -> tasks.create(session, requestContext));
        } finally {
            executionRelease.countDown();
            executionThread.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertDoesNotThrow(() -> tasks.create(session, requestContext));
    }

    @Test
    void paginatesTasksAndFiltersBySession() {
        McpTasks tasks = new McpTasks();
        McpSession owner = session("owner");
        McpSession other = session("other");
        Context requestContext = requestContext();
        for (int i = 0; i < McpTasks.DEFAULT_PAGE_SIZE + 1; i++) {
            tasks.create(owner, requestContext);
        }
        McpTask otherTask = tasks.create(other, requestContext);

        McpPage<McpTask> first = tasks.list(owner, requestContext);
        McpPage<McpTask> second = tasks.list(owner, requestContext, first.cursor());

        assertThat(first.components().size(), is(McpTasks.DEFAULT_PAGE_SIZE));
        assertThat(first.cursor(), is(not("")));
        assertThat(second.components().size(), is(1));
        assertThat(second.cursor(), is(""));
        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> tasks.list(owner, requestContext, otherTask.id()));
        assertThat(exception.code(), is(INVALID_PARAMS));
    }

    @Test
    void paginatesTasksUsingConfiguredPageSize() {
        McpTasks tasks = new McpTasks(McpTasksConfig.builder().pageSize(2).buildPrototype());
        McpSession owner = session("owner");
        McpSession other = session("other");
        Context requestContext = requestContext();
        try {
            tasks.create(owner, requestContext);
            tasks.create(owner, requestContext);
            tasks.create(owner, requestContext);
            tasks.create(other, requestContext);

            McpPage<McpTask> first = tasks.list(owner, requestContext);
            McpPage<McpTask> second = tasks.list(owner, requestContext, first.cursor());

            assertThat(first.components().size(), is(2));
            assertThat(first.cursor(), is(not("")));
            assertThat(second.components().size(), is(1));
            assertThat(second.cursor(), is(""));
        } finally {
            tasks.remove(owner);
            tasks.remove(other);
        }
    }

    @Test
    void enforcesConfiguredGlobalCapacity() {
        McpTasks tasks = new McpTasks(McpTasksConfig.builder()
                                              .maxTasks(2)
                                              .maxTasksPerSession(2)
                                              .buildPrototype());
        Context requestContext = requestContext();
        McpSession one = session("one");
        McpSession two = session("two");

        try {
            tasks.create(one, requestContext);
            tasks.create(two, requestContext);
            McpInternalException exception = assertThrows(McpInternalException.class,
                                                           () -> tasks.create(session("three"), requestContext));

            assertThat(exception.code(), is(INTERNAL_ERROR));
            assertThat(exception.getMessage(), is("Task capacity reached"));
        } finally {
            tasks.remove(one);
            tasks.remove(two);
        }
    }

    @Test
    void enforcesConfiguredPerSessionCapacity() {
        McpTasks tasks = new McpTasks(McpTasksConfig.builder()
                                              .maxTasks(3)
                                              .maxTasksPerSession(1)
                                              .buildPrototype());
        McpSession owner = session("owner");
        McpSession other = session("other");
        Context requestContext = requestContext();

        try {
            tasks.create(owner, requestContext);
            McpInternalException exception = assertThrows(McpInternalException.class,
                                                           () -> tasks.create(owner, requestContext));

            assertThat(exception.code(), is(INTERNAL_ERROR));
            assertThat(exception.getMessage(), is("Task capacity reached for this session"));
            assertDoesNotThrow(() -> tasks.create(other, requestContext));
        } finally {
            tasks.remove(owner);
            tasks.remove(other);
        }
    }

    @Test
    void isolatesTasksByAuthorizationIdentity() {
        McpTasks tasks = new McpTasks();
        McpSession session = session("one");
        Context alice = requestContext("alice");
        Context anotherAliceRequest = requestContext("alice");
        Context bob = requestContext("bob");
        McpTask task = tasks.create(session, alice);

        assertThat(tasks.get(task.id(), session, anotherAliceRequest), sameInstance(task));
        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> tasks.get(task.id(), session, bob));
        assertThat(exception.code(), is(INVALID_PARAMS));
        assertThat(tasks.list(session, bob).components().isEmpty(), is(true));
        McpInternalException cursorException = assertThrows(McpInternalException.class,
                                                             () -> tasks.list(session, bob, task.id()));
        assertThat(cursorException.code(), is(INVALID_PARAMS));
    }

    @Test
    void usesStablePrincipalIdentityAcrossAuthorizationDecisions() {
        McpTasks tasks = new McpTasks();
        McpSession session = session("one");
        Context authenticated = requestContext("alice", true, true);
        McpTask task = tasks.create(session, authenticated);

        assertThat(tasks.get(task.id(), session, requestContext("alice", false, true)), sameInstance(task));
        assertThat(tasks.get(task.id(), session, requestContext("alice", true, false)), sameInstance(task));
    }

    @Test
    void sessionEvictionRemovesOwnedTasks() {
        McpServerConfig config = McpServerConfig.create();
        McpTasks tasks = new McpTasks();
        McpSessions sessions = new McpSessions(1, session -> {
            session.close();
            tasks.remove(session);
        });
        McpSession owner = new McpSession(sessions, mock(McpTransportManager.class), config, "owner");
        owner.protocolVersion(McpProtocolVersion.VERSION_2025_11_25);
        sessions.put(owner.id(), owner);
        Context requestContext = requestContext();
        McpTask task = tasks.create(owner, requestContext);
        McpSession replacement = new McpSession(sessions,
                                                mock(McpTransportManager.class),
                                                config,
                                                "replacement");
        replacement.protocolVersion(McpProtocolVersion.VERSION_2025_11_25);

        sessions.put(replacement.id(), replacement);

        assertThat(sessions.get(owner.id()).isEmpty(), is(true));
        assertThrows(McpInternalException.class, () -> tasks.get(task.id(), owner, requestContext));
        assertThat(task.awaitOutcome(), instanceOf(McpTask.ErrorOutcome.class));
    }

    private Context requestContext() {
        return Context.create();
    }

    @SuppressWarnings("unchecked")
    private Context requestContext(String userName) {
        return requestContext(userName, true, true);
    }

    @SuppressWarnings("unchecked")
    private Context requestContext(String userName, boolean authenticated, boolean authorized) {
        Context context = Context.create();
        SecurityContext<Principal> security = mock(SecurityContext.class);
        when(security.isAuthenticated()).thenReturn(authenticated);
        when(security.isAuthorized()).thenReturn(authorized);
        when(security.userPrincipal()).thenReturn(Optional.of(() -> userName));
        when(security.servicePrincipal()).thenReturn(Optional.empty());
        context.register(security);
        return context;
    }

    private McpSession session(String id) {
        McpServerConfig config = McpServerConfig.create();
        McpSessions sessions = new McpSessions(config.maxSessionCount());
        McpSession session = new McpSession(sessions, mock(McpTransportManager.class), config, id);
        session.protocolVersion(McpProtocolVersion.VERSION_2025_11_25);
        return session;
    }
}
