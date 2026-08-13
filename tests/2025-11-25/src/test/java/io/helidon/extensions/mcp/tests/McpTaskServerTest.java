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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.context.Contexts;
import io.helidon.extensions.mcp.server.McpException;
import io.helidon.extensions.mcp.server.McpServerConfig;
import io.helidon.extensions.mcp.server.McpServerFeature;
import io.helidon.extensions.mcp.server.McpTaskSupport;
import io.helidon.extensions.mcp.server.McpToolRequest;
import io.helidon.extensions.mcp.server.McpToolResult;
import io.helidon.http.HeaderName;
import io.helidon.http.HeaderNames;
import io.helidon.http.HeaderValues;
import io.helidon.json.JsonNull;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@ServerTest
@Execution(ExecutionMode.SAME_THREAD)
class McpTaskServerTest {
    private static final HeaderName SESSION_ID_HEADER = HeaderNames.create("Mcp-Session-Id");
    private static final HeaderName MCP_PROTOCOL_VERSION = HeaderNames.create("Mcp-Protocol-Version");
    private static final String RELATED_TASK_META_KEY = "io.modelcontextprotocol/related-task";
    private static final AtomicReference<CountDownLatch> LIFECYCLE_GATE =
            new AtomicReference<>(new CountDownLatch(0));
    private static final AtomicReference<CountDownLatch> CANCEL_READY =
            new AtomicReference<>(new CountDownLatch(0));
    private static final AtomicReference<CountDownLatch> CANCEL_SIGNAL =
            new AtomicReference<>(new CountDownLatch(0));
    private static final AtomicReference<CountDownLatch> CANCEL_FINISHED =
            new AtomicReference<>(new CountDownLatch(0));

    private final Http1Client client;

    McpTaskServerTest(WebServer server) {
        client = Http1Client.builder()
                .baseUri("http://localhost:" + server.port())
                .build();
    }

    @SetUpRoute
    static void routing(HttpRouting.Builder builder) {
        builder.addFeature(server("/tasks", false));
        builder.addFeature(server("/stateless-tasks", true));
        builder.addFeature(McpServerFeature.builder()
                                   .path("/tasks-without-task-tools")
                                   .addTool(tool -> tool.name("forbidden")
                                           .schema("")
                                           .tool(request -> McpToolResult.builder()
                                                   .addTextContent("forbidden result")
                                                   .build())));
    }

    @Test
    void advertisesTasksWhenNoToolSupportsTaskExecution() {
        Initialization initialization = initialize("/tasks-without-task-tools", "2025-11-25");
        Session session = initialization.session();
        JsonObject capabilities = initialization.response()
                .objectValue("result")
                .orElseThrow()
                .objectValue("capabilities")
                .orElseThrow();
        JsonObject expectedTasks = JsonParser.create("""
                {
                  "list": {},
                  "cancel": {},
                  "requests": {
                    "tools": {
                      "call": {}
                    }
                  }
                }
                """).readJsonObject();

        assertThat(capabilities.objectValue("tasks").orElseThrow(), is(expectedTasks));
        JsonObject toolsResponse = rpc(session, 2, "tools/list", JsonObject.empty());
        assertThat(tools(toolsResponse).get("forbidden").containsKey("execution"), is(false));
        JsonObject tasksResponse = rpc(session, 3, "tasks/list", JsonObject.empty());
        assertThat(tasksResponse.objectValue("result")
                           .orElseThrow()
                           .arrayValue("tasks")
                           .orElseThrow()
                           .size(),
                   is(0));
    }

