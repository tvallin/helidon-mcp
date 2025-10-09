package io.helidon.extensions.mcp.examples.coffee.shop;

import java.util.List;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
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

    @SetUpRoute
    static void routing(HttpRouting.Builder builder) {
        Main.setUpRoute(builder);
    }

    @AfterAll
    static void closeClient() throws Exception {
        client.close();
    }

    @Test
    void testListTools() {
        List<ToolSpecification> tools = client.listTools();
        assertThat(tools.size(), is(1));

        ToolSpecification tool1 = tools.getFirst();
        assertThat(tool1.name(), is("menu-manager"));
        assertThat(tool1.description(), is("Provides a list of coffee"));
        assertThat(tool1.parameters().properties().isEmpty(), is(true));
    }

    @Test
    void testMenuManager() {
        var result = client.executeTool(ToolExecutionRequest.builder()
                                                .name("menu-manager")
                                                .build());
        assertThat(result.isError(), is(false));
        assertThat(result.resultText(), containsString("Latte"));
    }
}
