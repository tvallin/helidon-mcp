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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.json.JsonObject;
import io.helidon.webserver.jsonrpc.JsonRpcResponse;

import static io.helidon.jsonrpc.core.JsonRpcError.INTERNAL_ERROR;

final class McpTaskTransport implements McpTransport {
    static final int MAX_QUEUED_MESSAGES = 1000;

    private final McpTask task;
    private final Deque<JsonObject> queuedMessages = new ArrayDeque<>();
    private final Deque<ResultAttachment> resultTransports = new ArrayDeque<>();
    private final AtomicInteger pendingClientRequests = new AtomicInteger();
    private final Lock stateLock = new ReentrantLock();
    private final Lock deliveryLock = new ReentrantLock();

    private boolean closed;

    McpTaskTransport(McpTask task) {
        this.task = task;
    }

    @Override
    public void send(JsonObject object) {
        List<Runnable> failures = new ArrayList<>();
        stateLock.lock();
        try {
            if (closed) {
                return;
            }
        } finally {
            stateLock.unlock();
        }
        boolean clientRequest = object.containsKey("method") && object.containsKey("id");
        if (clientRequest) {
            pendingClientRequests.incrementAndGet();
            task.inputRequired();
        }
        JsonObject relatedMessage = task.relatedMessage(object);
        deliveryLock.lock();
        try {
            stateLock.lock();
            try {
                if (closed) {
                    return;
                }
                if (resultTransports.isEmpty()) {
                    enqueueLast(relatedMessage);
                    return;
                }
            } finally {
                stateLock.unlock();
            }
            if (!sendToAttachedTransport(relatedMessage, failures)) {
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

    @Override
    public void send(JsonRpcResponse response) {
        send(response.asJsonObject());
    }

    @Override
    public boolean block(Duration timeout) {
        McpTransport currentTransport;
        stateLock.lock();
        try {
            currentTransport = resultTransports.isEmpty() ? null : resultTransports.getLast().transport();
        } finally {
            stateLock.unlock();
        }
        return currentTransport != null && currentTransport.block(timeout);
    }

    @Override
    public void unblock() {
        McpTransport currentTransport;
        stateLock.lock();
        try {
            currentTransport = resultTransports.isEmpty() ? null : resultTransports.getLast().transport();
        } finally {
            stateLock.unlock();
        }
        if (currentTransport != null) {
            currentTransport.unblock();
        }
    }

    @Override
    public void clientResponseReceived(long requestId) {
        stateLock.lock();
        try {
            queuedMessages.removeIf(message -> message.longValue("id").orElse(-1L) == requestId);
        } finally {
            stateLock.unlock();
        }
        int remaining = pendingClientRequests.updateAndGet(value -> value > 0 ? value - 1 : 0);
        if (remaining == 0) {
            task.working();
        }
    }

    @Override
    public Optional<McpTaskOwner> taskOwner() {
        return Optional.of(task.owner());
    }

    ResultAttachment attach(McpTransport transport, Runnable onFailure) {
        List<Runnable> failures = new ArrayList<>();
        ResultAttachment attachment = new ResultAttachment(transport, onFailure);
        deliveryLock.lock();
        try {
            stateLock.lock();
            try {
                if (closed) {
                    return attachment;
                }
                resultTransports.addLast(attachment);
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
        return attachment;
    }

    void detach(ResultAttachment attachment) {
        stateLock.lock();
        try {
            resultTransports.removeIf(candidate -> candidate == attachment);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void close() {
        stateLock.lock();
        try {
            closed = true;
            queuedMessages.clear();
            resultTransports.clear();
        } finally {
            stateLock.unlock();
        }
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
            ResultAttachment attachment;
            stateLock.lock();
            try {
                if (closed || resultTransports.isEmpty()) {
                    return false;
                }
                attachment = resultTransports.getLast();
            } finally {
                stateLock.unlock();
            }
            try {
                attachment.transport().send(message);
                return true;
            } catch (RuntimeException e) {
                stateLock.lock();
                try {
                    resultTransports.removeIf(candidate -> candidate == attachment);
                } finally {
                    stateLock.unlock();
                }
                failures.add(attachment.onFailure());
            }
        }
    }

    record ResultAttachment(McpTransport transport, Runnable onFailure) {
    }
}
