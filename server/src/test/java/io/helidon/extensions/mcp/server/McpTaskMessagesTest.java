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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.json.JsonNumber;
import io.helidon.json.JsonObject;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static io.helidon.jsonrpc.core.JsonRpcError.INTERNAL_ERROR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class McpTaskMessagesTest {
    private final McpTaskOwner owner = new McpTaskOwner(new McpTaskOwner.AuthorizationIdentity(List.of()));
    private final McpTasksConfig config = McpTasksConfig.create();

    @Test
    void queuesRelatedRequestAndTracksInputStatus() {
        McpTask task = task();
        McpTaskMessages messages = messages(task);
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(1, messages);
        McpStreamableHttpTransport transport = mock(McpStreamableHttpTransport.class);
        JsonObject request = request(17);

        messages.send(task, request);

        assertThat(task.toJson().stringValue("status").orElseThrow(), is("input_required"));
        verifyNoInteractions(transport);
        McpTaskResultWaiters.Waiter waiter = waiters.register(JsonNumber.create(1), task, transport);
        assertThat(waiters.attach(waiter), is(true));
        ArgumentCaptor<JsonObject> delivered = ArgumentCaptor.forClass(JsonObject.class);
        verify(transport).send(delivered.capture());
        JsonObject relatedTask = delivered.getValue()
                .objectValue("params")
                .orElseThrow()
                .objectValue("_meta")
                .orElseThrow()
                .objectValue(McpTask.RELATED_TASK_META_KEY)
                .orElseThrow();
        assertThat(relatedTask.stringValue("taskId").orElseThrow(), is(task.id()));

        messages.clientResponseReceived(task, 17);
        assertThat(task.toJson().stringValue("status").orElseThrow(), is("working"));
        waiters.discard(waiter);
    }

    @Test
    void removesTimedOutRequestBeforeDelivery() {
        McpTask task = task();
        McpTaskMessages messages = messages(task);
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(1, messages);
        McpStreamableHttpTransport transport = mock(McpStreamableHttpTransport.class);

        messages.send(task, request(19));
        messages.clientResponseReceived(task, 19);
        McpTaskResultWaiters.Waiter waiter = waiters.register(JsonNumber.create(1), task, transport);
        assertThat(waiters.attach(waiter), is(true));

        verifyNoInteractions(transport);
        assertThat(task.toJson().stringValue("status").orElseThrow(), is("working"));
        waiters.discard(waiter);
    }

    @Test
    void boundsQueuedMessages() {
        McpTask task = task();
        McpTaskMessages messages = messages(task);
        JsonObject notification = notification(1);
        for (int i = 0; i < McpTaskMessages.MAX_QUEUED_MESSAGES; i++) {
            messages.send(task, notification);
        }

        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> messages.send(task, notification));
        assertThat(exception.code(), is(INTERNAL_ERROR));
    }

    @Test
    void detachesAbandonedResultTransport() {
        McpTask task = task();
        McpTaskMessages messages = messages(task);
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(2, messages);
        McpStreamableHttpTransport first = mock(McpStreamableHttpTransport.class);
        McpStreamableHttpTransport second = mock(McpStreamableHttpTransport.class);
        McpTaskResultWaiters.Waiter firstWaiter = waiters.register(JsonNumber.create(1), task, first);
        assertThat(waiters.attach(firstWaiter), is(true));
        waiters.discard(firstWaiter);

        JsonObject notification = notification(1);
        messages.send(task, notification);
        McpTaskResultWaiters.Waiter secondWaiter = waiters.register(JsonNumber.create(2), task, second);
        assertThat(waiters.attach(secondWaiter), is(true));

        verifyNoInteractions(first);
        verify(second).send(task.relatedMessage(notification));
        waiters.discard(secondWaiter);
    }

    @Test
    void failedAttachRequeuesUndeliveredMessagesInOriginalOrder() {
        McpTask task = task();
        McpTaskMessages messages = messages(task);
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(2, messages);
        McpStreamableHttpTransport failed = mock(McpStreamableHttpTransport.class);
        McpStreamableHttpTransport replacement = mock(McpStreamableHttpTransport.class);
        JsonObject first = notification(1);
        JsonObject second = notification(2);
        JsonObject third = notification(3);
        JsonObject relatedFirst = task.relatedMessage(first);
        JsonObject relatedSecond = task.relatedMessage(second);
        JsonObject relatedThird = task.relatedMessage(third);
        messages.send(task, first);
        messages.send(task, second);
        messages.send(task, third);
        doThrow(new IllegalStateException("Closed transport")).when(failed).send(relatedSecond);
        McpTaskResultWaiters.Waiter failedWaiter = waiters.register(JsonNumber.create(1), task, failed);

        assertDoesNotThrow(() -> waiters.attach(failedWaiter));
        McpTaskResultWaiters.Waiter replacementWaiter = waiters.register(JsonNumber.create(2), task, replacement);
        assertThat(waiters.attach(replacementWaiter), is(true));

        ArgumentCaptor<JsonObject> failedMessages = ArgumentCaptor.forClass(JsonObject.class);
        verify(failed, times(2)).send(failedMessages.capture());
        assertThat(failedMessages.getAllValues(), is(List.of(relatedFirst, relatedSecond)));
        ArgumentCaptor<JsonObject> replacementMessages = ArgumentCaptor.forClass(JsonObject.class);
        verify(replacement, times(2)).send(replacementMessages.capture());
        assertThat(replacementMessages.getAllValues(), is(List.of(relatedSecond, relatedThird)));
        waiters.discard(failedWaiter);
        waiters.discard(replacementWaiter);
    }

    @Test
    void keepsInputRequiredUntilEveryKnownRequestFinishes() {
        McpTask task = task();
        McpTaskMessages messages = messages(task);

        messages.send(task, request(1));
        messages.send(task, request(2));
        messages.clientResponseReceived(task, 2);
        messages.clientResponseReceived(task, 99);
        messages.clientResponseReceived(task, 2);

        assertThat(task.toJson().stringValue("status").orElseThrow(), is("input_required"));
        messages.clientResponseReceived(task, 1);
        assertThat(task.toJson().stringValue("status").orElseThrow(), is("working"));
    }

    @Test
    void immediateClientResponseReturnsTaskToWorking() {
        McpTask task = task();
        McpTaskMessages messages = messages(task);
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(1, messages);
        McpStreamableHttpTransport transport = mock(McpStreamableHttpTransport.class);
        McpTaskResultWaiters.Waiter waiter = waiters.register(JsonNumber.create(1), task, transport);
        assertThat(waiters.attach(waiter), is(true));
        doAnswer(invocation -> {
            messages.clientResponseReceived(task, 23);
            return null;
        }).when(transport).send(any(JsonObject.class));

        messages.send(task, request(23));

        assertThat(task.toJson().stringValue("status").orElseThrow(), is("working"));
        waiters.discard(waiter);
    }

    @Test
    void failedCancellationLeavesChannelAvailable() {
        McpTask task = task();
        McpTaskMessages messages = messages(task);
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(1, messages);
        McpStreamableHttpTransport transport = mock(McpStreamableHttpTransport.class);
        McpTaskResultWaiters.Waiter waiter = waiters.register(JsonNumber.create(1), task, transport);
        assertThat(waiters.attach(waiter), is(true));
        task.complete(JsonObject.empty(), false);

        assertThat(messages.cancel(task), is(false));
        JsonObject notification = notification(6);
        messages.send(task, notification);

        verify(transport).send(task.relatedMessage(notification));
        waiters.discard(waiter);
    }

    @Test
    void successfulCancellationStopsTaskMessages() {
        McpTask task = task();
        McpTaskMessages messages = messages(task);
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(1, messages);
        McpStreamableHttpTransport transport = mock(McpStreamableHttpTransport.class);
        McpTaskResultWaiters.Waiter waiter = waiters.register(JsonNumber.create(1), task, transport);
        assertThat(waiters.attach(waiter), is(true));

        assertThat(messages.cancel(task), is(true));
        messages.send(task, notification(7));

        verifyNoInteractions(transport);
        waiters.discard(waiter);
    }

    @Test
    void expirationWinsAtomicallyAgainstLateCompletion() {
        McpTask task = task();
        McpTaskMessages messages = messages(task);

        messages.expire(task);
        task.complete(JsonObject.builder().set("value", "late").build(), false);

        assertThat(task.toJson().stringValue("status").orElseThrow(), is("cancelled"));
        assertThat(task.awaitOutcome(), instanceOf(McpTask.ErrorOutcome.class));
    }

    @Test
    void failedLatestWaiterFallsBackToPrevious() {
        McpTask task = task();
        McpTaskMessages messages = messages(task);
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(2, messages);
        McpStreamableHttpTransport first = mock(McpStreamableHttpTransport.class);
        McpStreamableHttpTransport failed = mock(McpStreamableHttpTransport.class);
        McpTaskResultWaiters.Waiter firstWaiter = waiters.register(JsonNumber.create(1), task, first);
        McpTaskResultWaiters.Waiter failedWaiter = waiters.register(JsonNumber.create(2), task, failed);
        assertThat(waiters.attach(firstWaiter), is(true));
        assertThat(waiters.attach(failedWaiter), is(true));
        doThrow(new IllegalStateException("Closed transport")).when(failed).send(any(JsonObject.class));

        JsonObject notification = notification(4);
        messages.send(task, notification);

        verify(failed).send(task.relatedMessage(notification));
        verify(first).send(task.relatedMessage(notification));
        assertThat(failedWaiter.abandoned(), is(true));
        waiters.discard(firstWaiter);
        waiters.discard(failedWaiter);
    }

    @Test
    void closeDoesNotWaitForBackpressuredDelivery() throws InterruptedException {
        McpTask task = task();
        McpTaskMessages messages = messages(task);
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(1, messages);
        McpStreamableHttpTransport transport = mock(McpStreamableHttpTransport.class);
        McpTaskResultWaiters.Waiter waiter = waiters.register(JsonNumber.create(1), task, transport);
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        doAnswer(invocation -> {
            deliveryStarted.countDown();
            if (!releaseDelivery.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release task message delivery");
            }
            return null;
        }).when(transport).send(any(JsonObject.class));
        assertThat(waiters.attach(waiter), is(true));
        Thread sender = Thread.ofVirtual().start(() -> {
            try {
                messages.send(task, notification(5));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        try {
            assertThat(deliveryStarted.await(5, TimeUnit.SECONDS), is(true));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> messages.close(task));
        } finally {
            releaseDelivery.countDown();
            sender.join(TimeUnit.SECONDS.toMillis(5));
            waiters.discard(waiter);
        }

        assertThat(sender.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
    }

    @Test
    void closedChannelDoesNotDeliverMessages() {
        McpTask task = task();
        McpTaskMessages messages = messages(task);
        McpStreamableHttpTransport transport = mock(McpStreamableHttpTransport.class);
        McpTaskResultWaiters waiters = new McpTaskResultWaiters(1, messages);
        AtomicBoolean attached = new AtomicBoolean();

        messages.close(task);
        McpTaskResultWaiters.Waiter waiter = waiters.register(JsonNumber.create(1), task, transport);
        attached.set(waiters.attach(waiter));
        assertDoesNotThrow(() -> messages.send(task, notification(1)));
        assertDoesNotThrow(() -> messages.clientResponseReceived(task, 1));

        assertThat(attached.get(), is(true));
        verify(transport, never()).send(any(JsonObject.class));
        waiters.discard(waiter);
    }

    private McpTask task() {
        return new McpTask("task-id",
                           owner,
                           config.defaultTtl().toMillis(),
                           config.pollInterval().toMillis());
    }

    private McpTaskMessages messages(McpTask task) {
        McpTaskMessages messages = new McpTaskMessages();
        messages.register(task);
        return messages;
    }

    private JsonObject notification(int progress) {
        return JsonObject.builder()
                .set("jsonrpc", "2.0")
                .set("method", "notifications/progress")
                .set("params", JsonObject.builder().set("progress", progress).build())
                .build();
    }

    private JsonObject request(long id) {
        return JsonObject.builder()
                .set("jsonrpc", "2.0")
                .set("id", id)
                .set("method", "sampling/createMessage")
                .set("params", JsonObject.empty())
                .build();
    }
}