    @Test
    void negotiatesAndEnforcesTaskSupport() {
        Initialization initialization = initialize("/tasks", "2025-11-25");
        Session session = initialization.session();
        JsonObject capabilities = initialization.response()
                .objectValue("result")
                .orElseThrow()
                .objectValue("capabilities")
                .orElseThrow();
        JsonObject expectedTasks = JsonParser.create("""
                {
                  "list": {},
                  "cancel": {},
                  "requests": {
                    "tools": {
                      "call": {}
                    }
                  }
                }
                """).readJsonObject();
        assertThat(capabilities.objectValue("tasks").orElseThrow(), is(expectedTasks));

        JsonObject toolsResponse = rpc(session, 2, "tools/list", JsonObject.empty());
        Map<String, JsonObject> tools = tools(toolsResponse);
        assertThat(taskSupport(tools.get("optional")), is("optional"));
        assertThat(taskSupport(tools.get("required")), is("required"));
        assertThat(tools.get("forbidden").containsKey("execution"), is(false));

        JsonObject direct = rpc(session, 3, "tools/call", call("optional"));
        assertThat(text(direct.objectValue("result").orElseThrow()), is("optional result"));

        assertError(rpc(session, 4, "tools/call", call("required")), -32601);
        assertError(rpc(session, 5, "tools/call", taskCall("forbidden", JsonObject.empty())), -32601);
        assertError(rpc(session,
                        6,
                        "tools/call",
                        taskCall("optional", JsonObject.builder().set("ttl", -1).build())),
                    -32602);
        assertError(rpc(session,
                        7,
                        "tools/call",
                        taskCall("optional", JsonObject.builder().set("ttl", JsonNull.instance()).build())),
                    -32602);

        JsonObject fractional = rpc(session,
                                    8,
                                    "tools/call",
                                    taskCall("optional", JsonObject.builder().set("ttl", 60000.2).build()));
        JsonObject fractionalTask = task(fractional);
        assertThat(fractionalTask.numberValue("ttl").orElseThrow().longValue(), is(60001L));

        JsonObject directContext = rpc(session, 9, "tools/call", call("current-context"));
        String directContextState = text(directContext.objectValue("result").orElseThrow());
        JsonObject contextCreate = rpc(session,
                                       10,
                                       "tools/call",
                                       taskCall("current-context", JsonObject.empty()));
        String contextTaskId = task(contextCreate).stringValue("taskId").orElseThrow();
        JsonObject taskContext = rpc(session, 11, "tasks/result", taskId(contextTaskId));
        assertThat(text(taskContext.objectValue("result").orElseThrow()), is(directContextState));

        Initialization legacyInitialization = initialize("/tasks", "2025-06-18");
        Session legacy = legacyInitialization.session();
        JsonObject legacyCapabilities = legacyInitialization.response()
                .objectValue("result")
                .orElseThrow()
                .objectValue("capabilities")
                .orElseThrow();
        assertThat(legacyCapabilities.containsKey("tasks"), is(false));
        JsonObject legacyTools = rpc(legacy, 2, "tools/list", JsonObject.empty());
        assertThat(tools(legacyTools).get("required").containsKey("execution"), is(false));
        JsonObject legacyCall = rpc(legacy, 3, "tools/call", taskCall("required", JsonObject.empty()));
        assertThat(text(legacyCall.objectValue("result").orElseThrow()), is("required result"));
        assertError(rpc(legacy, 4, "tasks/get", taskId("unavailable")), -32601);
        assertError(rpc(legacy, 5, "tasks/result", taskId("unavailable")), -32601);
        assertError(rpc(legacy, 6, "tasks/list", JsonObject.empty()), -32601);
        assertError(rpc(legacy, 7, "tasks/cancel", taskId("unavailable")), -32601);

    }

    @Test
    void runsTasksInStatelessMode() {
        Initialization initialization = initialize("/stateless-tasks", "2025-11-25");
        Session session = initialization.session();
        JsonObject capabilities = initialization.response()
                .objectValue("result")
                .orElseThrow()
                .objectValue("capabilities")
                .orElseThrow();
        JsonObject expectedTasks = JsonParser.create("""
                {
                  "list": {},
                  "cancel": {},
                  "requests": {
                    "tools": {
                      "call": {}
                    }
                  }
                }
                """).readJsonObject();
        assertThat(capabilities.objectValue("tasks").orElseThrow(), is(expectedTasks));

        Map<String, JsonObject> tools = tools(rpc(session, 2, "tools/list", JsonObject.empty()));
        assertThat(taskSupport(tools.get("optional")), is("optional"));
        assertThat(taskSupport(tools.get("required")), is("required"));
        assertError(rpc(session, 3, "tools/call", call("required")), -32601);

        JsonObject create = rpc(session, 4, "tools/call", taskCall("required", JsonObject.empty()));
        String taskId = task(create).stringValue("taskId").orElseThrow();
        JsonObject result = rpc(session, 5, "tasks/result", taskId(taskId));
        assertThat(text(result.objectValue("result").orElseThrow()), is("required result"));
        JsonObject completed = rpc(session, 6, "tasks/get", taskId(taskId));
        assertThat(completed.objectValue("result").orElseThrow().stringValue("status").orElseThrow(),
                   is("completed"));
    }

