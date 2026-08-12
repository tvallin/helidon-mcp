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

import java.util.List;

/**
 * Mutable pagination over a refreshed task list using task identifiers as cursors.
 */
final class McpMutablePagination implements McpPagination<McpTask> {
    private final List<McpTask> tasks;
    private final int pageSize;

    McpMutablePagination(List<McpTask> tasks, int pageSize) {
        this.tasks = List.copyOf(tasks);
        this.pageSize = pageSize;
    }

    @Override
    public McpPage<McpTask> firstPage() {
        return page(0);
    }

    @Override
    public McpPage<McpTask> page(String cursor) {
        for (int i = 0; i < tasks.size(); i++) {
            if (cursor.equals(tasks.get(i).id())) {
                return page(i + 1);
            }
        }
        return null;
    }

    @Override
    public List<McpTask> content() {
        return tasks;
    }

    private McpPage<McpTask> page(int start) {
        if (start >= tasks.size()) {
            return new McpPage<>(List.of());
        }
        int end = pageSize == DEFAULT_PAGE_SIZE ? tasks.size() : Math.min(start + pageSize, tasks.size());
        List<McpTask> pageTasks = tasks.subList(start, end);
        boolean isLast = end == tasks.size();
        String nextCursor = isLast ? "" : pageTasks.getLast().id();
        return new McpPage<>(pageTasks, nextCursor, isLast);
    }
}
