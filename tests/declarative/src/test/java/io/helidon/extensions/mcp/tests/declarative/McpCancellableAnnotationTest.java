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
package io.helidon.extensions.mcp.tests.declarative;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.HeaderName;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.json.JsonObject;
import io.helidon.webclient.jsonrpc.JsonRpcClient;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.testing.junit5.ServerTest;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@ServerTest
class McpCancellableAnnotationTest {
    private static final HeaderName SESSION_ID = HeaderNames.create("Mcp-Session-Id");
    private final JsonRpcClient client;

    McpCancellableAnnotationTest(WebServer server) {
        client = JsonRpcClient.create(config -> config.baseUri("http://localhost:" + server.port() + "/cancellation"));
    }

    @Test
    void callsNamedHandlerWithTheActiveRequest() {
        String sessionId;
        try (var response = client.rpcMethod("initialize")
                .rpcId(1)
                .param("protocolVersion", "2025-11-25")
                .param("capabilities", JsonObject.empty())
                .param("clientInfo", JsonObject.builder().set("name", "test").set("version", "1").build())
                .submit()) {
            sessionId = response.headers().get(SESSION_ID).get();
        }
        try (var response = client.rpcMethod("notifications/initialized").header(SESSION_ID, sessionId).submit()) {
            assertThat(response.status(), is(Status.ACCEPTED_202));
        }
        try (var call = client.rpcMethod("tools/call")
                .header(SESSION_ID, sessionId)
                .header(HeaderNames.ACCEPT, "application/json, text/event-stream")
                .rpcId(2)
                .param("name", "waitForCancellation")
                .param("_meta", JsonObject.builder().set("progressToken", "wait").build())
                .submit()) {
            // The tool opens this stream by sending progress after it starts waiting for cancellation.
            assertThat(call.headers().contentType().orElseThrow().text(), is(MediaTypes.TEXT_EVENT_STREAM_VALUE));

            try (var response = client.rpcMethod("notifications/cancelled")
                    .header(SESSION_ID, sessionId)
                    .param("requestId", 2)
                    .submit()) {
                assertThat(response.status(), is(Status.ACCEPTED_202));
            }

            assertThat(call.entity().as(String.class), containsString("same request"));
        }
    }
}
