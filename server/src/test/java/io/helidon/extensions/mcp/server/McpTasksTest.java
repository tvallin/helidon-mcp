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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.helidon.common.context.Context;
import io.helidon.common.security.SecurityContext;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.json.JsonObject;
import io.helidon.service.registry.Services;

import org.junit.jupiter.api.AfterEach;
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
    private final List<McpTasks> taskRegistries = new ArrayList<>();

    @AfterEach
    void closeTaskRegistries() {
        taskRegistries.forEach(McpTasks::close);
    }

    @Test
    void resolvesCoordinatorAsSingletonService() {
        assertThat(Services.get(McpTaskCoordinator.class), sameInstance(Services.get(McpTaskCoordinator.class)));
    }

    @Test
    void reservesAndReleasesCapacityByTaskId() {
        McpTaskCoordinator coordinator = coordinatorWithCapacity(1);

        assertThat(coordinator.tryReserve("first"), is(true));
        assertThat(coordinator.tryReserve("first"), is(false));
        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> coordinator.tryReserve("second"));
        assertThat(exception.getMessage(), is("Task capacity reached"));

        coordinator.release("first");

        assertThat(coordinator.tryReserve("second"), is(true));
        coordinator.release("second");
    }

    @Test
    void createsWorkingTaskWithDefaultRetention() {
        McpTasksConfig config = McpTasksConfig.create();
        McpTask task = tasks(new McpTaskCoordinator(config)).create(requestContext());

        JsonObject json = task.toJson();
        assertThat(json.stringValue("taskId").orElseThrow(), is(task.id()));
        assertThat(json.stringValue("status").orElseThrow(), is("working"));
        assertThat(json.numberValue("ttl").orElseThrow().longValue(), is(config.defaultTtl().toMillis()));
        assertThat(json.numberValue("pollInterval").orElseThrow().longValue(),
                   is(config.pollInterval().toMillis()));
        assertThat(json.stringValue("createdAt").orElseThrow(), is(not("")));
        assertThat(json.stringValue("lastUpdatedAt").orElseThrow(), is(not("")));
    }

    @Test
    void createsTaskWithConfiguredDefaultTtlAndPollInterval() {
        McpTasks tasks = tasks(new McpTaskCoordinator(McpTasksConfig.builder()
                                        .pollInterval(Duration.ofMillis(250))
                                        .minTtl(Duration.ofSeconds(2))
                                        .defaultTtl(Duration.ofSeconds(3))
                                        .maxTtl(Duration.ofSeconds(4))
                                        .buildPrototype()));

        JsonObject json = tasks.create(requestContext()).toJson();

        assertThat(json.numberValue("ttl").orElseThrow().longValue(), is(3000L));
        assertThat(json.numberValue("pollInterval").orElseThrow().longValue(), is(250L));
    }

    @Test
    void readsTaskConfigurationFromGlobalConfigRoot() {
        Config root = Config.just(ConfigSources.create(Map.of(
                "mcp.server.tasks.poll-interval", "PT0.25S",
                "mcp.server.tasks.default-ttl", "PT2S")));
        McpTasks tasks = tasks(new McpTaskCoordinator(root));

        JsonObject json = tasks.create(requestContext()).toJson();

        assertThat(json.numberValue("ttl").orElseThrow().longValue(), is(2000L));
        assertThat(json.numberValue("pollInterval").orElseThrow().longValue(), is(250L));
    }

    @Test
    void completesTaskAndAddsRelatedMetadata() {
        McpTask task = tasks().create(requestContext(), 5000);
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
        McpTask task = tasks().create(requestContext());
        JsonObject result = JsonObject.builder().set("isError", true).build();

        task.complete(result, true);

        assertThat(task.toJson().stringValue("status").orElseThrow(), is("failed"));
        McpTask.ResultOutcome outcome = (McpTask.ResultOutcome) task.awaitOutcome();
        assertThat(outcome.result(), is(result));
    }

    @Test
    void cancellationDeletesTaskImmediately() {
        McpTasks tasks = tasks();
        Context requestContext = requestContext();
        McpTask task = tasks.create(requestContext);

        tasks.cancel(task.id(), requestContext);

        assertThat(task.toJson().stringValue("status").orElseThrow(), is("cancelled"));
        McpInternalException getException = assertThrows(McpInternalException.class,
                                                         () -> tasks.get(task.id(), requestContext));
        assertThat(getException.code(), is(INVALID_PARAMS));
        assertThat(tasks.list(requestContext).components().isEmpty(), is(true));
        assertThrows(McpInternalException.class, () -> tasks.cancel(task.id(), requestContext));
    }

    @Test
    void cancellationUnblocksExistingResultWaiter() throws Exception {
        McpTasks tasks = tasks();
        Context requestContext = requestContext();
        McpTask task = tasks.create(requestContext);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CompletableFuture<McpTask.Outcome> waiter = CompletableFuture.supplyAsync(() -> {
            waiterStarted.countDown();
            return task.awaitOutcome();
        });

        try {
            assertThat(waiterStarted.await(5, TimeUnit.SECONDS), is(true));

            tasks.cancel(task.id(), requestContext);

            McpTask.ErrorOutcome outcome = (McpTask.ErrorOutcome) waiter.get(5, TimeUnit.SECONDS);
            assertThat(outcome.code(), is(INVALID_PARAMS));
        } finally {
            task.fail(INVALID_PARAMS, "Test cleanup");
            waiter.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void throwingCancellationHookDoesNotFailCancellationOrSuppressInterrupt() throws Exception {
        McpTasks tasks = tasks();
        McpSession session = session("one");
        Context requestContext = requestContext();
        McpTask task = tasks.create(requestContext);
        McpFeatures features = session.createFeatures(task, requestContext);
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

            assertDoesNotThrow(() -> tasks.cancel(task.id(), requestContext));

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
        McpTaskCoordinator coordinator = new McpTaskCoordinator();
        McpTasks ownerTasks = tasks(coordinator);
        McpTasks otherTasks = tasks(coordinator);
        Context requestContext = requestContext();
        McpTask task = ownerTasks.create(requestContext);

        assertThat(ownerTasks.get(task.id(), requestContext), sameInstance(task));
        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> otherTasks.get(task.id(), requestContext));
        assertThat(exception.code(), is(INVALID_PARAMS));
    }

    @Test
    void clampsRequestedTtl() {
        McpTasksConfig config = McpTasksConfig.create();
        McpTasks tasks = tasks(new McpTaskCoordinator(config));
        Context requestContext = requestContext();
        McpTask shortTask = tasks.create(requestContext, 0);
        McpTask longTask = tasks.create(requestContext, Long.MAX_VALUE);

        assertThat(shortTask.toJson().numberValue("ttl").orElseThrow().longValue(), is(config.minTtl().toMillis()));
        assertThat(longTask.toJson().numberValue("ttl").orElseThrow().longValue(), is(config.maxTtl().toMillis()));
    }

    @Test
    void clampsRequestedTtlToConfiguredRange() {
        McpTasks tasks = tasks(new McpTaskCoordinator(McpTasksConfig.builder()
                                        .minTtl(Duration.ofSeconds(2))
                                        .defaultTtl(Duration.ofSeconds(3))
                                        .maxTtl(Duration.ofSeconds(4))
                                        .buildPrototype()));
        Context requestContext = requestContext();

        McpTask shortTask = tasks.create(requestContext, 1);
        McpTask longTask = tasks.create(requestContext, Long.MAX_VALUE);

        assertThat(shortTask.toJson().numberValue("ttl").orElseThrow().longValue(), is(2000L));
        assertThat(longTask.toJson().numberValue("ttl").orElseThrow().longValue(), is(4000L));
    }

    @Test
    void cancelledExecutionRetainsCapacityUntilWorkerExits() throws Exception {
        McpTaskCoordinator coordinator = coordinatorWithCapacity(1);
        McpTasks tasks = tasks(coordinator);
        McpTasks otherTasks = tasks(coordinator);
        McpSession session = session("one");
        Context requestContext = requestContext();
        McpTask task = tasks.create(requestContext);
        McpFeatures features = session.createFeatures(task, requestContext);
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
            tasks.cancel(task.id(), requestContext);

            assertThrows(McpInternalException.class, () -> otherTasks.create(requestContext));
        } finally {
            executionRelease.countDown();
            executionThread.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertDoesNotThrow(() -> otherTasks.create(requestContext));
    }

    @Test
    void paginatesTasksWithinSessionRegistry() {
        McpTasksConfig config = McpTasksConfig.create();
        McpTaskCoordinator coordinator = new McpTaskCoordinator(config);
        McpTasks ownerTasks = tasks(coordinator);
        McpTasks otherTasks = tasks(coordinator);
        Context requestContext = requestContext();
        for (int i = 0; i < config.pageSize() + 1; i++) {
            ownerTasks.create(requestContext);
        }
        McpTask otherTask = otherTasks.create(requestContext);

        McpPage<McpTask> first = ownerTasks.list(requestContext);
        McpPage<McpTask> second = ownerTasks.list(requestContext, first.cursor());

        assertThat(first.components().size(), is(config.pageSize()));
        assertThat(first.cursor(), is(not("")));
        assertThat(second.components().size(), is(1));
        assertThat(second.cursor(), is(""));
        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> ownerTasks.list(requestContext, otherTask.id()));
        assertThat(exception.code(), is(INVALID_PARAMS));
    }

    @Test
    void paginatesTasksUsingConfiguredPageSize() {
        McpTaskCoordinator coordinator = new McpTaskCoordinator(McpTasksConfig.builder()
                                                                        .pageSize(2)
                                                                        .buildPrototype());
        McpTasks ownerTasks = tasks(coordinator);
        McpTasks otherTasks = tasks(coordinator);
        Context requestContext = requestContext();
        ownerTasks.create(requestContext);
        ownerTasks.create(requestContext);
        ownerTasks.create(requestContext);
        otherTasks.create(requestContext);

        McpPage<McpTask> first = ownerTasks.list(requestContext);
        McpPage<McpTask> second = ownerTasks.list(requestContext, first.cursor());

        assertThat(first.components().size(), is(2));
        assertThat(first.cursor(), is(not("")));
        assertThat(second.components().size(), is(1));
        assertThat(second.cursor(), is(""));
    }

    @Test
    void enforcesConfiguredGlobalCapacity() {
        McpTaskCoordinator coordinator = new McpTaskCoordinator(McpTasksConfig.builder()
                                                                        .maxTasks(2)
                                                                        .maxTasksPerSession(2)
                                                                        .buildPrototype());
        McpTasks firstTasks = tasks(coordinator);
        McpTasks secondTasks = tasks(coordinator);
        McpTasks thirdTasks = tasks(coordinator);
        Context requestContext = requestContext();

        firstTasks.create(requestContext);
        secondTasks.create(requestContext);
        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> thirdTasks.create(requestContext));

        assertThat(exception.code(), is(INTERNAL_ERROR));
        assertThat(exception.getMessage(), is("Task capacity reached"));
    }

    @Test
    void reservesGlobalCapacityAtomicallyAcrossSessions() throws Exception {
        McpTaskCoordinator coordinator = coordinatorWithCapacity(1);
        McpTasks firstTasks = tasks(coordinator);
        McpTasks secondTasks = tasks(coordinator);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        List<Object> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Object> first = CompletableFuture.supplyAsync(
                    () -> createAfterRelease(firstTasks, ready, release), executor);
            CompletableFuture<Object> second = CompletableFuture.supplyAsync(
                    () -> createAfterRelease(secondTasks, ready, release), executor);
            assertThat(ready.await(5, TimeUnit.SECONDS), is(true));
            release.countDown();
            results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }

        assertThat(results.stream().filter(McpTask.class::isInstance).count(), is(1L));
        McpInternalException failure = results.stream()
                .filter(McpInternalException.class::isInstance)
                .map(McpInternalException.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(failure.getMessage(), is("Task capacity reached"));
    }

    @Test
    void enforcesConfiguredPerSessionCapacity() {
        McpTaskCoordinator coordinator = new McpTaskCoordinator(McpTasksConfig.builder()
                                                                        .maxTasks(3)
                                                                        .maxTasksPerSession(1)
                                                                        .buildPrototype());
        McpTasks ownerTasks = tasks(coordinator);
        McpTasks otherTasks = tasks(coordinator);
        Context requestContext = requestContext();

        ownerTasks.create(requestContext);
        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> ownerTasks.create(requestContext));

        assertThat(exception.code(), is(INTERNAL_ERROR));
        assertThat(exception.getMessage(), is("Task capacity reached for this session"));
        assertDoesNotThrow(() -> otherTasks.create(requestContext));
    }

    @Test
    void isolatesTasksByAuthorizationIdentity() {
        McpTasks tasks = tasks();
        Context alice = requestContext("alice");
        Context anotherAliceRequest = requestContext("alice");
        Context bob = requestContext("bob");
        McpTask task = tasks.create(alice);

        assertThat(tasks.get(task.id(), anotherAliceRequest), sameInstance(task));
        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> tasks.get(task.id(), bob));
        assertThat(exception.code(), is(INVALID_PARAMS));
        assertThat(tasks.list(bob).components().isEmpty(), is(true));
        McpInternalException cursorException = assertThrows(McpInternalException.class,
                                                             () -> tasks.list(bob, task.id()));
        assertThat(cursorException.code(), is(INVALID_PARAMS));
    }

    @Test
    void usesStablePrincipalIdentityAcrossAuthorizationDecisions() {
        McpTasks tasks = tasks();
        Context authenticated = requestContext("alice", true, true);
        McpTask task = tasks.create(authenticated);

        assertThat(tasks.get(task.id(), requestContext("alice", false, true)), sameInstance(task));
        assertThat(tasks.get(task.id(), requestContext("alice", true, false)), sameInstance(task));
    }

    @Test
    void sessionCloseRemovesTasksAndReleasesGlobalCapacity() {
        McpTaskCoordinator coordinator = coordinatorWithCapacity(1);
        McpSession owner = session("owner", coordinator);
        Context requestContext = requestContext();
        McpTask task = owner.tasks().create(requestContext);

        owner.close();

        assertThrows(McpInternalException.class, () -> owner.tasks().get(task.id(), requestContext));
        assertThat(task.awaitOutcome(), instanceOf(McpTask.ErrorOutcome.class));
        McpSession replacement = session("replacement", coordinator);
        try {
            assertDoesNotThrow(() -> replacement.tasks().create(requestContext));
        } finally {
            replacement.close();
        }
    }

    @Test
    void sessionCloseRetainsCapacityUntilRunningWorkerExits() throws Exception {
        McpTaskCoordinator coordinator = coordinatorWithCapacity(1);
        McpSession owner = session("owner", coordinator);
        McpSession replacement = session("replacement", coordinator);
        Context requestContext = requestContext();
        McpTask task = owner.tasks().create(requestContext);
        McpFeatures features = owner.createFeatures(task, requestContext);
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch executionRelease = new CountDownLatch(1);
        Thread executionThread = Thread.ofVirtual().unstarted(() -> {
            try {
                executionStarted.countDown();
                while (executionRelease.getCount() != 0) {
                    try {
                        executionRelease.await();
                    } catch (InterruptedException e) {
                        // Deliberately ignore session-close cancellation to verify retained capacity.
                    }
                }
            } finally {
                owner.tasks().executionFinished(task);
            }
        });
        owner.tasks().start(task, executionThread, features);

        try {
            assertThat(executionStarted.await(5, TimeUnit.SECONDS), is(true));

            owner.close();

            assertThrows(McpInternalException.class, () -> replacement.tasks().create(requestContext));
        } finally {
            executionRelease.countDown();
            executionThread.join(TimeUnit.SECONDS.toMillis(5));
            owner.close();
        }

        try {
            assertDoesNotThrow(() -> replacement.tasks().create(requestContext));
        } finally {
            replacement.close();
        }
    }

    @Test
    void sessionEvictionRemovesOwnedTasks() {
        McpServerConfig config = McpServerConfig.create();
        McpTaskCoordinator coordinator = coordinatorWithCapacity(1);
        McpSessions sessions = new McpSessions(1, McpSession::close);
        McpSession owner = new McpSession(sessions,
                                          mock(McpTransportManager.class),
                                          config,
                                          coordinator,
                                          "owner");
        owner.protocolVersion(McpProtocolVersion.VERSION_2025_11_25);
        sessions.put(owner.id(), owner);
        Context requestContext = requestContext();
        McpTask task = owner.tasks().create(requestContext);
        McpSession replacement = new McpSession(sessions,
                                                mock(McpTransportManager.class),
                                                config,
                                                coordinator,
                                                "replacement");
        replacement.protocolVersion(McpProtocolVersion.VERSION_2025_11_25);

        sessions.put(replacement.id(), replacement);

        assertThat(sessions.get(owner.id()).isEmpty(), is(true));
        assertThrows(McpInternalException.class, () -> owner.tasks().get(task.id(), requestContext));
        assertThat(task.awaitOutcome(), instanceOf(McpTask.ErrorOutcome.class));
        try {
            assertDoesNotThrow(() -> replacement.tasks().create(requestContext));
        } finally {
            replacement.close();
        }
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

    private Object createAfterRelease(McpTasks tasks, CountDownLatch ready, CountDownLatch release) {
        ready.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to create task");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to create task", e);
        }
        try {
            return tasks.create(requestContext());
        } catch (McpInternalException e) {
            return e;
        }
    }

    private McpTasks tasks() {
        return tasks(new McpTaskCoordinator());
    }

    private McpTaskCoordinator coordinatorWithCapacity(int capacity) {
        return new McpTaskCoordinator(McpTasksConfig.builder()
                                              .maxTasks(capacity)
                                              .maxTasksPerSession(capacity)
                                              .buildPrototype());
    }

    private McpTasks tasks(McpTaskCoordinator coordinator) {
        McpTasks tasks = new McpTasks(coordinator);
        taskRegistries.add(tasks);
        return tasks;
    }

    private McpSession session(String id) {
        McpServerConfig config = McpServerConfig.create();
        McpSessions sessions = new McpSessions(config.maxSessionCount());
        McpSession session = new McpSession(sessions,
                                            mock(McpTransportManager.class),
                                            config,
                                            id);
        session.protocolVersion(McpProtocolVersion.VERSION_2025_11_25);
        return session;
    }

    private McpSession session(String id, McpTaskCoordinator coordinator) {
        McpServerConfig config = McpServerConfig.create();
        McpSessions sessions = new McpSessions(config.maxSessionCount());
        McpSession session = new McpSession(sessions,
                                            mock(McpTransportManager.class),
                                            config,
                                            coordinator,
                                            id);
        session.protocolVersion(McpProtocolVersion.VERSION_2025_11_25);
        return session;
    }
}
