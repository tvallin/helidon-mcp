package io.helidon.extensions.mcp.tests;

import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.SetUpRoute;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.logging.McpLogLevel;
import dev.langchain4j.mcp.client.logging.McpLogMessage;
import dev.langchain4j.mcp.client.logging.McpLogMessageHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

abstract class AbstractLangchain4jLoggingTest {
    protected static McpClient client;

    @SetUpRoute
    static void routing(HttpRouting.Builder builder) {
        LoggingNotifications.setUpRoute(builder);
    }

    @AfterAll
    static void closeClient() throws Exception {
        client.close();
    }

    @Test
    void testLogging() {
        client.executeTool(ToolExecutionRequest.builder().name("logging").build());
    }

    protected static class MyLogMessageHandler implements McpLogMessageHandler {
        @Override
        public void handleLogMessage(McpLogMessage message) {
            assertThat(message.level(), is(McpLogLevel.INFO));
            assertThat(message.logger(), is("helidon-logger"));
            assertThat(message.data().asText(), is("Logging data"));
        }
    }
}
