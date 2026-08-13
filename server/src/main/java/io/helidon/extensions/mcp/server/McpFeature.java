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
package io.helidon.extensions.mcp.server;

import io.helidon.json.JsonObject;

/**
 * MCP request feature base class.
 */
abstract class McpFeature {
    /**
     * MCP session to access client information.
     */
    private final McpSession session;
    private final McpFeatureTarget target;

    McpFeature(McpSession session) {
        this.session = session;
        this.target = null;
    }

    McpFeature(McpSession session, McpFeatureTarget target) {
        this.session = session;
        this.target = target;
    }

    McpSession session() {
        return session;
    }

    void send(JsonObject message) {
        session.send(target, message);
    }

    void prepareResponse(long requestId) {
        session.prepareResponse(requestId, target);
    }

    void finishResponse(long requestId) {
        session.finishResponse(requestId, target);
    }
}
