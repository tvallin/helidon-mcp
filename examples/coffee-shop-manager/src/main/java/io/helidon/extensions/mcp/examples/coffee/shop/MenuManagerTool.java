package io.helidon.extensions.mcp.examples.coffee.shop;

import java.io.InputStream;
import java.util.List;
import java.util.Scanner;
import java.util.function.Function;

import io.helidon.extensions.mcp.server.McpRequest;
import io.helidon.extensions.mcp.server.McpTool;
import io.helidon.extensions.mcp.server.McpToolContent;
import io.helidon.extensions.mcp.server.McpToolContents;

class MenuManagerTool implements McpTool {
    private static final InputStream MENU = MenuManagerTool.class.getResourceAsStream("/menu.json");

    @Override
    public String name() {
        return "menu-manager";
    }

    @Override
    public String description() {
        return "Provides a list of coffee";
    }

    @Override
    public String schema() {
        return "";
    }

    @Override
    public Function<McpRequest, List<McpToolContent>> tool() {
        return request -> {
            StringBuilder builder = new StringBuilder();
            try (Scanner scanner = new Scanner(MENU)) {
                while(scanner.hasNextLine()){
                    String line = scanner.nextLine();
                    builder.append(line).append("\n");
                }
            }
            return List.of(McpToolContents.textContent(builder.toString()));
        };
    }
}
