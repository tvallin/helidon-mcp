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

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.json.JsonObject;
import io.helidon.json.JsonString;

import static io.helidon.jsonrpc.core.JsonRpcError.INVALID_PARAMS;

final class McpTask {
    static final String RELATED_TASK_META_KEY = "io.modelcontextprotocol/related-task";

    private final String id;
    private final McpTaskOwner owner;
    private final Instant createdAt;
    private final long expiresAtMillis;
    private final long ttl;
    private final long pollInterval;
    private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition outcomeAvailable = lock.newCondition();

    private volatile Thread executionThread;
    private volatile McpFeatures features;
    private Future<?> expiration;
    private boolean deleted;
    private Status status = Status.WORKING;
    private String statusMessage = "";
    private Instant lastUpdatedAt;

    McpTask(String id, McpTaskOwner owner, long ttl, long pollInterval) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        this.createdAt = Instant.now();
        this.expiresAtMillis = expirationTime(System.currentTimeMillis(), ttl);
        this.ttl = ttl;
        this.lastUpdatedAt = createdAt;
        this.pollInterval = pollInterval;
    }

    static McpTask create(McpTaskOwner owner, long ttl, long pollInterval) {
        String id = UUID.randomUUID().toString();
        return new McpTask(id, owner, ttl, pollInterval);
    }

    JsonObject toJson() {
        lock.lock();
        try {
            JsonObject.Builder builder = JsonObject.builder()
                    .set("taskId", id)
                    .set("status", status.text())
                    .set("createdAt", createdAt.toString())
                    .set("lastUpdatedAt", lastUpdatedAt.toString());
            builder.set("ttl", ttl);
            if (!statusMessage.isBlank()) {
                builder.set("statusMessage", statusMessage);
            }
            if (pollInterval > 0) {
                builder.set("pollInterval", pollInterval);
            }
            return builder.build();
        } finally {
            lock.unlock();
        }
    }

    boolean cancel() {
        lock.lock();
        try {
            if (status.terminal()) {
                return false;
            }
            update(Status.CANCELLED, "The task was cancelled by request.");
            outcome.complete(new ErrorOutcome(INVALID_PARAMS, "Task not found: " + id));
            outcomeAvailable.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    void inputRequired() {
        lock.lock();
        try {
            if (status == Status.WORKING) {
                update(Status.INPUT_REQUIRED, "");
            }
        } finally {
            lock.unlock();
        }
    }

    void working() {
        lock.lock();
        try {
            if (status == Status.INPUT_REQUIRED) {
                update(Status.WORKING, "");
            }
        } finally {
            lock.unlock();
        }
    }

    void complete(JsonObject result, boolean error) {
        lock.lock();
        try {
            if (outcome.isDone()) {
                return;
            }
            if (!status.terminal()) {
                update(error ? Status.FAILED : Status.COMPLETED,
                       error ? "Tool execution returned an error result." : "");
            }
            outcome.complete(new ResultOutcome(result));
            outcomeAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void fail(int code, String message) {
        lock.lock();
        try {
            if (outcome.isDone()) {
                return;
            }
            if (!status.terminal()) {
                update(Status.FAILED, message);
            }
            outcome.complete(new ErrorOutcome(code, message));
            outcomeAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void expire() {
        boolean cancelExecution;
        lock.lock();
        try {
            delete();
            cancelExecution = !status.terminal();
            if (cancelExecution) {
                update(Status.CANCELLED, "The task expired.");
            }
            outcome.complete(new ErrorOutcome(INVALID_PARAMS, "Task not found: " + id));
            outcomeAvailable.signalAll();
        } finally {
            lock.unlock();
        }
        if (cancelExecution) {
            cancelExecution("The task expired.");
        }
    }

    boolean start(Thread executionThread, McpFeatures features) {
        lock.lock();
        try {
            this.executionThread = Objects.requireNonNull(executionThread);
            this.features = Objects.requireNonNull(features);
            if (terminal()) {
                cancelExecution(statusMessage);
                return false;
            }
            executionThread.start();
            return true;
        } finally {
            lock.unlock();
        }
    }

    void expiration(Future<?> expiration) {
        lock.lock();
        try {
            Objects.requireNonNull(expiration);
            if (deleted) {
                expiration.cancel(false);
            } else {
                this.expiration = expiration;
            }
        } finally {
            lock.unlock();
        }
    }

    void delete() {
        lock.lock();
        try {
            deleted = true;
            if (expiration != null) {
                expiration.cancel(false);
                expiration = null;
            }
        } finally {
            lock.unlock();
        }
    }

    void cancelExecution(String reason) {
        McpFeatures currentFeatures = features;
        if (currentFeatures != null) {
            currentFeatures.cancellation().cancel(reason, JsonString.create(id));
        }
        Thread currentThread = executionThread;
        if (currentThread != null) {
            currentThread.interrupt();
        }
    }

    Outcome awaitOutcome() {
        try {
            return outcome.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpInternalException("Interrupted while waiting for task result", e);
        } catch (ExecutionException e) {
            throw new McpInternalException("Unable to retrieve task result", e);
        }
    }

    Outcome awaitOutcome(McpTaskResultWaiters.Waiter waiter) {
        lock.lock();
        try {
            while (!outcome.isDone() && !waiter.abandoned()) {
                outcomeAvailable.await();
            }
            if (waiter.abandoned()) {
                throw new McpInternalException("Task result request was abandoned");
            }
            return outcome.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpInternalException("Interrupted while waiting for task result", e);
        } finally {
            lock.unlock();
        }
    }

    void wakeResultWaiters() {
        lock.lock();
        try {
            outcomeAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void executionFinished() {
        lock.lock();
        try {
            executionThread = null;
            features = null;
        } finally {
            lock.unlock();
        }
    }

    JsonObject relatedResult(JsonObject result) {
        JsonObject meta = result.objectValue("_meta").orElse(JsonObject.empty());
        JsonObject relatedMeta = JsonObject.builder()
                .from(meta)
                .set(RELATED_TASK_META_KEY, JsonObject.builder().set("taskId", id).build())
                .build();
        return JsonObject.builder()
                .from(result)
                .set("_meta", relatedMeta)
                .build();
    }

    JsonObject relatedMessage(JsonObject message) {
        if (message.containsKey("method")) {
            JsonObject params = message.objectValue("params").orElse(JsonObject.empty());
            JsonObject meta = params.objectValue("_meta").orElse(JsonObject.empty());
            JsonObject relatedMeta = JsonObject.builder()
                    .from(meta)
                    .set(RELATED_TASK_META_KEY, JsonObject.builder().set("taskId", id).build())
                    .build();
            JsonObject relatedParams = JsonObject.builder()
                    .from(params)
                    .set("_meta", relatedMeta)
                    .build();
            return JsonObject.builder().from(message).set("params", relatedParams).build();
        }
        if (message.containsKey("result")) {
            JsonObject result = message.objectValue("result").orElse(JsonObject.empty());
            return JsonObject.builder().from(message).set("result", relatedResult(result)).build();
        }
        return message;
    }

    boolean expired(long now) {
        return now >= expiresAtMillis;
    }

    boolean ownedBy(McpTaskOwner owner) {
        return this.owner.equals(owner);
    }

    String id() {
        return id;
    }

    Instant createdAt() {
        return createdAt;
    }

    McpTaskOwner owner() {
        return owner;
    }

    boolean terminal() {
        lock.lock();
        try {
            return status.terminal();
        } finally {
            lock.unlock();
        }
    }

    private void update(Status newStatus, String message) {
        status = newStatus;
        statusMessage = message;
        lastUpdatedAt = Instant.now();
    }

    private static long expirationTime(long createdAt, long ttl) {
        try {
            return Math.addExact(createdAt, ttl);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    enum Status {
        WORKING,
        INPUT_REQUIRED,
        COMPLETED,
        FAILED,
        CANCELLED;

        String text() {
            return name().toLowerCase(Locale.ROOT);
        }

        boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    sealed interface Outcome permits ResultOutcome, ErrorOutcome {
    }

    record ResultOutcome(JsonObject result) implements Outcome {
        ResultOutcome {
            Objects.requireNonNull(result);
        }
    }

    record ErrorOutcome(int code, String message) implements Outcome {
        ErrorOutcome {
            Objects.requireNonNull(message);
        }
    }
}
