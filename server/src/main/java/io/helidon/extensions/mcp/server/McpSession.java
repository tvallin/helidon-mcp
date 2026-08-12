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
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.common.LazyValue;
import io.helidon.common.LruCache;
import io.helidon.common.context.Context;
import io.helidon.json.JsonException;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonValue;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.jsonrpc.JsonRpcRequest;
import io.helidon.webserver.jsonrpc.JsonRpcResponse;

import static io.helidon.extensions.mcp.server.McpJsonSerializer.prettyPrint;
import static io.helidon.extensions.mcp.server.McpSession.State.UNINITIALIZED;

class McpSession {
    private static final System.Logger LOGGER = System.getLogger(McpSession.class.getName());

    private final String id;
    private final McpSessions sessions;
    private final McpTransportManager manager;
    private final Context context = Context.create();
    private final Set<McpCapability> clientCapabilities;
    private final AtomicLong jsonRpcId = new AtomicLong(0);
    private final LruCache<String, McpFeatures> features;
    private final List<McpFeatureLifecycle> featureListeners;
    private final LruCache<String, McpTransport> transports;
    private final LazyValue<McpSessionFeatures> sessionFeatures;
    private final McpPendingResponses pendingResponses;
    private final McpTaskResultWaiters taskResultWaiters;
    private final Lock lifecycleLock = new ReentrantLock();
    private final Condition lifecycleChanged = lifecycleLock.newCondition();
    private final Set<String> creatingTransports = new HashSet<>();
    private final ThreadLocal<Integer> activeOperationDepth = new ThreadLocal<>();

    private McpJsonSerializer serializer;
    private volatile McpTaskOwner.AuthorizationIdentity authorizationIdentity =
            McpTaskOwner.authorizationIdentity(Context.create());
    private volatile boolean authorizationBound;
    private volatile State state = UNINITIALIZED;
    private volatile McpProtocolVersion protocolVersion;
    private boolean active = true;
    private boolean closing;
    private boolean closeDeferred;
    private boolean closeStarted;
    private int activeOperations;

    McpSession(McpSessions sessions, McpTransportManager manager, McpServerConfig config, String id) {
        this.id = id;
        this.manager = manager;
        this.sessions = sessions;
        this.clientCapabilities = new HashSet<>();
        this.featureListeners = new CopyOnWriteArrayList<>();
        this.features = LruCache.create(config.maxRequestsPerSession());
        this.transports = LruCache.create(config.maxRequestsPerSession());
        this.pendingResponses = new McpPendingResponses(config.maxRequestsPerSession());
        this.taskResultWaiters = new McpTaskResultWaiters(config.maxRequestsPerSession());
        this.featureListeners.add(McpProgress.McpProgressListener.create());
        this.sessionFeatures = LazyValue.create(() -> new McpSessionFeatures(this));
        this.context.register(McpServerConfigBlueprint.class, config);
    }

    void send(JsonValue id, JsonRpcResponse response) {
        String key = id.toString();
        McpTransport transport = transports.get(key)
                .orElseThrow(() -> new McpInternalException("No transport for id " + id));
        send(id, response, transport);
    }

    void send(JsonValue id, JsonRpcResponse response, McpTransport transport) {
        try {
            transport.send(response);
        } finally {
            clearRequest(id);
        }
    }

    void onConnect(ServerResponse response) {
        context.register(McpRoots.McpRootClassifier.class, true);
        manager.onConnect(response);
    }

    void onDisconnect(ServerResponse response) {
        close();
        sessions.remove(id);
        manager.onDisconnect(response);
    }

    void close() {
        state = State.DISCONNECTED;
        boolean closeResources = false;
        lifecycleLock.lock();
        try {
            if (!active || closing) {
                return;
            }
            closing = true;
            Integer operationDepth = activeOperationDepth.get();
            if (operationDepth != null && operationDepth > 0) {
                closeDeferred = true;
                return;
            }
            while (activeOperations > 0) {
                lifecycleChanged.awaitUninterruptibly();
            }
            active = false;
            closeStarted = true;
            closeResources = true;
        } finally {
            lifecycleLock.unlock();
        }
        if (closeResources) {
            closeResources();
        }
    }

    McpTransport onRequest(JsonValue id, JsonRpcRequest req, JsonRpcResponse res) {
        beginActiveOperation();
        try {
            McpTransport transport = createTransport(id.toString(), req, res);
            manager.onRequest(req, res);
            return transport;
        } finally {
            endActiveOperation();
        }
    }

    McpTransport createTransport(JsonValue id, JsonRpcRequest req, JsonRpcResponse res) {
        beginActiveOperation();
        try {
            return createTransport(id.toString(), req, res);
        } finally {
            endActiveOperation();
        }
    }

    McpSession onNotification(JsonRpcRequest req, JsonRpcResponse res) {
        manager.onNotification(req, res);
        return this;
    }

