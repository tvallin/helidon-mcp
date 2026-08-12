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

import java.util.List;

import io.helidon.json.JsonString;
import io.helidon.jsonrpc.core.JsonRpcParams;

/**
 * Support for MCP pagination feature.
 * <p>
 * Pagination is supported by the following MCP methods:
 * <ul>
 *     <li>
 *         {@link McpJsonSerializer#METHOD_TOOLS_LIST}
 *         List the tools registered on the server.
 *     </li>
 *     <li>
 *         {@link McpJsonSerializer#METHOD_PROMPT_LIST}
 *         List the prompts registered on the server.
 *     </li>
 *     <li>
 *         {@link McpJsonSerializer#METHOD_RESOURCES_LIST}
 *         List the resources registered on the server.
 *     </li>
 *     <li>
 *         {@link McpJsonSerializer#METHOD_RESOURCES_TEMPLATES_LIST}
 *         List the resource templates registered on the server.
 *     </li>
 *     <li>
 *         {@link McpJsonSerializer#METHOD_TASKS_LIST}
 *         List the tasks created on the server.
 *     </li>
 * </ul>
 * <p>
 * Pagination enables the server to return results in smaller, manageable chunks rather than
 * delivering the entire dataset at once. The size of each chunk is configured via the {@code page-size}
 * property. {@link McpStaticPagination} provides opaque cursors for fixed component lists, while
 * {@link McpMutablePagination} uses task identifiers as cursors for refreshed task lists.
 *
 * @param <T> MCP components type
 */
sealed interface McpPagination<T> permits McpStaticPagination, McpMutablePagination {
    int DEFAULT_PAGE_SIZE = 0;

    /**
     * First page.
     *
     * @return first page
     */
    McpPage<T> firstPage();

    /**
     * Page following the provided cursor.
     *
     * @param cursor cursor from the preceding page
     * @return page, or {@code null} if the cursor is unknown
     */
    McpPage<T> page(String cursor);

    /**
     * All content represented by this pagination instance.
     *
     * @return content
     */
    List<T> content();

    /**
     * Page selected by the cursor request parameter, or the first page when no cursor is provided.
     *
     * @param params request parameters
     * @return selected page
     */
    default McpPage<T> page(JsonRpcParams params) {
        return params.find("cursor")
                .map(JsonString.class::cast)
                .map(JsonString::value)
                .map(this::page)
                .orElse(this.firstPage());
    }
}
