package io.helidon.extensions.mcp.examples.coffee.shop;

import io.helidon.config.Config;
import io.helidon.extensions.mcp.server.McpServerFeature;
import io.helidon.service.registry.Services;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.HttpRouting;

/**
 * Main class for running an MCP server that manages coffee shop menu.
 */
public class Main {

    private Main() {
    }

    /**
     * Start the application.
     *
     * @param args command line arguments, currently ignored
     */
    public static void main(String[] args) {
        var config = Services.get(Config.class);

        var server = WebServerConfig.builder()
                .config(config.get("server"))
                .routing(Main::setUpRoute)
                .build()
                .start();
    }

    static void setUpRoute(HttpRouting.Builder builder) {
        var config = Services.get(Config.class);
        builder.addFeature(McpServerFeature.builder()
                                   .config(config.get("mcp.server"))
                                   .addTool(new MenuManagerTool())
                                   .build());
    }
}