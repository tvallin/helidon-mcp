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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Static pagination over a fixed snapshot using opaque cursors.
 *
 * @param <T> MCP component type
 */
final class McpStaticPagination<T> implements McpPagination<T> {
    private final List<T> components;
    private final Map<String, Integer> cursorIndexes;
    private final Map<Integer, String> pageCursors;
    private final int pageSize;

    McpStaticPagination(List<T> components, int pageSize) {
        this.components = List.copyOf(components);
        this.pageSize = pageSize;
        Map<String, Integer> cursorIndexes = new HashMap<>();
        Map<Integer, String> pageCursors = new HashMap<>();
        if (pageSize != DEFAULT_PAGE_SIZE) {
            for (int nextPage = pageSize; nextPage < this.components.size(); nextPage += pageSize) {
                String cursor = UUID.randomUUID().toString();
                cursorIndexes.put(cursor, nextPage);
                pageCursors.put(nextPage - 1, cursor);
            }
        }
        this.cursorIndexes = Map.copyOf(cursorIndexes);
        this.pageCursors = Map.copyOf(pageCursors);
    }

    @Override
    public McpPage<T> firstPage() {
        return page(0);
    }

    @Override
    public McpPage<T> page(String cursor) {
        Integer start = cursorIndexes.get(cursor);
        return start == null ? null : page(start);
    }

    @Override
    public List<T> content() {
        return components;
    }

    private McpPage<T> page(int start) {
        if (start >= components.size()) {
            return new McpPage<>(List.of());
        }
        int end = pageSize == DEFAULT_PAGE_SIZE ? components.size() : Math.min(start + pageSize, components.size());
        List<T> pageComponents = components.subList(start, end);
        boolean isLast = end == components.size();
        String nextCursor = isLast ? "" : pageCursors.get(end - 1);
        return new McpPage<>(pageComponents, nextCursor, isLast);
    }
}
