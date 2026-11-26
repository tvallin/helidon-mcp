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

/**
 * Implement alongside an MCP component interface to handle cancellation of individual invocations.
 * The server registers the callback before invoking the component and removes it when the invocation ends.
 */
@FunctionalInterface
public interface McpCancellable {
    /**
     * Handle cancellation for the given invocation. The callback receives the same request as the component
     * and may run concurrently with it. If cancellation has already been requested, the callback runs before
     * the component is invoked.
     *
     * @param request request being cancelled
     */
    void onCancel(McpRequest request);
}