    void beforeFeatureRequest(McpParameters parameters, JsonValue requestId) {
        features.get(requestId.toString()).ifPresent(feature -> beforeFeatureRequest(parameters, feature));
    }

    void beforeFeatureRequest(McpParameters parameters, McpFeatures features) {
        for (McpFeatureLifecycle listener : featureListeners) {
            listener.beforeRequest(parameters, features);
        }
    }

    void afterFeatureRequest(McpParameters parameters, JsonValue requestId) {
        features.get(requestId.toString()).ifPresent(feature -> afterFeatureRequest(parameters, feature));
    }

    void afterFeatureRequest(McpParameters parameters, McpFeatures features) {
        for (McpFeatureLifecycle listener : featureListeners) {
            listener.afterRequest(parameters, features);
        }
    }

    void acceptResponse(JsonObject response, Context requestContext) {
        try {
            long requestId = response.longValue("id").orElseThrow();
            pendingResponses.accept(requestId, response, new McpTaskOwner(this, requestContext));
        } catch (JsonException | NoSuchElementException e) {
            if (LOGGER.isLoggable(Level.TRACE)) {
                LOGGER.log(Level.TRACE, "Received a response with wrong request id type", e);
            }
        }
    }

    void prepareResponse(long requestId) {
        pendingResponses.prepare(requestId);
    }

    void prepareResponse(long requestId, McpTransport transport) {
        Optional<McpTaskOwner> owner = transport.taskOwner();
        if (owner.isPresent()) {
            pendingResponses.prepare(requestId, owner.get());
        } else {
            pendingResponses.prepare(requestId);
        }
    }

    void discardResponse(long requestId) {
        pendingResponses.discard(requestId);
    }

    McpTaskResultWaiters.Waiter registerTaskResult(JsonValue requestId, McpTask task, McpTransport transport) {
        return taskResultWaiters.register(requestId, task, transport);
    }

    boolean attachTaskResult(McpTaskResultWaiters.Waiter waiter) {
        return taskResultWaiters.attach(waiter);
    }

    boolean claimTaskResult(McpTaskResultWaiters.Waiter waiter) {
        return taskResultWaiters.claim(waiter);
    }

    boolean abandonTaskResult(JsonValue requestId, Context requestContext) {
        return taskResultWaiters.abandon(requestId, new McpTaskOwner(this, requestContext));
    }

    void discardTaskResult(McpTaskResultWaiters.Waiter waiter) {
        taskResultWaiters.discard(waiter);
    }

    McpTask createTask(McpTasks tasks, Context requestContext) {
        beginActiveOperation();
        try {
            return tasks.create(this, requestContext);
        } finally {
            endActiveOperation();
        }
    }

    McpTask createTask(McpTasks tasks, Context requestContext, long ttl) {
        beginActiveOperation();
        try {
            return tasks.create(this, requestContext, ttl);
        } finally {
            endActiveOperation();
        }
    }

    McpFeatures createFeatures(JsonValue requestId, JsonRpcRequest request, JsonRpcResponse response) {
        String key = requestId.toString();
        var transport = transports.get(key)
                .orElseThrow(() -> new McpInternalException("No transport for request id " + requestId));
        return createFeatures(requestId, transport, request.context());
    }

    McpFeatures createFeatures(JsonValue requestId, McpTransport transport, Context requestContext) {
        String key = requestId.toString();
        McpFeatures feat = new McpFeatures(this, transport, requestContext);
        features.put(key, feat);
        return feat;
    }

    McpFeatures createFeatures(McpTransport transport, Context requestContext) {
        return new McpFeatures(this, transport, requestContext);
    }

    JsonObject pollResponse(long requestId, Duration timeout) {
        Optional<JsonObject> response = pendingResponses.poll(requestId, timeout);
        if (response.isEmpty()) {
            return serializer.jsonrpcErrorTimeoutResponse(requestId);
        }
        JsonObject jsonResponse = response.get();
        if (LOGGER.isLoggable(System.Logger.Level.DEBUG)) {
            LOGGER.log(System.Logger.Level.DEBUG, "Response:\n" + prettyPrint(jsonResponse));
        }
        return jsonResponse;
    }

    /**
     * Generates a unique JSON-RPC {@code id} for an outbound request to the client.
     * The returned identifier is guaranteed to be unused by any prior request in this session.
     *
     * @return a new request id
     */
    long jsonRpcId() {
        return jsonRpcId.getAndIncrement();
    }

    void clearRequest(JsonValue requestId) {
        String key = requestId.toString();
        features.remove(key);
        transports.remove(key);
    }

    Optional<McpFeatures> findFeatures(JsonValue requestId) {
        return features.get(requestId.toString());
    }

    McpSessionFeatures features() {
        return sessionFeatures.get();
    }

    McpSessions sessions() {
        return sessions;
    }

    String id() {
        return id;
    }

    void bindAuthorization(Context requestContext) {
        authorizationIdentity = McpTaskOwner.authorizationIdentity(requestContext);
        authorizationBound = true;
    }

