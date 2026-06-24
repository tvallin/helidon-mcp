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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.jsonrpc.JsonRpcRequest;
import io.helidon.webserver.jsonrpc.JsonRpcResponse;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class McpSessionTest {

    @Test
    void testPollResponseCorrelatesByRequestId() throws Exception {
        McpSession session = session();
        long firstId = session.jsonRpcId();
        long secondId = session.jsonRpcId();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<JsonObject> first = executor.submit(() -> session.pollResponse(firstId, Duration.ofSeconds(2)));
            session.acceptResponse(response(secondId, "second"));
            Future<JsonObject> second = executor.submit(() -> session.pollResponse(secondId, Duration.ofSeconds(2)));
            session.acceptResponse(response(firstId, "first"));

            assertThat(first.get(3, TimeUnit.SECONDS).getString("result"), is("first"));
            assertThat(second.get(3, TimeUnit.SECONDS).getString("result"), is("second"));
        } finally {
            executor.shutdownNow();
        }
    }

    private static McpSession session() {
        McpServerConfig config = McpServerConfig.create();
        McpSession session = new McpSession(new McpSessions(config.maxSessionCount()),
                                            new NoOpTransportManager(),
                                            config,
                                            "test-session");
        session.protocolVersion(McpProtocolVersion.VERSION_2025_06_18);
        return session;
    }

    private static JsonObject response(long id, String result) {
        return Json.createObjectBuilder()
                .add("jsonrpc", "2.0")
                .add("id", id)
                .add("result", result)
                .build();
    }

    private static final class NoOpTransportManager implements McpTransportManager {
        @Override
        public McpTransport create(JsonRpcRequest request, JsonRpcResponse response) {
            throw new UnsupportedOperationException("Transport is not used by this test");
        }

        @Override
        public void onConnect(ServerResponse response) {
        }

        @Override
        public void onDisconnect(ServerResponse response) {
        }

        @Override
        public void onRequest(JsonRpcRequest request, JsonRpcResponse response) {
        }

        @Override
        public void onNotification(JsonRpcRequest request, JsonRpcResponse response) {
        }
    }
}
