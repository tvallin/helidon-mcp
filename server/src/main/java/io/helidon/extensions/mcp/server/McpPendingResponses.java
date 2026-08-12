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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.json.JsonObject;

/**
 * Pending JSON-RPC responses for an MCP session.
 */
final class McpPendingResponses {
    private final Lock lock = new ReentrantLock();
    private final int capacity;
    private final Map<Long, PendingResponse> responses = new HashMap<>();

    private boolean active = true;

    McpPendingResponses(int capacity) {
        this.capacity = capacity;
    }

    void prepare(long requestId) {
        prepare(requestId, new UnownedPendingResponse(new CompletableFuture<>()));
    }

    void prepare(long requestId, McpTaskOwner owner) {
        prepare(requestId, new OwnedPendingResponse(new CompletableFuture<>(), owner));
    }

    private void prepare(long requestId, PendingResponse response) {
        lock.lock();
        try {
            if (!active) {
                throw new McpInternalException("Session disconnected");
            }
            if (responses.containsKey(requestId)) {
                return;
            }
            if (responses.size() >= capacity) {
                throw new McpInternalException("Maximum pending response count reached");
            }
            responses.put(requestId, response);
        } finally {
            lock.unlock();
        }
    }

    void accept(long requestId, JsonObject response) {
        pendingResponse(requestId)
                .filter(UnownedPendingResponse.class::isInstance)
                .ifPresent(pending -> pending.future().complete(response));
    }

    void accept(long requestId, JsonObject response, McpTaskOwner owner) {
        pendingResponse(requestId)
                .filter(pending -> pending.accepts(owner))
                .ifPresent(pending -> pending.future().complete(response));
    }

    private Optional<PendingResponse> pendingResponse(long requestId) {
        lock.lock();
        try {
            if (!active) {
                return Optional.empty();
            }
            return Optional.ofNullable(responses.get(requestId));
        } finally {
            lock.unlock();
        }
    }

    Optional<JsonObject> poll(long requestId, Duration timeout) {
        CompletableFuture<JsonObject> pendingResponse;
        lock.lock();
        try {
            if (!active) {
                throw new McpInternalException("Session disconnected");
            }
            PendingResponse response = responses.get(requestId);
            if (response == null) {
                throw new McpInternalException("No pending response for request id " + requestId);
            }
            pendingResponse = response.future();
        } finally {
            lock.unlock();
        }
        try {
            return Optional.of(pendingResponse.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpInternalException("Session interrupted.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new McpInternalException("Unable to receive session response", cause);
        }
    }

    void discard(long requestId) {
        lock.lock();
        try {
            responses.remove(requestId);
        } finally {
            lock.unlock();
        }
    }

    void disconnect() {
        List<CompletableFuture<JsonObject>> pending;
        lock.lock();
        try {
            if (!active) {
                return;
            }
            active = false;
            pending = responses.values().stream()
                    .map(PendingResponse::future)
                    .toList();
            responses.clear();
        } finally {
            lock.unlock();
        }
        var exception = new McpInternalException("Session disconnected");
        pending.forEach(response -> response.completeExceptionally(exception));
    }

    private sealed interface PendingResponse permits OwnedPendingResponse, UnownedPendingResponse {
        CompletableFuture<JsonObject> future();

        boolean accepts(McpTaskOwner owner);
    }

    private record OwnedPendingResponse(CompletableFuture<JsonObject> future,
                                        McpTaskOwner owner) implements PendingResponse {
        @Override
        public boolean accepts(McpTaskOwner owner) {
            return this.owner.equals(owner);
        }
    }

    private record UnownedPendingResponse(CompletableFuture<JsonObject> future) implements PendingResponse {
        @Override
        public boolean accepts(McpTaskOwner owner) {
            return true;
        }
    }
}
