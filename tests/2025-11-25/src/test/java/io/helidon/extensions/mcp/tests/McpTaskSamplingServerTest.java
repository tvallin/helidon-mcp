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
package io.helidon.extensions.mcp.tests;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.extensions.mcp.server.McpServerFeature;
import io.helidon.extensions.mcp.server.McpTaskSupport;
import io.helidon.extensions.mcp.server.McpToolResult;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static io.helidon.extensions.mcp.server.McpRole.USER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@ServerTest
class McpTaskSamplingServerTest {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String PROTOCOL_VERSION = "2025-11-25";
    private static final String RELATED_TASK_META_KEY = "io.modelcontextprotocol/related-task";
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() { };

    private final int port;

    McpTaskSamplingServerTest(WebServer server) {
        port = server.port();
    }

    @SetUpRoute
    static void routing(HttpRouting.Builder builder) {
        builder.addFeature(McpServerFeature.builder()
                                   .path("/task-sampling")
                                   .addTool(tool -> tool.name("task-sampling")
                                           .description("Requests sampling from a background task.")
                                           .schema("")
                                           .taskSupport(McpTaskSupport.REQUIRED)
                                           .tool(request -> {
                                               var response = request.features()
                                                       .sampling()
                                                       .request(sampling -> sampling
                                                               .addTextMessage(USER, "Sample for the task"));
                                               return McpToolResult.create(response.asTextContent().text());
                                           })));
    }

    @Test
    void routesSamplingThroughTaskResultStream() {
        AtomicReference<Map<String, Object>> samplingRequest = new AtomicReference<>();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/task-sampling")
                .supportedProtocolVersions(List.of(PROTOCOL_VERSION))
                .build();
        McpClientSession.RequestHandler<Map<String, Object>> samplingHandler = params -> {
            samplingRequest.set(transport.unmarshalFrom(params, MAP_TYPE));
            return Mono.just(Map.of("role", "assistant",
                                    "content", Map.of("type", "text", "text", "sampled task result"),
                                    "model", "test-model",
                                    "stopReason", "endTurn"));
        };
        McpClientSession session = new McpClientSession(
                REQUEST_TIMEOUT,
                transport,
                Map.of(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, samplingHandler),
                Map.of(),
                connection -> connection);

        try {
            Map<String, Object> initialize = session.sendRequest(
                    McpSchema.METHOD_INITIALIZE,
                    Map.of("protocolVersion", PROTOCOL_VERSION,
                           "capabilities", Map.of("sampling", Map.of()),
                           "clientInfo", Map.of("name", "task-sampling-test", "version", "1.0.0")),
                    MAP_TYPE)
                    .block(REQUEST_TIMEOUT);
            assertThat(initialize, is(notNullValue()));
            session.sendNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED).block(REQUEST_TIMEOUT);

            Map<String, Object> createTask = session.sendRequest(
                    McpSchema.METHOD_TOOLS_CALL,
                    Map.of("name", "task-sampling",
                           "arguments", Map.of(),
                           "task", Map.of("ttl", 60_000)),
                    MAP_TYPE)
                    .block(REQUEST_TIMEOUT);
            assertThat(createTask.get("task"), instanceOf(Map.class));
            Map<?, ?> task = (Map<?, ?>) createTask.get("task");
            String taskId = (String) task.get("taskId");

            Map<String, Object> result = session.sendRequest(
                    "tasks/result",
                    Map.of("taskId", taskId),
                    MAP_TYPE)
                    .block(REQUEST_TIMEOUT);
            assertThat(result.get("content"), instanceOf(List.class));
            List<?> content = (List<?>) result.get("content");
            assertThat(content.getFirst(), instanceOf(Map.class));
            assertThat(((Map<?, ?>) content.getFirst()).get("text"), is("sampled task result"));

            Map<String, Object> request = samplingRequest.get();
            assertThat(request, is(notNullValue()));
            assertThat(request.get("_meta"), instanceOf(Map.class));
            Map<?, ?> metadata = (Map<?, ?>) request.get("_meta");
            assertThat(metadata.get(RELATED_TASK_META_KEY), instanceOf(Map.class));
            Map<?, ?> relatedTask = (Map<?, ?>) metadata.get(RELATED_TASK_META_KEY);
            assertThat(relatedTask.get("taskId"), is(taskId));

            assertThat(result.get("_meta"), instanceOf(Map.class));
            Map<?, ?> resultMetadata = (Map<?, ?>) result.get("_meta");
            assertThat(resultMetadata.get(RELATED_TASK_META_KEY), instanceOf(Map.class));
            Map<?, ?> resultRelatedTask = (Map<?, ?>) resultMetadata.get(RELATED_TASK_META_KEY);
            assertThat(resultRelatedTask.get("taskId"), is(taskId));
        } finally {
            session.closeGracefully()
                    .then(transport.closeGracefully())
                    .block(REQUEST_TIMEOUT);
        }
    }
}