    boolean authorized(Context requestContext) {
        return !authorizationBound
                || authorizationIdentity.equals(McpTaskOwner.authorizationIdentity(requestContext));
    }

    private McpTransport createTransport(String key, JsonRpcRequest request, JsonRpcResponse response) {
        lifecycleLock.lock();
        try {
            while (creatingTransports.contains(key)) {
                lifecycleChanged.awaitUninterruptibly();
            }
            Optional<McpTransport> existing = transports.get(key);
            if (existing.isPresent()) {
                return existing.get();
            }
            creatingTransports.add(key);
        } finally {
            lifecycleLock.unlock();
        }

        McpTransport transport;
        try {
            transport = manager.create(request, response);
        } catch (RuntimeException | Error e) {
            lifecycleLock.lock();
            try {
                creatingTransports.remove(key);
                lifecycleChanged.signalAll();
            } finally {
                lifecycleLock.unlock();
            }
            throw e;
        }

        lifecycleLock.lock();
        try {
            transports.put(key, transport);
            return transport;
        } finally {
            creatingTransports.remove(key);
            lifecycleChanged.signalAll();
            lifecycleLock.unlock();
        }
    }

    private void beginActiveOperation() {
        lifecycleLock.lock();
        try {
            if (!active || closing) {
                throw new McpInternalException("Session disconnected");
            }
            Integer operationDepth = activeOperationDepth.get();
            int depth = operationDepth == null ? 0 : operationDepth;
            if (depth == 0) {
                activeOperations++;
            }
            activeOperationDepth.set(depth + 1);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void endActiveOperation() {
        boolean closeResources = false;
        lifecycleLock.lock();
        try {
            int depth = activeOperationDepth.get() - 1;
            if (depth > 0) {
                activeOperationDepth.set(depth);
                return;
            }
            activeOperationDepth.remove();
            activeOperations--;
            if (activeOperations == 0) {
                lifecycleChanged.signalAll();
                if (closeDeferred && !closeStarted) {
                    active = false;
                    closeStarted = true;
                    closeResources = true;
                }
            }
        } finally {
            lifecycleLock.unlock();
        }
        if (closeResources) {
            closeResources();
        }
    }

    private void closeResources() {
        taskResultWaiters.disconnect();
        pendingResponses.disconnect();
        manager.close();
    }

    void capability(McpCapability capability) {
        clientCapabilities.add(capability);
    }

    void initializeClientCapabilities(McpParameters capabilities) {
        var sampling = capabilities.get(McpCapability.SAMPLING.text());
        sampling.ifPresent(it -> clientCapabilities.add(McpCapability.SAMPLING));
        if (protocolVersion == McpProtocolVersion.VERSION_2025_11_25) {
            sampling.get("tools")
                    .ifPresent(it -> clientCapabilities.add(McpCapability.SAMPLING_TOOLS));
        }
        sampling.get("context")
                .ifPresent(it -> clientCapabilities.add(McpCapability.SAMPLING_CONTEXT));

        // Ensure backward compatibility with earlier specification versions.
        if (protocolVersion != McpProtocolVersion.VERSION_2025_11_25 && sampling.isPresent()) {
            clientCapabilities.add(McpCapability.SAMPLING_CONTEXT);
        }

        capabilities.get(McpCapability.ROOTS.text())
                .ifPresent(it -> clientCapabilities.add(McpCapability.ROOTS));

        var elicitation = capabilities.get(McpCapability.ELICITATION.text());
        elicitation.ifPresent(it -> clientCapabilities.add(McpCapability.ELICITATION));
        if (protocolVersion == McpProtocolVersion.VERSION_2025_11_25) {
            elicitation.asMap()
                    .filter(Map::isEmpty)
                    .ifPresent(it -> clientCapabilities.add(McpCapability.ELICITATION_FORM));
        } else {
            elicitation.ifPresent(it -> clientCapabilities.add(McpCapability.ELICITATION_FORM));
        }
        elicitation.get("form")
                .ifPresent(it -> clientCapabilities.add(McpCapability.ELICITATION_FORM));
        elicitation.get("url")
                .ifPresent(it -> clientCapabilities.add(McpCapability.ELICITATION_URL));
    }

    Set<McpCapability> capabilities() {
        return clientCapabilities;
    }

    Context context() {
        return context;
    }

    void protocolVersion(McpProtocolVersion protocolVersion) {
        this.protocolVersion = protocolVersion;
        this.serializer = McpJsonSerializer.create(protocolVersion);
    }

    McpProtocolVersion protocolVersion() {
        return protocolVersion;
    }

    McpJsonSerializer serializer() {
        return serializer;
    }

    Optional<McpTransport> transport(JsonValue id) {
        return transports.get(id.toString());
    }

    void state(State state) {
        this.state = state;
    }

    State state() {
        return state;
    }

    enum State {
        DISCONNECTED,
        INITIALIZED,
        INITIALIZING,
        UNINITIALIZED
    }

}