    @Test
    void requiresInitializationForStatelessTaskOperations() {
        assertError(statelessRpc(2,
                                 "tools/call",
                                 taskCall("required", JsonObject.empty())),
                    -32600);
        assertError(statelessRpc(3, "tasks/list", JsonObject.empty()), -32600);
    }

    @Test
    void runsTaskLifecycleAndReplaysResults() {
        LIFECYCLE_GATE.set(new CountDownLatch(1));
        Session session = initialize("/tasks", "2025-11-25").session();
        JsonObject create = rpc(session,
                                2,
                                "tools/call",
                                taskCall("lifecycle", JsonObject.builder().set("ttl", 60000).build()));
        JsonObject createdTask = task(create);
        String taskId = createdTask.stringValue("taskId").orElseThrow();
        assertThat(createdTask.stringValue("status").orElseThrow(), is("working"));
        assertThat(createdTask.numberValue("ttl").orElseThrow().longValue(), is(60000L));
        JsonObject createRelatedTask = create.objectValue("result")
                .orElseThrow()
                .objectValue("_meta")
                .orElseThrow()
                .objectValue(RELATED_TASK_META_KEY)
                .orElseThrow();
        assertThat(createRelatedTask.stringValue("taskId").orElseThrow(), is(taskId));

        JsonObject get = rpc(session, 3, "tasks/get", taskId(taskId));
        assertThat(get.objectValue("result").orElseThrow().stringValue("status").orElseThrow(), is("working"));
        JsonObject list = rpc(session, 4, "tasks/list", JsonObject.empty());
        boolean listed = list.objectValue("result")
                .orElseThrow()
                .arrayValue("tasks")
                .orElseThrow()
                .values()
                .stream()
                .map(value -> value.asObject().stringValue("taskId").orElseThrow())
                .anyMatch(taskId::equals);
        assertThat(listed, is(true));

        LIFECYCLE_GATE.get().countDown();
        JsonObject resultResponse = rpc(session, 5, "tasks/result", taskId(taskId));
        JsonObject result = resultResponse.objectValue("result").orElseThrow();
        assertThat(text(result), is("lifecycle result"));
        JsonObject relatedTask = result.objectValue("_meta")
                .orElseThrow()
                .objectValue(RELATED_TASK_META_KEY)
                .orElseThrow();
        assertThat(relatedTask.stringValue("taskId").orElseThrow(), is(taskId));
        JsonObject replayedResult = rpc(session, 6, "tasks/result", taskId(taskId))
                .objectValue("result")
                .orElseThrow();
        assertThat(replayedResult, is(result));
        JsonObject completed = rpc(session, 7, "tasks/get", taskId(taskId));
        assertThat(completed.objectValue("result").orElseThrow().stringValue("status").orElseThrow(),
                   is("completed"));

        JsonObject errorCreate = rpc(session,
                                     8,
                                     "tools/call",
                                     taskCall("error", JsonObject.builder().set("ttl", 60000).build()));
        String errorTaskId = task(errorCreate).stringValue("taskId").orElseThrow();
        JsonObject errorResult = rpc(session, 9, "tasks/result", taskId(errorTaskId));
        assertThat(errorResult.objectValue("result").orElseThrow().booleanValue("isError").orElseThrow(), is(true));
        JsonObject failed = rpc(session, 10, "tasks/get", taskId(errorTaskId));
        assertThat(failed.objectValue("result").orElseThrow().stringValue("status").orElseThrow(), is("failed"));

        long nextId = assertReplayedError(session, 11, "exception");
        nextId = assertReplayedError(session, nextId, "null-exception");

        Session other = initialize("/tasks", "2025-11-25").session();
        assertError(rpc(other, 2, "tasks/get", taskId(taskId)), -32602);
        assertError(rpc(session, nextId++, "tasks/get", taskId("missing-task")), -32602);
        assertError(rpc(session,
                        nextId++,
                        "tasks/list",
                        JsonObject.builder().set("cursor", "missing-cursor").build()),
                    -32602);
        assertError(rpc(session,
                        nextId,
                        "tasks/list",
                        JsonObject.builder().set("cursor", JsonNull.instance()).build()),
                    -32602);
    }

