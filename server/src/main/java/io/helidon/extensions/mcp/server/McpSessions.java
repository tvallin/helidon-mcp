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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.helidon.common.LruCache;

/**
 * An LRU cache for {@link McpSession}s that also implements {@link Iterable}.
 */
final class McpSessions implements LruCache<String, McpSession>, Iterable<McpSession> {

    private final Map<String, McpSession> sessions;
    private final Consumer<McpSession> removalListener;
    private final int capacity;
    private final Lock lock = new ReentrantLock();

    McpSessions(int cacheSize) {
        this(cacheSize, ignored -> { });
    }

    McpSessions(int cacheSize, Consumer<McpSession> removalListener) {
        if (cacheSize < 1) {
            throw new IllegalArgumentException("Session cache size must be positive");
        }
        this.capacity = cacheSize;
        this.removalListener = removalListener;
        this.sessions = new LinkedHashMap<>(cacheSize, 0.75F, true);
    }

    public Optional<McpSession> get(String sessionId) {
        lock.lock();
        try {
            return Optional.ofNullable(sessions.get(sessionId));
        } finally {
            lock.unlock();
        }
    }

    public Optional<McpSession> put(String sessionId, McpSession session) {
        McpSession previous;
        McpSession evicted = null;
        lock.lock();
        try {
            previous = sessions.put(sessionId, session);
            if (sessions.size() > capacity) {
                Iterator<McpSession> iterator = sessions.values().iterator();
                evicted = iterator.next();
                iterator.remove();
            }
        } finally {
            lock.unlock();
        }
        if (previous != null && previous != session) {
            removalListener.accept(previous);
        }
        if (evicted != null && evicted != previous) {
            removalListener.accept(evicted);
        }
        return Optional.ofNullable(previous);
    }

    public Optional<McpSession> remove(String sessionId) {
        McpSession removed;
        lock.lock();
        try {
            removed = sessions.remove(sessionId);
        } finally {
            lock.unlock();
        }
        if (removed != null) {
            removalListener.accept(removed);
        }
        return Optional.ofNullable(removed);
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return sessions.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public void clear() {
        List<McpSession> removed;
        lock.lock();
        try {
            removed = List.copyOf(sessions.values());
            sessions.clear();
        } finally {
            lock.unlock();
        }
        removed.forEach(removalListener);
    }

    @Override
    public Optional<McpSession> computeValue(String key, Supplier<Optional<McpSession>> valueSupplier) {
        Optional<McpSession> current = get(key);
        if (current.isPresent()) {
            return current;
        }
        Optional<McpSession> computed = valueSupplier.get();
        computed.ifPresent(value -> put(key, value));
        return computed;
    }

    @Override
    public Iterator<McpSession> iterator() {
        lock.lock();
        try {
            return List.copyOf(sessions.values()).iterator();
        } finally {
            lock.unlock();
        }
    }
}
