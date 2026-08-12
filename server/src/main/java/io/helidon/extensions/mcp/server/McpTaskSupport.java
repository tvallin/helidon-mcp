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
package io.helidon.extensions.mcp.server;

import java.util.Locale;

/**
 * Task-augmented execution support for an MCP tool.
 */
public enum McpTaskSupport {
    /**
     * The tool does not support task-augmented execution.
     */
    FORBIDDEN,
    /**
     * The tool supports both direct and task-augmented execution.
     */
    OPTIONAL,
    /**
     * The tool requires task-augmented execution when Tasks are negotiated.
     */
    REQUIRED;

    String text() {
        return name().toLowerCase(Locale.ROOT);
    }
}
