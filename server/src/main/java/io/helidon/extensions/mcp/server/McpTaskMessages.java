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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.json.JsonObject;

import static io.helidon.jsonrpc.core.JsonRpcError.INTERNAL_ERROR;

final class McpTaskMessages {
    static final int MAX_QUEUED_MESSAGES = 1000;

    private final Map<McpTask, Channel> channels = new IdentityHashMap<>();
    private final Lock lock = new ReentrantLock();

    private boolean active = true;

    void register(McpTask task) {
        lock.lock();
        try {
            if (!active) {
                throw new McpInternalException("Session disconnected");
            }
            channels.computeIfAbsent(task, Channel::new);
        } finally {
            lock.unlock();
        }
    }

    void send(McpTask task, JsonObject message) {
        Channel channel = findChannel(task);
        if (channel != null) {
            channel.send(message);
        }
    }

    void clientResponseReceived(McpTask task, long requestId) {
        Channel channel = findChannel(task);
        if (channel != null) {
            channel.clientResponseReceived(requestId);
        }
    }

    boolean cancel(McpTask task) {
        Channel channel = findChannel(task);
        return channel == null ? task.cancel() : channel.cancel();
    }

    void expire(McpTask task) {
        Channel channel = findChannel(task);
        if (channel == null) {
            task.expire();
        } else {
            channel.expire();
        }
    }

    boolean attach(McpTaskResultWaiters.Waiter waiter) {
        Channel channel = findChannel(waiter.task());
        return channel == null || channel.attach(waiter);
    }

    void detach(McpTaskResultWaiters.Waiter waiter) {
        Channel channel = findChannel(waiter.task());
        if (channel != null) {
            channel.detach(waiter);
        }
    }

    void close(McpTask task) {
        lock.lock();
        try {
            Channel channel = channels.get(task);
            if (channel != null) {
                channel.close();
                channels.remove(task);
            }
        } finally {
            lock.unlock();
        }
    }

    void disconnect() {
        lock.lock();
        try {
            if (!active) {
                return;
            }
            active = false;
            channels.values().forEach(Channel::close);
            channels.clear();
        } finally {
            lock.unlock();
        }
    }

    private Channel findChannel(McpTask task) {
        lock.lock();
        try {
            return channels.get(task);
        } finally {
            lock.unlock();
        }
    }

    private static final class Channel {
        private final McpTask task;
        private final Deque<JsonObject> queuedMessages = new ArrayDeque<>();
        private final Deque<McpTaskResultWaiters.Waiter> resultWaiters = new ArrayDeque<>();
        private final Set<Long> pendingClientRequests = new HashSet<>();
        private final Map<Long, JsonObject> queuedClientRequests = new HashMap<>();
        private final Lock stateLock = new ReentrantLock();
        private final Lock deliveryLock = new ReentrantLock();

        private boolean closed;

        private Channel(McpTask task) {
            this.task = task;
        }

        private void send(JsonObject message) {
            List<Runnable> failures = new ArrayList<>();
            JsonObject relatedMessage = task.relatedMessage(message);
            Long clientRequestId = clientRequestId(message);
            boolean queued = false;
            deliveryLock.lock();
            try {
                stateLock.lock();
                try {
                    if (closed) {
                        return;
                    }
                    if (resultWaiters.isEmpty()) {
                        ensureQueueCapacity();
                    }
                    if (clientRequestId != null && pendingClientRequests.add(clientRequestId)) {
                        queuedClientRequests.put(clientRequestId, relatedMessage);
                        task.inputRequired();
                    }
                    if (resultWaiters.isEmpty()) {
                        queuedMessages.addLast(relatedMessage);
                        queued = true;
                    }
                } finally {
                    stateLock.unlock();
                }
                if (!queued && !sendToAttachedTransport(relatedMessage, failures)) {
                    stateLock.lock();
                    try {
                        if (!closed) {
                            enqueueFirst(relatedMessage);
                        }
                    } finally {
                        stateLock.unlock();
                    }
                }
            } finally {
                deliveryLock.unlock();
                failures.forEach(Runnable::run);
            }
        }

