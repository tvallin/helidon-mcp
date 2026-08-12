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

import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.json.JsonNull;
import io.helidon.json.JsonValue;

/**
 * The MCP Cancellation feature enables verification of whether a client
 * has issued a cancellation request. Such requests are typically made when
 * a process is taking an extended amount of time, and the client opts not
 * to wait for the completion of the operation.
 */
public final class McpCancellation {
    private static final int MAX_CONCURRENT_HOOKS = 1000;
    private static final System.Logger LOGGER = System.getLogger(McpCancellation.class.getName());
    private static final Semaphore HOOK_PERMITS = new Semaphore(MAX_CONCURRENT_HOOKS);

    private final Lock lock = new ReentrantLock();
    private volatile McpCancellationResult result;
    private Runnable hook = () -> {};
    private JsonValue cancellationRequestId = JsonNull.instance();
    private boolean hookRegistered;
    private boolean hookInvoked;

    McpCancellation() {
        result = new McpCancellationResultImpl(false);
    }

    /**
     * Check whether a cancellation request was made.
     *
     * @return cancellation result
     */
    public McpCancellationResult result() {
        return result;
    }

    /**
     * Actions to be performed when cancellation get triggered.
     *
     * @param hook cancellation hook
     */
    public void registerCancellationHook(Runnable hook) {
        Objects.requireNonNull(hook, "hook must not be null");
        Runnable hookToRun = null;
        JsonValue requestId = JsonNull.instance();
        lock.lock();
        try {
            this.hook = hook;
            hookRegistered = true;
            if (result.isRequested() && !hookInvoked) {
                hookInvoked = true;
                hookToRun = hook;
                requestId = cancellationRequestId;
            }
        } finally {
            lock.unlock();
        }
        if (hookToRun != null) {
            runHook(hookToRun, requestId);
        }
    }

    /**
     * Cancel the current operation. This method can be triggered only once and
     * additional calls are ignored.
     *
     * @param reason cancellation reason
     * @param requestId request ID to be canceled
     */
    void cancel(String reason, JsonValue requestId) {
        cancel(new McpCancellationResultImpl(true, reason), requestId);
    }

    /**
     * Cancel the current operation without a reason. This method can be triggered only once and
     * additional calls are ignored.
     *
     * @param requestId request ID to be canceled
     */
    void cancel(JsonValue requestId) {
        cancel(new McpCancellationResultImpl(true), requestId);
    }

    private void cancel(McpCancellationResult cancellationResult, JsonValue requestId) {
        Runnable hookToRun = null;
        lock.lock();
        try {
            if (result.isRequested()) {
                return;
            }
            result = cancellationResult;
            cancellationRequestId = requestId;
            if (hookRegistered) {
                hookInvoked = true;
                hookToRun = hook;
            }
        } finally {
            lock.unlock();
        }
        if (LOGGER.isLoggable(Level.DEBUG)) {
            LOGGER.log(Level.DEBUG, "Cancelling task with request id: %s", requestId);
        }
        if (hookToRun != null) {
            runHook(hookToRun, requestId);
        }
    }

    private void runHook(Runnable hook, JsonValue requestId) {
        if (!HOOK_PERMITS.tryAcquire()) {
            LOGGER.log(Level.WARNING, "Cancellation hook capacity reached for request id: " + requestId);
            return;
        }
        Thread.ofVirtual().name("mcp-cancellation-hook").start(() -> {
            try {
                hook.run();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Cancellation hook failed for request id: " + requestId, e);
            } finally {
                HOOK_PERMITS.release();
            }
        });
    }
}
