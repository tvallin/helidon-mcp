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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.http.HeaderValues;
import io.helidon.http.sse.SseEvent;
import io.helidon.json.JsonObject;
import io.helidon.webserver.jsonrpc.JsonRpcResponse;
import io.helidon.webserver.sse.SseSink;

import static io.helidon.extensions.mcp.server.McpJsonSerializer.prettyPrint;

final class McpStreamableHttpTransport implements McpTransport {
    private static final System.Logger LOGGER = System.getLogger(McpStreamableHttpTransport.class.getName());

    private final CountDownLatch latch;
    private final JsonRpcResponse response;
    private final Lock stateLock = new ReentrantLock();
    private final Condition sinkCreated = stateLock.newCondition();
    private SseSink sseSink;
    private boolean creatingSink;
    private boolean closed;

    McpStreamableHttpTransport(JsonRpcResponse response) {
        this.response = response;
        this.latch = new CountDownLatch(1);
    }

    @Override
    public void send(JsonObject object) {
        if (LOGGER.isLoggable(System.Logger.Level.DEBUG)) {
            LOGGER.log(System.Logger.Level.DEBUG, "Streamable Http:\n" + prettyPrint(object));
        }
        sink().emit(SseEvent.builder()
                            .name("message")
                            .data(object.toString())
                            .build());
    }

    @Override
    public void send(JsonRpcResponse response) {
        if (LOGGER.isLoggable(System.Logger.Level.DEBUG)) {
            LOGGER.log(System.Logger.Level.DEBUG, "Streamable Http:\n" + prettyPrint(response.asJsonObject()));
        }
        SseSink currentSink = currentSink();
        if (currentSink != null) {
            currentSink.emit(SseEvent.builder()
                                     .name("message")
                                     .data(response.asJsonObject().toString())
                                     .build());
            currentSink.close();
            return;
        }
        response.header(HeaderValues.CONTENT_TYPE_JSON);
        response.send();
    }

    @Override
    public boolean block(Duration timeout) {
        try {
            boolean completed = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                    LOGGER.log(System.Logger.Level.TRACE, "Blocking timeout reached");
                }
            }
            return completed;
        } catch (InterruptedException e) {
            if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                LOGGER.log(System.Logger.Level.TRACE, "Interrupted while blocking", e);
            }
            return false;
        }
    }

    @Override
    public void unblock() {
        latch.countDown();
    }

    @Override
    public void close() {
        SseSink currentSink;
        stateLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            currentSink = sseSink;
            sinkCreated.signalAll();
        } finally {
            stateLock.unlock();
        }
        latch.countDown();
        if (currentSink != null) {
            currentSink.close();
        }
    }

    boolean openedSseChannel() {
        stateLock.lock();
        try {
            return sseSink != null;
        } finally {
            stateLock.unlock();
        }
    }

    private SseSink sink() {
        stateLock.lock();
        try {
            while (creatingSink && !closed) {
                awaitSinkCreation();
            }
            if (closed) {
                throw new McpInternalException("Streamable HTTP transport is closed");
            }
            if (sseSink != null) {
                return sseSink;
            }
            creatingSink = true;
        } finally {
            stateLock.unlock();
        }

        SseSink createdSink;
        try {
            response.header(HeaderValues.CONTENT_TYPE_EVENT_STREAM);
            createdSink = response.sink(SseSink.TYPE);
        } catch (RuntimeException | Error e) {
            stateLock.lock();
            try {
                creatingSink = false;
                sinkCreated.signalAll();
            } finally {
                stateLock.unlock();
            }
            throw e;
        }

        stateLock.lock();
        try {
            creatingSink = false;
            if (!closed) {
                sseSink = createdSink;
            }
            sinkCreated.signalAll();
            if (!closed) {
                return createdSink;
            }
        } finally {
            stateLock.unlock();
        }
        createdSink.close();
        throw new McpInternalException("Streamable HTTP transport is closed");
    }

    private SseSink currentSink() {
        stateLock.lock();
        try {
            while (creatingSink && !closed) {
                awaitSinkCreation();
            }
            if (closed) {
                throw new McpInternalException("Streamable HTTP transport is closed");
            }
            return sseSink;
        } finally {
            stateLock.unlock();
        }
    }

    private void awaitSinkCreation() {
        try {
            sinkCreated.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpInternalException("Interrupted while opening streamable HTTP transport", e);
        }
    }
}