        private boolean attach(McpTaskResultWaiters.Waiter waiter) {
            List<Runnable> failures = new ArrayList<>();
            deliveryLock.lock();
            try {
                stateLock.lock();
                try {
                    if (closed) {
                        return true;
                    }
                    resultWaiters.addLast(waiter);
                } finally {
                    stateLock.unlock();
                }
                while (true) {
                    JsonObject message;
                    stateLock.lock();
                    try {
                        if (closed || queuedMessages.isEmpty()) {
                            break;
                        }
                        message = queuedMessages.removeFirst();
                    } finally {
                        stateLock.unlock();
                    }
                    if (!sendToAttachedTransport(message, failures)) {
                        stateLock.lock();
                        try {
                            if (!closed) {
                                enqueueFirst(message);
                            }
                        } finally {
                            stateLock.unlock();
                        }
                        break;
                    }
                }
            } finally {
                deliveryLock.unlock();
                failures.forEach(Runnable::run);
            }
            return true;
        }

        private void detach(McpTaskResultWaiters.Waiter waiter) {
            stateLock.lock();
            try {
                resultWaiters.removeIf(candidate -> candidate == waiter);
            } finally {
                stateLock.unlock();
            }
        }

        private void clientResponseReceived(long requestId) {
            stateLock.lock();
            try {
                if (!pendingClientRequests.remove(requestId)) {
                    return;
                }
                JsonObject queuedRequest = queuedClientRequests.remove(requestId);
                if (queuedRequest != null) {
                    queuedMessages.removeIf(message -> message == queuedRequest);
                }
                if (pendingClientRequests.isEmpty()) {
                    task.working();
                }
            } finally {
                stateLock.unlock();
            }
        }

        private boolean cancel() {
            stateLock.lock();
            try {
                if (!task.cancel()) {
                    return false;
                }
                closeState();
                return true;
            } finally {
                stateLock.unlock();
            }
        }

        private void expire() {
            stateLock.lock();
            try {
                task.expire();
                closeState();
            } finally {
                stateLock.unlock();
            }
        }

        private void close() {
            stateLock.lock();
            try {
                closeState();
            } finally {
                stateLock.unlock();
            }
        }

        private void closeState() {
            closed = true;
            queuedMessages.clear();
            queuedClientRequests.clear();
            pendingClientRequests.clear();
            resultWaiters.clear();
        }

        private void enqueueFirst(JsonObject message) {
            ensureQueueCapacity();
            queuedMessages.addFirst(message);
        }

        private void enqueueLast(JsonObject message) {
            ensureQueueCapacity();
            queuedMessages.addLast(message);
        }

        private void ensureQueueCapacity() {
            if (queuedMessages.size() >= MAX_QUEUED_MESSAGES) {
                throw new McpInternalException(INTERNAL_ERROR, "Task message queue capacity reached");
            }
        }

        private boolean sendToAttachedTransport(JsonObject message, List<Runnable> failures) {
            while (true) {
                McpTaskResultWaiters.Waiter waiter;
                stateLock.lock();
                try {
                    if (closed || resultWaiters.isEmpty()) {
                        return false;
                    }
                    waiter = resultWaiters.getLast();
                } finally {
                    stateLock.unlock();
                }
                try {
                    waiter.transport().send(message);
                    return true;
                } catch (RuntimeException e) {
                    stateLock.lock();
                    try {
                        resultWaiters.removeIf(candidate -> candidate == waiter);
                    } finally {
                        stateLock.unlock();
                    }
                    failures.add(waiter::deliveryFailed);
                }
            }
        }

        private static Long clientRequestId(JsonObject message) {
            if (!message.containsKey("method") || !message.containsKey("id")) {
                return null;
            }
            var requestId = message.longValue("id");
            return requestId.orElse(null);
        }
    }
}
