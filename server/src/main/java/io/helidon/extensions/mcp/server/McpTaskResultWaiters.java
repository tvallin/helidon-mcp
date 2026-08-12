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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.json.JsonValue;

final class McpTaskResultWaiters {
    private static final System.Logger LOGGER = System.getLogger(McpTaskResultWaiters.class.getName());

    private final Map<String, Waiter> requestWaiters = new HashMap<>();
    private final int capacity;
    private final Lock stateLock = new ReentrantLock();

    private boolean active = true;

    McpTaskResultWaiters(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Task result waiter capacity must be positive");
        }
        this.capacity = capacity;
    }

    Waiter register(JsonValue requestId, McpTask task, McpTransport transport) {
        String requestKey = requestId.toString();
        Waiter waiter;
        stateLock.lock();
        try {
            if (!active) {
                throw new McpInternalException("Session disconnected");
            }
            if (requestWaiters.containsKey(requestKey)) {
                throw new McpInternalException("Task result waiter already exists for request id " + requestId);
            }
            if (requestWaiters.size() >= capacity) {
                throw new McpInternalException("Maximum task result waiter count reached");
            }
            waiter = new Waiter(requestKey,
                                task,
                                task.owner(),
                                transport);
            requestWaiters.put(requestKey, waiter);
        } finally {
            stateLock.unlock();
        }
        return waiter;
    }

    boolean attach(Waiter waiter) {
        stateLock.lock();
        try {
            if (requestWaiters.get(waiter.requestKey()) != waiter) {
                return false;
            }
        } finally {
            stateLock.unlock();
        }

        boolean attached = waiter.attach();
        stateLock.lock();
        try {
            if (requestWaiters.get(waiter.requestKey()) == waiter && attached) {
                return true;
            }
            if (!attached) {
                requestWaiters.remove(waiter.requestKey(), waiter);
            }
        } finally {
            stateLock.unlock();
        }
        waiter.release();
        return false;
    }

    boolean claim(Waiter waiter) {
        stateLock.lock();
        try {
            if (requestWaiters.get(waiter.requestKey()) != waiter || !waiter.claim()) {
                return false;
            }
            requestWaiters.remove(waiter.requestKey());
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    boolean abandon(JsonValue requestId, McpTaskOwner owner) {
        Waiter waiter;
        stateLock.lock();
        try {
            waiter = requestWaiters.get(requestId.toString());
            if (waiter == null || !waiter.owner().equals(owner)) {
                return false;
            }
            requestWaiters.remove(waiter.requestKey());
        } finally {
            stateLock.unlock();
        }
        waiter.abandon();
        return true;
    }

    void discard(Waiter waiter) {
        stateLock.lock();
        try {
            requestWaiters.remove(waiter.requestKey(), waiter);
        } finally {
            stateLock.unlock();
        }
        waiter.release();
    }

    void disconnect() {
        List<Waiter> waiters;
        stateLock.lock();
        try {
            if (!active) {
                return;
            }
            active = false;
            waiters = new ArrayList<>(requestWaiters.values());
            requestWaiters.clear();
        } finally {
            stateLock.unlock();
        }
        waiters.forEach(Waiter::beginAbandonment);
        waiters.forEach(Waiter::closeTransport);
    }

    static final class Waiter {
        private final String requestKey;
        private final McpTask task;
        private final McpTaskOwner owner;
        private final McpTransport transport;
        private final Lock stateLock = new ReentrantLock();
        private State state = State.ACTIVE;
        private McpTaskTransport.ResultAttachment attachment;
        private McpTaskTransport.ResultAttachment detachOnClose;

        private Waiter(String requestKey,
                       McpTask task,
                       McpTaskOwner owner,
                       McpTransport transport) {
            this.requestKey = requestKey;
            this.task = task;
            this.owner = owner;
            this.transport = transport;
        }

        String requestKey() {
            return requestKey;
        }

        McpTaskOwner owner() {
            return owner;
        }

        McpTransport transport() {
            return transport;
        }

        boolean abandoned() {
            stateLock.lock();
            try {
                return state == State.ABANDONED;
            } finally {
                stateLock.unlock();
            }
        }

        private boolean attach() {
            stateLock.lock();
            try {
                if (state != State.ACTIVE) {
                    return false;
                }
            } finally {
                stateLock.unlock();
            }
            try {
                McpTaskTransport.ResultAttachment attachment = task.transport().attach(transport, this::abandon);
                boolean detach;
                stateLock.lock();
                try {
                    if (state != State.ACTIVE) {
                        detach = true;
                    } else {
                        this.attachment = attachment;
                        detach = false;
                    }
                } finally {
                    stateLock.unlock();
                }
                if (detach) {
                    task.transport().detach(attachment);
                }
                return !detach;
            } catch (RuntimeException e) {
                abandon();
                return false;
            }
        }

        private boolean claim() {
            McpTaskTransport.ResultAttachment attachment;
            stateLock.lock();
            try {
                if (state != State.ACTIVE) {
                    return false;
                }
                state = State.CLAIMED;
                attachment = this.attachment;
                this.attachment = null;
            } finally {
                stateLock.unlock();
            }
            if (attachment != null) {
                task.transport().detach(attachment);
            }
            return true;
        }

        private void abandon() {
            if (beginAbandonment()) {
                closeTransport();
            }
        }

        private boolean beginAbandonment() {
            stateLock.lock();
            try {
                if (state != State.ACTIVE) {
                    return false;
                }
                state = State.ABANDONED;
                detachOnClose = attachment;
                attachment = null;
            } finally {
                stateLock.unlock();
            }
            task.wakeResultWaiters();
            return true;
        }

        private void release() {
            McpTaskTransport.ResultAttachment attachment;
            stateLock.lock();
            try {
                attachment = this.attachment;
                this.attachment = null;
                if (state == State.ACTIVE || state == State.CLAIMED) {
                    state = State.RELEASED;
                }
            } finally {
                stateLock.unlock();
            }
            if (attachment != null) {
                task.transport().detach(attachment);
            }
        }

        private void closeTransport() {
            McpTaskTransport.ResultAttachment attachment;
            stateLock.lock();
            try {
                attachment = detachOnClose;
                detachOnClose = null;
            } finally {
                stateLock.unlock();
            }
            if (attachment != null) {
                task.transport().detach(attachment);
            }
            try {
                transport.close();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.DEBUG, "Unable to close abandoned task result transport", e);
            }
        }

        private enum State {
            ACTIVE,
            CLAIMED,
            ABANDONED,
            RELEASED
        }
    }
}
