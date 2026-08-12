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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.json.JsonObject;
import io.helidon.webserver.jsonrpc.JsonRpcResponse;
import io.helidon.webserver.sse.SseSink;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpStreamableHttpTransportTest {
    @Test
    void closePreventsSinkCreationFromResurrectingTransport() throws InterruptedException {
        JsonRpcResponse response = mock(JsonRpcResponse.class);
        SseSink sink = mock(SseSink.class);
        CountDownLatch creatingSink = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        when(response.sink(SseSink.TYPE)).thenAnswer(invocation -> {
            creatingSink.countDown();
            assertThat(releaseSink.await(5, TimeUnit.SECONDS), is(true));
            return sink;
        });
        McpStreamableHttpTransport transport = new McpStreamableHttpTransport(response);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sender = Thread.ofVirtual().start(() -> {
            try {
                transport.send(JsonObject.empty());
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        assertThat(creatingSink.await(5, TimeUnit.SECONDS), is(true));

        transport.close();
        releaseSink.countDown();
        sender.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(sender.isAlive(), is(false));
        assertThat(failure.get(), instanceOf(McpInternalException.class));
        verify(sink).close();
        verify(sink, never()).emit(any());
        assertThrows(McpInternalException.class, () -> transport.send(JsonObject.empty()));
    }
}