    @Test
    void cancelsAndDeletesTask() throws InterruptedException {
        CANCEL_READY.set(new CountDownLatch(1));
        CANCEL_SIGNAL.set(new CountDownLatch(1));
        CANCEL_FINISHED.set(new CountDownLatch(1));
        Session session = initialize("/tasks", "2025-11-25").session();
        JsonObject create = rpc(session, 2, "tools/call", taskCall("cancel", JsonObject.empty()));
        String taskId = task(create).stringValue("taskId").orElseThrow();
        assertThat(CANCEL_READY.get().await(5, TimeUnit.SECONDS), is(true));

        JsonObject cancel = rpc(session, 3, "tasks/cancel", taskId(taskId));
        JsonObject cancelledTask = cancel.objectValue("result").orElseThrow();
        assertThat(cancelledTask.stringValue("taskId").orElseThrow(), is(taskId));
        assertThat(cancelledTask.stringValue("status").orElseThrow(), is("cancelled"));
        assertThat(CANCEL_SIGNAL.get().await(5, TimeUnit.SECONDS), is(true));
        assertThat(CANCEL_FINISHED.get().await(5, TimeUnit.SECONDS), is(true));

        assertError(rpc(session, 4, "tasks/get", taskId(taskId)), -32602);
        assertError(rpc(session, 5, "tasks/result", taskId(taskId)), -32602);
        assertError(rpc(session, 6, "tasks/cancel", taskId(taskId)), -32602);
    }

    private static McpServerConfig.Builder server(String path, boolean stateless) {
        McpServerConfig.Builder server = McpServerFeature.builder()
                .path(path)
                .stateless(stateless)
                .addTool(tool -> tool.name("forbidden")
                        .schema("")
                        .tool(request -> McpToolResult.builder()
                                .addTextContent("forbidden result")
                                .build()))
                .addTool(tool -> tool.name("optional")
                        .schema("")
                        .taskSupport(McpTaskSupport.OPTIONAL)
                        .tool(request -> McpToolResult.builder()
                                .addTextContent("optional result")
                                .build()))
                .addTool(tool -> tool.name("required")
                        .schema("")
                        .taskSupport(McpTaskSupport.REQUIRED)
                        .tool(request -> McpToolResult.builder()
                                .addTextContent("required result")
                                .build()))
                .addTool(tool -> tool.name("lifecycle")
                        .schema("")
                        .taskSupport(McpTaskSupport.REQUIRED)
                        .tool(McpTaskServerTest::lifecycle))
                .addTool(tool -> tool.name("error")
                        .schema("")
                        .taskSupport(McpTaskSupport.OPTIONAL)
                        .tool(request -> McpToolResult.builder()
                                .error(true)
                                .addTextContent("error result")
                                .build()))
                .addTool(tool -> tool.name("exception")
                        .schema("")
                        .taskSupport(McpTaskSupport.OPTIONAL)
                        .tool(request -> {
                            throw new McpException(-32001, "");
                        }))
                .addTool(tool -> tool.name("null-exception")
                        .schema("")
                        .taskSupport(McpTaskSupport.OPTIONAL)
                        .tool(request -> {
                            throw new NullPointerException();
                        }))
                .addTool(tool -> tool.name("current-context")
                        .schema("")
                        .taskSupport(McpTaskSupport.OPTIONAL)
                        .tool(McpTaskServerTest::currentContext))
                .addTool(tool -> tool.name("cancel")
                        .schema("")
                        .taskSupport(McpTaskSupport.REQUIRED)
                        .tool(McpTaskServerTest::cancel));
        return server;
    }

