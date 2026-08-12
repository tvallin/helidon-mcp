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

import io.helidon.json.JsonObject;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static io.helidon.jsonrpc.core.JsonRpcError.INTERNAL_ERROR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class McpTaskTransportTest {
    @Test
    void queuesRelatedRequestAndTracksInputStatus() {
        McpTask task = task();
        McpTaskTransport transport = task.transport();
        McpStreamableHttpTransport resultTransport = mock(McpStreamableHttpTransport.class);
        JsonObject request = JsonObject.builder()
                .set("jsonrpc", "2.0")
                .set("id", 17)
                .set("method", "sampling/createMessage")
                .set("params", JsonObject.empty())
                .build();

        transport.send(request);

        assertThat(task.toJson().stringValue("status").orElseThrow(), is("input_required"));
        verifyNoInteractions(resultTransport);
        transport.attach(resultTransport, () -> { });
        ArgumentCaptor<JsonObject> message = ArgumentCaptor.forClass(JsonObject.class);
        verify(resultTransport).send(message.capture());
        JsonObject relatedTask = message.getValue()
                .objectValue("params")
                .orElseThrow()
                .objectValue("_meta")
                .orElseThrow()
                .objectValue(McpTask.RELATED_TASK_META_KEY)
                .orElseThrow();
        assertThat(relatedTask.stringValue("taskId").orElseThrow(), is(task.id()));

        transport.clientResponseReceived(17);
        assertThat(task.toJson().stringValue("status").orElseThrow(), is("working"));
    }

    @Test
    void removesTimedOutRequestBeforeDelivery() {
        McpTask task = task();
        McpTaskTransport transport = task.transport();
        McpStreamableHttpTransport resultTransport = mock(McpStreamableHttpTransport.class);
        JsonObject request = JsonObject.builder()
                .set("jsonrpc", "2.0")
                .set("id", 19)
                .set("method", "elicitation/create")
                .set("params", JsonObject.empty())
                .build();

        transport.send(request);
        transport.clientResponseReceived(19);
        transport.attach(resultTransport, () -> { });

        verifyNoInteractions(resultTransport);
        assertThat(task.toJson().stringValue("status").orElseThrow(), is("working"));
    }

    @Test
    void boundsQueuedMessages() {
        McpTaskTransport transport = task().transport();
        JsonObject notification = JsonObject.builder()
                .set("jsonrpc", "2.0")
                .set("method", "notifications/progress")
                .set("params", JsonObject.empty())
                .build();
        for (int i = 0; i < McpTaskTransport.MAX_QUEUED_MESSAGES; i++) {
            transport.send(notification);
        }

        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> transport.send(notification));
        assertThat(exception.code(), is(INTERNAL_ERROR));
    }

    @Test
    void detachesAbandonedResultTransport() {
        McpTaskTransport transport = task().transport();
        McpStreamableHttpTransport first = mock(McpStreamableHttpTransport.class);
        McpStreamableHttpTransport second = mock(McpStreamableHttpTransport.class);
        JsonObject notification = JsonObject.builder()
                .set("jsonrpc", "2.0")
                .set("method", "notifications/progress")
                .set("params", JsonObject.empty())
                .build();
        McpTaskTransport.ResultAttachment attachment = transport.attach(first, () -> { });
        transport.detach(attachment);

        transport.send(notification);
        transport.attach(second, () -> { });

        verifyNoInteractions(first);
        verify(second).send(notificationWithRelatedTask(notification));
    }

    @Test
    void failedAttachRequeuesUndeliveredMessagesInOriginalOrder() {
        McpTaskTransport transport = task().transport();
        McpStreamableHttpTransport failed = mock(McpStreamableHttpTransport.class);
        McpStreamableHttpTransport replacement = mock(McpStreamableHttpTransport.class);
        JsonObject first = notification(1);
        JsonObject second = notification(2);
        JsonObject third = notification(3);
        JsonObject relatedFirst = notificationWithRelatedTask(first);
        JsonObject relatedSecond = notificationWithRelatedTask(second);
        JsonObject relatedThird = notificationWithRelatedTask(third);
        AtomicBoolean transportFailed = new AtomicBoolean();
        transport.send(first);
        transport.send(second);
        transport.send(third);
        doThrow(new IllegalStateException("Closed transport")).when(failed).send(relatedSecond);

        assertDoesNotThrow(() -> transport.attach(failed, () -> transportFailed.set(true)));
        assertThat(transportFailed.get(), is(true));
        transport.attach(replacement, () -> { });

        ArgumentCaptor<JsonObject> failedMessages = ArgumentCaptor.forClass(JsonObject.class);
        verify(failed, times(2)).send(failedMessages.capture());
        assertThat(failedMessages.getAllValues(), is(List.of(relatedFirst, relatedSecond)));
        ArgumentCaptor<JsonObject> replacementMessages = ArgumentCaptor.forClass(JsonObject.class);
        verify(replacement, times(2)).send(replacementMessages.capture());
        assertThat(replacementMessages.getAllValues(), is(List.of(relatedSecond, relatedThird)));
    }

    @Test
    void failedRequestDoesNotClearAnotherPendingRequest() {
        McpTask task = task();
        McpTaskTransport transport = task.transport();
        McpStreamableHttpTransport resultTransport = mock(McpStreamableHttpTransport.class);
        McpStreamableHttpTransport replacement = mock(McpStreamableHttpTransport.class);
        AtomicBoolean transportFailed = new AtomicBoolean();
        JsonObject first = request(1);
        JsonObject second = request(2);
        transport.send(first);
        transport.attach(resultTransport, () -> transportFailed.set(true));
        doThrow(new IllegalStateException("Closed transport"))
                .when(resultTransport)
                .send(task.relatedMessage(second));

        assertDoesNotThrow(() -> transport.send(second));
        assertThat(transportFailed.get(), is(true));
        transport.attach(replacement, () -> { });
        verify(replacement).send(task.relatedMessage(second));
        transport.clientResponseReceived(2);

        assertThat(task.toJson().stringValue("status").orElseThrow(), is("input_required"));
        transport.clientResponseReceived(1);
        assertThat(task.toJson().stringValue("status").orElseThrow(), is("working"));
    }

    @Test
    void closeDoesNotWaitForBackpressuredDelivery() throws InterruptedException {
        McpTaskTransport transport = task().transport();
        McpStreamableHttpTransport resultTransport = mock(McpStreamableHttpTransport.class);
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        doAnswer(invocation -> {
            deliveryStarted.countDown();
            releaseDelivery.await();
            return null;
        }).when(resultTransport).send(any(JsonObject.class));
        transport.attach(resultTransport, () -> { });
        Thread sender = Thread.ofVirtual().start(() -> transport.send(notification(1)));

        try {
            assertThat(deliveryStarted.await(5, TimeUnit.SECONDS), is(true));
            assertTimeoutPreemptively(Duration.ofSeconds(1), transport::close);
        } finally {
            releaseDelivery.countDown();
            sender.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    private McpTask task() {
        return new McpTask("task-id",
                           new McpTaskOwner("session-id"),
                           McpTasks.DEFAULT_TTL,
                           McpTasks.DEFAULT_POLL_INTERVAL);
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

    private JsonObject notificationWithRelatedTask(JsonObject notification) {
        JsonObject params = notification.objectValue("params").orElseThrow();
        return JsonObject.builder()
                .from(notification)
                .set("params", JsonObject.builder()
                        .from(params)
                        .set("_meta", JsonObject.builder()
                                .set(McpTask.RELATED_TASK_META_KEY,
                                     JsonObject.builder().set("taskId", "task-id").build())
                                .build())
                        .build())
                .build();
    }
}
