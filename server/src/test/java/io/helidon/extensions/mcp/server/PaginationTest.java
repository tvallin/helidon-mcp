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

import org.junit.jupiter.api.Test;

import static io.helidon.extensions.mcp.server.McpPagination.DEFAULT_PAGE_SIZE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class PaginationTest {

    @Test
    void testPaginationOrder() {
        List<String> strings = List.of("a", "b", "c", "d");
        McpPagination<String> pagination = new McpStaticPagination<>(strings, 2);
        assertThat(pagination.content(), hasItems("a", "b", "c", "d"));

        McpPage<String> page1 = pagination.firstPage();
        assertThat(page1.isLast(), is(false));
        assertThat(page1.components(), contains("a", "b"));

        McpPage<String> page2 = pagination.page(page1.cursor());
        assertThat(page2.isLast(), is(true));
        assertThat(page2.cursor().isBlank(), is(true));
        assertThat(page2.components(), contains("c", "d"));
    }

    @Test
    void testPaginationMatchingSize() {
        List<String> strings = List.of("a", "b", "c", "d");
        McpPagination<String> pagination = new McpStaticPagination<>(strings, 4);
        assertThat(pagination.content(), hasItems("a", "b", "c", "d"));

        McpPage<String> page = pagination.firstPage();
        assertThat(page.isLast(), is(true));
        assertThat(page.cursor().isBlank(), is(true));
        assertThat(page.components(), contains("a", "b", "c", "d"));
    }

    @Test
    void testPaginationOversize() {
        List<String> strings = List.of("a", "b", "c", "d");
        McpPagination<String> pagination = new McpStaticPagination<>(strings, 10);
        assertThat(pagination.content(), hasItems("a", "b", "c", "d"));

        McpPage<String> page = pagination.firstPage();
        assertThat(page.isLast(), is(true));
        assertThat(page.cursor().isBlank(), is(true));
        assertThat(page.components(), contains("a", "b", "c", "d"));
    }

    @Test
    void testPaginationSize() {
        List<String> strings = List.of("a", "b", "c", "d");
        McpPagination<String> pagination = new McpStaticPagination<>(strings, 3);
        assertThat(pagination.content(), hasItems("a", "b", "c", "d"));

        McpPage<String> page1 = pagination.firstPage();
        assertThat(page1.isLast(), is(false));
        assertThat(page1.components(), contains("a", "b", "c"));

        McpPage<String> page2 = pagination.page(page1.cursor());
        assertThat(page2.isLast(), is(true));
        assertThat(page2.components(), contains("d"));
        assertThat(page2.cursor().isBlank(), is(true));
    }

    @Test
    void testPaginationWithUniqueItem() {
        List<String> strings = List.of("a", "b", "c", "d");
        McpPagination<String> pagination = new McpStaticPagination<>(strings, 1);
        assertThat(pagination.content(), hasItems("a", "b", "c", "d"));

        McpPage<String> page1 = pagination.firstPage();
        assertThat(page1.isLast(), is(false));
        assertThat(page1.components(), contains("a"));
        assertThat(page1.cursor().isBlank(), is(false));

        McpPage<String> page2 = pagination.page(page1.cursor());
        assertThat(page2.isLast(), is(false));
        assertThat(page2.components(), contains("b"));
        assertThat(page2.cursor().isBlank(), is(false));

        McpPage<String> page3 = pagination.page(page2.cursor());
        assertThat(page3.isLast(), is(false));
        assertThat(page3.components(), contains("c"));
        assertThat(page3.cursor().isBlank(), is(false));

        McpPage<String> page4 = pagination.page(page3.cursor());
        assertThat(page4.isLast(), is(true));
        assertThat(page4.components(), contains("d"));
        assertThat(page4.cursor().isBlank(), is(true));
    }

    @Test
    void testPaginationDefaultSize() {
        List<String> strings = List.of("a", "b", "c", "d");
        McpPagination<String> pagination = new McpStaticPagination<>(strings, DEFAULT_PAGE_SIZE);
        assertThat(pagination.content(), hasItems("a", "b", "c", "d"));

        McpPage<String> page = pagination.firstPage();
        assertThat(page.isLast(), is(true));
        assertThat(page.cursor().isBlank(), is(true));
        assertThat(page.components(), contains("a", "b", "c", "d"));
    }

    @Test
    void testPaginationEmptyContent() {
        McpPagination<String> pagination = new McpStaticPagination<>(List.of(), 2);
        assertThat(pagination.content(), is(List.of()));

        McpPage<String> page = pagination.firstPage();
        assertThat(page.isLast(), is(true));
        assertThat(page.cursor().isBlank(), is(true));
        assertThat(page.components(), is(List.of()));
    }

    @Test
    void testTaskPaginationAcrossSnapshots() {
        McpTask a = new McpTask("a", new McpTaskOwner("owner"), 1000, 1000);
        McpTask b = new McpTask("b", new McpTaskOwner("owner"), 1000, 1000);
        McpTask c = new McpTask("c", new McpTaskOwner("owner"), 1000, 1000);
        McpPagination<McpTask> initial = new McpMutablePagination(List.of(a, b, c), 2);

        McpPage<McpTask> first = initial.firstPage();

        assertThat(first.components(), contains(a, b));
        assertThat(first.cursor(), is("b"));

        McpTask aa = new McpTask("aa", new McpTaskOwner("owner"), 1000, 1000);
        McpTask d = new McpTask("d", new McpTaskOwner("owner"), 1000, 1000);
        McpPagination<McpTask> changed = new McpMutablePagination(List.of(aa, b, c, d), 2);
        McpPage<McpTask> second = changed.page(first.cursor());

        assertThat(second.components(), contains(c, d));
        assertThat(second.isLast(), is(true));
        assertThat(second.cursor(), is(""));
        assertThat(changed.page("missing"), is(nullValue()));
    }
}
