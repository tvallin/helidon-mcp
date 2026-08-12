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

package io.helidon.extensions.mcp.tests.declarative;

import io.helidon.http.HeaderName;
import io.helidon.http.HeaderNames;
import io.helidon.http.HeaderValues;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.testing.junit5.ServerTest;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ServerTest
class McpSdkToolAnnotationsServerTest {
    private static final HeaderName SESSION_ID_HEADER = HeaderNames.create("Mcp-Session-Id");
    private static final HeaderName MCP_PROTOCOL_VERSION = HeaderNames.create("Mcp-Protocol-Version");
    private static McpSyncClient client;
    private final Http1Client httpClient;

    McpSdkToolAnnotationsServerTest(WebServer server) {
        httpClient = Http1Client.builder()
                .baseUri("http://localhost:" + server.port())
                .build();
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + server.port())
                                        .endpoint("/toolAnnotations")
                                        .build())
                .build();
        client.initialize();
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    @Test
    void testListToolsWithAnnotations() {
        var result = client.listTools();
        var tools = result.tools();
        assertThat(tools.size(), is(2));

        var tool1 = tools.getFirst();
        assertThat(tool1.name(), is("tool1"));
        assertThat(tool1.description(), is("Tool description"));
        McpSchema.ToolAnnotations annotations1 = tool1.annotations();
        assertThat(annotations1.title(), is(""));
        assertThat(annotations1.readOnlyHint(), is(false));
        assertThat(annotations1.destructiveHint(), is(true));
        assertThat(annotations1.idempotentHint(), is(false));
        assertThat(annotations1.openWorldHint(), is(true));

        var tool2 = tools.getLast();
        assertThat(tool2.name(), is("tool2"));
        assertThat(tool2.description(), is("Tool description"));
        McpSchema.ToolAnnotations annotations2 = tool2.annotations();
        assertThat(annotations2.title(), is("tool2 title"));
        assertThat(annotations2.readOnlyHint(), is(true));
        assertThat(annotations2.destructiveHint(), is(false));
        assertThat(annotations2.idempotentHint(), is(true));
        assertThat(annotations2.openWorldHint(), is(false));
    }

    @Test
    void testGeneratedTaskSupport() {
        JsonObject initialize = JsonObject.builder()
                .set("jsonrpc", "2.0")
                .set("id", 1)
                .set("method", "initialize")
                .set("params", JsonObject.builder()
                        .set("protocolVersion", "2025-11-25")
                        .set("capabilities", JsonObject.empty())
                        .set("clientInfo", JsonObject.builder()
                                .set("name", "declarative-task-test")
                                .set("version", "1.0.0")
                                .build())
                        .build())
                .build();
        String sessionId;
        try (var response = httpClient.post("/toolAnnotations")
                .header(HeaderValues.CONTENT_TYPE_JSON)
                .submit(initialize.toString())) {
            sessionId = response.headers().get(SESSION_ID_HEADER).get();
            response.entity().as(String.class);
        }

        JsonObject list = JsonObject.builder()
                .set("jsonrpc", "2.0")
                .set("id", 2)
                .set("method", "tools/list")
                .set("params", JsonObject.empty())
                .build();
        try (var response = httpClient.post("/toolAnnotations")
                .header(SESSION_ID_HEADER, sessionId)
                .header(MCP_PROTOCOL_VERSION, "2025-11-25")
                .header(HeaderValues.CONTENT_TYPE_JSON)
                .submit(list.toString())) {
            JsonObject result = JsonParser.create(response.entity().as(String.class))
                    .readJsonObject()
                    .objectValue("result")
                    .orElseThrow();
            JsonObject defaultTool = result.arrayValue("tools")
                    .orElseThrow()
                    .values()
                    .stream()
                    .map(value -> value.asObject())
                    .filter(value -> value.stringValue("name").orElse("").equals("tool1"))
                    .findFirst()
                    .orElseThrow();
            assertThat(defaultTool.containsKey("execution"), is(false));

            JsonObject tool = result.arrayValue("tools")
                    .orElseThrow()
                    .values()
                    .stream()
                    .map(value -> value.asObject())
                    .filter(value -> value.stringValue("name").orElse("").equals("tool2"))
                    .findFirst()
                    .orElseThrow();
            assertThat(tool.objectValue("execution")
                               .orElseThrow()
                               .stringValue("taskSupport")
                               .orElseThrow(),
                       is("optional"));
        }
    }
}
