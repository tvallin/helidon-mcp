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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.helidon.json.JsonObject;
import io.helidon.json.JsonNumber;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class McpTaskResultWaitersTest {
    private static final McpTaskOwner TASK_OWNER =
            new McpTaskOwner(new McpTaskOwner.AuthorizationIdentity(List.of()));
    private final McpTasksConfig taskConfig = McpTasksConfig.create();

    @Test
    void allowsConcurrentWaitersForSameTaskWithinCapacity() {
        McpTaskMessages taskMessages = new McpTaskMessages();
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(2, taskMessages);
        McpTask task = new McpTask("task",
                                   TASK_OWNER,
                                   taskConfig.defaultTtl().toMillis(),
                                   taskConfig.pollInterval().toMillis());
        McpTransport firstTransport = mock(McpStreamableHttpTransport.class);
        McpTransport secondTransport = mock(McpStreamableHttpTransport.class);
        taskMessages.register(task);
        McpTaskResultWaiters.Waiter first = waiters.register(JsonNumber.create(1), task, firstTransport);
        McpTaskResultWaiters.Waiter second = waiters.register(JsonNumber.create(2), task, secondTransport);

        assertThat(waiters.attach(first), is(true));
        assertThat(waiters.attach(second), is(true));
        assertThat(waiters.claim(first), is(true));
        assertThat(waiters.claim(second), is(true));
        waiters.discard(first);
        waiters.discard(second);
    }

    @Test
    void rejectsUnrelatedWaiterBeyondCapacity() {
        McpTaskMessages taskMessages = new McpTaskMessages();
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(1, taskMessages);
        McpTask firstTask = new McpTask("first",
                                        TASK_OWNER,
                                        taskConfig.defaultTtl().toMillis(),
                                        taskConfig.pollInterval().toMillis());
        McpTask secondTask = new McpTask("second",
                                         TASK_OWNER,
                                         taskConfig.defaultTtl().toMillis(),
                                         taskConfig.pollInterval().toMillis());
        McpTaskResultWaiters.Waiter first = waiters.register(JsonNumber.create(1),
                                                            firstTask,
                                                            mock(McpStreamableHttpTransport.class));

        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> waiters.register(JsonNumber.create(2),
                                                                              secondTask,
                                                                              mock(McpStreamableHttpTransport.class)));

        assertThat(exception.getMessage(), is("Maximum task result waiter count reached"));
        waiters.discard(first);
    }

    @Test
    void checksAuthorizationIdentityBeforeAbandoningWaiter() throws InterruptedException {
        McpTaskMessages taskMessages = new McpTaskMessages();
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(1, taskMessages);
        McpTask task = new McpTask("owned",
                                   TASK_OWNER,
                                   taskConfig.defaultTtl().toMillis(),
                                   taskConfig.pollInterval().toMillis());
        McpTransport transport = mock(McpStreamableHttpTransport.class);
        taskMessages.register(task);
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch abandoned = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread waitingThread = Thread.ofVirtual().start(() -> {
            McpTaskResultWaiters.Waiter waiter = waiters.register(JsonNumber.create(1), task, transport);
            waiters.attach(waiter);
            registered.countDown();
            try {
                task.awaitOutcome(waiter);
            } catch (McpInternalException e) {
                abandoned.countDown();
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
                waiters.discard(waiter);
            }
        });
        assertThat(registered.await(5, TimeUnit.SECONDS), is(true));

        var otherOwner = new McpTaskOwner(new McpTaskOwner.AuthorizationIdentity(
                List.of(new McpTaskOwner.PrincipalIdentity("user", "other"))));
        assertThat(waiters.abandon(JsonNumber.create(1), otherOwner), is(false));
        assertThat(abandoned.getCount(), is(1L));
        assertThat(waiters.abandon(JsonNumber.create(1), task.owner()), is(true));

        assertThat(abandoned.await(5, TimeUnit.SECONDS), is(true));
        waitingThread.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(interrupted.get(), is(false));
        verify(transport).close();
    }

    @Test
    void doesNotAttachAbandonedWaiter() {
        McpTaskMessages taskMessages = new McpTaskMessages();
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(1, taskMessages);
        McpTask task = new McpTask("abandoned",
                                   TASK_OWNER,
                                   taskConfig.defaultTtl().toMillis(),
                                   taskConfig.pollInterval().toMillis());
        McpStreamableHttpTransport transport = mock(McpStreamableHttpTransport.class);
        taskMessages.register(task);
        McpTaskResultWaiters.Waiter waiter = waiters.register(JsonNumber.create(1), task, transport);
        waiters.abandon(JsonNumber.create(1), task.owner());

        assertThat(waiters.attach(waiter), is(false));
        taskMessages.send(task, JsonObject.builder()
                                      .set("jsonrpc", "2.0")
                                      .set("method", "notifications/progress")
                                      .set("params", JsonObject.empty())
                                      .build());
        verify(transport, never()).send(any(JsonObject.class));
    }

    @Test
    void physicalSendFailureAbandonsWaiter() {
        McpTaskMessages taskMessages = new McpTaskMessages();
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(1, taskMessages);
        McpTask task = new McpTask("send-failure",
                                   TASK_OWNER,
                                   taskConfig.defaultTtl().toMillis(),
                                   taskConfig.pollInterval().toMillis());
        McpStreamableHttpTransport transport = mock(McpStreamableHttpTransport.class);
        taskMessages.register(task);
        McpTaskResultWaiters.Waiter waiter = waiters.register(JsonNumber.create(1), task, transport);
        assertThat(waiters.attach(waiter), is(true));
        doThrow(new IllegalStateException("closed")).when(transport)
                .send(any(JsonObject.class));

        taskMessages.send(task, JsonObject.builder()
                                      .set("jsonrpc", "2.0")
                                      .set("method", "notifications/progress")
                                      .set("params", JsonObject.empty())
                                      .build());

        assertThat(waiter.abandoned(), is(true));
        assertThat(waiters.claim(waiter), is(false));
        verify(transport).close();
        waiters.discard(waiter);
    }

    @Test
    void abandoningLatestWaiterRestoresSharedTransport() {
        McpTaskMessages taskMessages = new McpTaskMessages();
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(2, taskMessages);
        McpTask task = new McpTask("fallback",
                                   TASK_OWNER,
                                   taskConfig.defaultTtl().toMillis(),
                                   taskConfig.pollInterval().toMillis());
        McpStreamableHttpTransport sharedTransport = mock(McpStreamableHttpTransport.class);
        taskMessages.register(task);
        McpTaskResultWaiters.Waiter first = waiters.register(JsonNumber.create(1), task, sharedTransport);
        McpTaskResultWaiters.Waiter second = waiters.register(JsonNumber.create(2), task, sharedTransport);
        assertThat(waiters.attach(first), is(true));
        assertThat(waiters.attach(second), is(true));

        assertThat(waiters.abandon(JsonNumber.create(2), task.owner()), is(true));
        taskMessages.send(task, JsonObject.builder()
                                      .set("jsonrpc", "2.0")
                                      .set("method", "notifications/progress")
                                      .set("params", JsonObject.empty())
                                      .build());

        verify(sharedTransport).send(any(JsonObject.class));
        waiters.discard(first);
        waiters.discard(second);
    }

}
