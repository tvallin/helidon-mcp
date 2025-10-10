/*
 * Copyright (c) 2025 Oracle and/or its affiliates.
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

package io.helidon.extensions.mcp.examples.coffee.shop.declarative;

import java.util.List;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.testing.junit5.ServerTest;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@ServerTest
class Langchain4jTest {
    private static McpClient client;

    Langchain4jTest(WebServer server) {
        McpTransport transport = new HttpMcpTransport.Builder()
                .sseUrl("http://localhost:" + server.port() + "/mcp-coffee-shop")
                .logRequests(true)
                .logResponses(true)
                .build();
        client = new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
    }

    @AfterAll
    static void closeClient() throws Exception {
        client.close();
    }

    @Test
    void testListTools() {
        List<ToolSpecification> tools = client.listTools();
        assertThat(tools.size(), is(3));

        ToolSpecification takingOrder = tools.get(2);
        assertThat(takingOrder.name(), is("takeOrder"));
        assertThat(takingOrder.description(), is("Take an order"));
        assertThat(takingOrder.parameters().properties().isEmpty(), is(false));

        ToolSpecification orderManager = tools.get(1);
        assertThat(orderManager.name(), is("listOrders"));
        assertThat(orderManager.description(), is("Give the list of orders"));
        assertThat(orderManager.parameters().properties().isEmpty(), is(true));

        ToolSpecification menuManager = tools.getFirst();
        assertThat(menuManager.name(), is("getMenu"));
        assertThat(menuManager.description(), is("Provides the coffee shop menu"));
        assertThat(menuManager.parameters().properties().isEmpty(), is(true));
    }

    @Test
    void testMenuManager() {
        var result = client.executeTool(ToolExecutionRequest.builder()
                                                .name("getMenu")
                                                .build());
        assertThat(result.isError(), is(false));
        assertThat(result.resultText(), containsString("Latte"));
    }

    @Test
    void testOrderManager() {
        var result = client.executeTool(ToolExecutionRequest.builder()
                                                .name("listOrders")
                                                .build());
        assertThat(result.isError(), is(false));
        assertThat(result.resultText(), containsString("content"));
    }
}
