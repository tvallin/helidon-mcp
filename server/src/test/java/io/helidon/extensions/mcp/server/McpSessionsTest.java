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

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;

class McpSessionsTest {
    @Test
    void invokesRemovalListenerOutsideCacheLock() throws InterruptedException {
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        McpSessions sessions = new McpSessions(1, ignored -> {
            listenerEntered.countDown();
            try {
                if (!releaseListener.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release removal listener");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });
        McpSession first = mock(McpSession.class);
        McpSession second = mock(McpSession.class);
        sessions.put("first", first);

        Thread eviction = Thread.ofVirtual().start(() -> {
            try {
                sessions.put("second", second);
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        assertThat(listenerEntered.await(5, TimeUnit.SECONDS), is(true));
        AtomicReference<Optional<McpSession>> lookup = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> lookup.set(sessions.get("second")));
        try {
            reader.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(reader.isAlive(), is(false));
            assertThat(lookup.get().orElseThrow(), sameInstance(second));
        } finally {
            releaseListener.countDown();
        }
        eviction.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(eviction.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
    }
}
