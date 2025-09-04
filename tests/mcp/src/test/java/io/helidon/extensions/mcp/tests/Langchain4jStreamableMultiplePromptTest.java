package io.helidon.extensions.mcp.tests;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.testing.junit5.ServerTest;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;

@ServerTest
class Langchain4jStreamableMultiplePromptTest extends AbstractLangchain4jMultiplePromptTest {

    Langchain4jStreamableMultiplePromptTest(WebServer server) {
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:" + server.port())
                .logRequests(true)
                .logResponses(true)
                .build();
        client = new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
    }
}
