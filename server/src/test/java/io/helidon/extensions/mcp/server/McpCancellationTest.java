/*
 * Copyright (c) 2025, 2026 Oracle and/or its affiliates.
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.json.JsonNull;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpCancellationTest {

    @Test
    void builderComponentsDeliverCancellationToTheirHandlers() {
        var handler = mock(McpCancellable.class);
        var server = McpServerFeature.builder()
                .addTool(tool -> tool.name("tool").schema("").tool(_ -> McpToolResult.create()).onCancel(handler))
                .addPrompt(prompt -> prompt.name("prompt").description("prompt")
                        .prompt(_ -> McpPromptResult.create()).onCancel(handler))
                .addResource(resource -> resource.name("resource").uri("test://resource").mediaType(MediaTypes.TEXT_PLAIN)
                        .resource(_ -> McpResourceResult.create()).onCancel(handler))
                .addCompletion(completion -> completion.reference("prompt")
                        .completion(_ -> McpCompletionResult.create()).onCancel(handler))
                .addResourceSubscriber(subscriber -> subscriber.uri("test://resource")
                        .subscribe(_ -> { }).onCancel(handler))
                .addResourceUnsubscriber(unsubscriber -> unsubscriber.uri("test://resource")
                        .unsubscribe(_ -> { }).onCancel(handler))
                .buildPrototype();
        for (Object component : List.of(server.tools().getFirst(), server.prompts().getFirst(), server.resources().getFirst(),
                                        server.completions().getFirst(), server.resourceSubscribers().getFirst(),
                                        server.resourceUnsubscribers().getFirst())) {
            var request = mock(McpRequest.class);
            var features = mock(McpFeatures.class);
            var cancellation = new McpCancellation();
            when(request.features()).thenReturn(features);
            when(features.cancellation()).thenReturn(cancellation);

            try (var _ = McpCancellation.register(component, request)) {
                cancellation.cancel(JsonNull.instance());
            }

            verify(handler, description(component.getClass().getSimpleName())).onCancel(request);
        }
    }

    @Test
    void testCancellationDefault() {
        McpCancellation cancellation = new McpCancellation();

        assertThat(cancellation.result().isRequested(), is(false));
        assertThat(cancellation.result().reason(), is(Optional.empty()));
    }

    @Test
    void testCancellationRequested() {
        String reason = "Process is taking too long";
        McpCancellation cancellation = new McpCancellation();
        cancellation.cancel(reason, JsonNull.instance());

        assertThat(cancellation.result().isRequested(), is(true));
        assertThat(cancellation.result().reason(), is(Optional.of(reason)));
    }

    @Test
    void testCancellationRequestedWithoutReason() {
        McpCancellation cancellation = new McpCancellation();
        cancellation.cancel(JsonNull.instance());

        assertThat(cancellation.result().isRequested(), is(true));
        assertThat(cancellation.result().reason(), is(Optional.empty()));
    }

    @Test
    void testCancellationHook() {
        AtomicInteger counter = new AtomicInteger();
        String reason = "Process is taking too long";
        McpCancellation cancellation = new McpCancellation();

        cancellation.registerCancellationHook(counter::getAndIncrement);
        cancellation.cancel(reason, JsonNull.instance());
        cancellation.cancel(reason, JsonNull.instance());

        McpCancellationResult result = cancellation.result();
        assertThat(result.isRequested(), is(true));
        assertThat(result.reason(), is(Optional.of(reason)));
        assertThat(counter.get(), is(1));
    }

    @Test
    void testMultipleCancellationHooks() {
        AtomicInteger firstCounter = new AtomicInteger();
        AtomicInteger secondCounter = new AtomicInteger();
        McpCancellation cancellation = new McpCancellation();

        cancellation.registerCancellationHook(firstCounter::incrementAndGet);
        cancellation.registerCancellationHook(secondCounter::incrementAndGet);
        cancellation.cancel(JsonNull.instance());
        cancellation.cancel(JsonNull.instance());

        assertThat(firstCounter.get(), is(1));
        assertThat(secondCounter.get(), is(1));
    }

    @Test
    void testUnregisteredCancellationHookDoesNotRun() {
        AtomicInteger counter = new AtomicInteger();
        McpCancellation cancellation = new McpCancellation();
        Runnable hook = counter::incrementAndGet;
        cancellation.registerCancellationHook(hook);

        cancellation.unregisterCancellationHook(hook);
        cancellation.cancel(JsonNull.instance());

        assertThat(counter.get(), is(0));
    }

    @Test
    void testFailingCancellationHookDoesNotSuppressLaterHook() {
        AtomicInteger counter = new AtomicInteger();
        McpCancellation cancellation = new McpCancellation();

        cancellation.registerCancellationHook(() -> {
            throw new IllegalStateException("Hook failed");
        });
        cancellation.registerCancellationHook(counter::incrementAndGet);
        cancellation.cancel(JsonNull.instance());

        assertThat(counter.get(), is(1));
    }

    @Test
    void testLateCancellationHookRunsImmediately() {
        AtomicInteger counter = new AtomicInteger();
        McpCancellation cancellation = new McpCancellation();
        cancellation.cancel(JsonNull.instance());

        cancellation.registerCancellationHook(counter::incrementAndGet);
        cancellation.cancel(JsonNull.instance());

        assertThat(counter.get(), is(1));
    }
}