    private static McpToolResult lifecycle(McpToolRequest request) {
        try {
            if (!LIFECYCLE_GATE.get().await(10, TimeUnit.SECONDS)) {
                return McpToolResult.builder().error(true).addTextContent("lifecycle timeout").build();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpToolResult.builder().error(true).addTextContent("lifecycle interrupted").build();
        }
        return McpToolResult.builder().addTextContent("lifecycle result").build();
    }

    private static McpToolResult cancel(McpToolRequest request) {
        CountDownLatch signal = CANCEL_SIGNAL.get();
        request.features().cancellation().registerCancellationHook(signal::countDown);
        CANCEL_READY.get().countDown();
        try {
            signal.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            CANCEL_FINISHED.get().countDown();
        }
        return McpToolResult.builder().addTextContent("cancelled result").build();
    }

    private static McpToolResult currentContext(McpToolRequest request) {
        boolean currentContextAvailable = Contexts.context().isPresent();
        return McpToolResult.create(Boolean.toString(currentContextAvailable));
    }

    private Initialization initialize(String path, String protocolVersion) {
        JsonObject request = JsonObject.builder()
                .set("jsonrpc", "2.0")
                .set("id", 1)
                .set("method", "initialize")
                .set("params", JsonObject.builder()
                        .set("protocolVersion", protocolVersion)
                        .set("capabilities", JsonObject.empty())
                        .set("clientInfo", JsonObject.builder()
                                .set("name", "task-test")
                                .set("version", "1.0.0")
                                .build())
                        .build())
                .build();
        try (var response = client.post(path)
                .header(HeaderValues.CONTENT_TYPE_JSON)
                .submit(request.toString())) {
            String sessionId = response.headers().get(SESSION_ID_HEADER).get();
            JsonObject responseObject = JsonParser.create(response.entity().as(String.class)).readJsonObject();
            return new Initialization(new Session(path, sessionId, protocolVersion), responseObject);
        }
    }

    private JsonObject rpc(Session session, long id, String method, JsonObject params) {
        JsonObject request = JsonObject.builder()
                .set("jsonrpc", "2.0")
                .set("id", id)
                .set("method", method)
                .set("params", params)
                .build();
        try (var response = client.post(session.path())
                .header(SESSION_ID_HEADER, session.id())
                .header(MCP_PROTOCOL_VERSION, session.protocolVersion())
                .header(HeaderValues.CONTENT_TYPE_JSON)
                .submit(request.toString())) {
            return JsonParser.create(response.entity().as(String.class)).readJsonObject();
        }
    }

    private JsonObject statelessRpc(long id, String method, JsonObject params) {
        JsonObject request = JsonObject.builder()
                .set("jsonrpc", "2.0")
                .set("id", id)
                .set("method", method)
                .set("params", params)
                .build();
        try (var response = client.post("/stateless-tasks")
                .header(MCP_PROTOCOL_VERSION, "2025-11-25")
                .header(HeaderValues.CONTENT_TYPE_JSON)
                .submit(request.toString())) {
            return JsonParser.create(response.entity().as(String.class)).readJsonObject();
        }
    }

    private JsonObject call(String name) {
        return JsonObject.builder()
                .set("name", name)
                .set("arguments", JsonObject.empty())
                .build();
    }

    private JsonObject taskCall(String name, JsonObject task) {
        return JsonObject.builder()
                .from(call(name))
                .set("task", task)
                .build();
    }

    private JsonObject taskId(String taskId) {
        return JsonObject.builder().set("taskId", taskId).build();
    }

    private JsonObject task(JsonObject response) {
        return response.objectValue("result").orElseThrow().objectValue("task").orElseThrow();
    }

    private long assertReplayedError(Session session, long firstId, String toolName) {
        JsonObject directException = rpc(session, firstId, "tools/call", call(toolName));
        JsonObject exceptionCreate = rpc(session,
                                         firstId + 1,
                                         "tools/call",
                                         taskCall(toolName, JsonObject.empty()));
        String exceptionTaskId = task(exceptionCreate).stringValue("taskId").orElseThrow();
        JsonObject taskException = rpc(session, firstId + 2, "tasks/result", taskId(exceptionTaskId));
        JsonObject directError = directException.objectValue("error").orElseThrow();
        JsonObject taskError = taskException.objectValue("error").orElseThrow();
        assertThat(taskError.intValue("code"), is(directError.intValue("code")));
        assertThat(taskError.stringValue("message"), is(directError.stringValue("message")));
        return firstId + 3;
    }

    private Map<String, JsonObject> tools(JsonObject response) {
        Map<String, JsonObject> tools = new HashMap<>();
        for (var value : response.objectValue("result")
                .orElseThrow()
                .arrayValue("tools")
                .orElseThrow()
                .values()) {
            JsonObject tool = value.asObject();
            tools.put(tool.stringValue("name").orElseThrow(), tool);
        }
        return tools;
    }

    private String taskSupport(JsonObject tool) {
        assertThat(tool, is(notNullValue()));
        return tool.objectValue("execution").orElseThrow().stringValue("taskSupport").orElseThrow();
    }

    private String text(JsonObject result) {
        return result.arrayValue("content")
                .orElseThrow()
                .values()
                .getFirst()
                .asObject()
                .stringValue("text")
                .orElseThrow();
    }

    private void assertError(JsonObject response, int code) {
        assertThat(response.objectValue("error").orElseThrow().intValue("code").orElseThrow(), is(code));
    }

    private record Session(String path, String id, String protocolVersion) {
    }

    private record Initialization(Session session, JsonObject response) {
    }
}
