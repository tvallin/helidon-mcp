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

import java.security.Principal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.context.Context;
import io.helidon.common.security.SecurityContext;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.json.JsonValue;
import io.helidon.jsonrpc.core.JsonRpcParams;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.jsonrpc.JsonRpcRequest;
import io.helidon.webserver.jsonrpc.JsonRpcResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpSessionTest {

    @Test
    void doesNotNegotiateMissingElicitationCapability() {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, """
                {}
                """);

        assertThat(session.capabilities().contains(McpCapability.ELICITATION), is(false));
    }

    @Test
    void negotiatesEmptyElicitationCapability() {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, """
                {"elicitation": {}}
                """);

        assertThat(session.capabilities().contains(McpCapability.ELICITATION_FORM), is(true));
    }

    @Test
    void negotiatesFormElicitationCapability() {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, """
                {"elicitation": {"form": {}}}
                """);

        assertThat(session.capabilities().contains(McpCapability.ELICITATION_FORM), is(true));
    }

    @Test
    void negotiatesUrlElicitationCapability() {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, """
                {"elicitation": {"url": {}}}
                """);

        assertThat(session.capabilities(),
                   containsInAnyOrder(McpCapability.ELICITATION,
                                      McpCapability.ELICITATION_URL));
    }

    @Test
    void disablesFormElicitationForUrlOnlyCapability() {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, """
                {"elicitation": {"url": {}}}
                """);
        McpElicitation elicitation = new McpElicitation(session,
                                                        new McpStreamableHttpTransport(mock(JsonRpcResponse.class)));

        assertThat(elicitation.enabled(), is(false));
    }

    @Test
    void negotiatesFormAndUrlElicitationCapabilities() {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, """
                {"elicitation": {"form": {}, "url": {}}}
                """);

        assertThat(session.capabilities(),
                   containsInAnyOrder(McpCapability.ELICITATION,
                                      McpCapability.ELICITATION_FORM,
                                      McpCapability.ELICITATION_URL));
    }

    @Test
    void negotiatesLatestSamplingSubcapabilities() {
        McpSession sampling = session(McpProtocolVersion.VERSION_2025_11_25, """
                {"sampling": {}}
                """);
        McpSession samplingContext = session(McpProtocolVersion.VERSION_2025_11_25, """
                {"sampling": {"context": {}}}
                """);
        McpSession samplingTools = session(McpProtocolVersion.VERSION_2025_11_25, """
                {"sampling": {"tools": {}}}
                """);

        assertThat(sampling.capabilities().contains(McpCapability.SAMPLING), is(true));
        assertThat(sampling.capabilities().contains(McpCapability.SAMPLING_CONTEXT), is(false));
        assertThat(sampling.capabilities().contains(McpCapability.SAMPLING_TOOLS), is(false));
        assertThat(samplingContext.capabilities().contains(McpCapability.SAMPLING), is(true));
        assertThat(samplingContext.capabilities().contains(McpCapability.SAMPLING_CONTEXT), is(true));
        assertThat(samplingTools.capabilities().contains(McpCapability.SAMPLING), is(true));
        assertThat(samplingTools.capabilities().contains(McpCapability.SAMPLING_TOOLS), is(true));
    }

    @ParameterizedTest
    @EnumSource(value = McpProtocolVersion.class,
                names = {"VERSION_2024_11_05", "VERSION_2025_03_26", "VERSION_2025_06_18"})
    void ignoresSamplingToolsCapabilityForLegacyProtocolVersions(McpProtocolVersion protocolVersion) {
        McpSession session = session(protocolVersion, """
                {"sampling": {"tools": {}}}
                """);

        assertThat(session.capabilities().contains(McpCapability.SAMPLING), is(true));
        assertThat(session.capabilities().contains(McpCapability.SAMPLING_TOOLS), is(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"listChanged\": false}", "{\"listChanged\": true}"})
    void negotiatesRootsCapabilityRegardlessOfListChanged(String rootsCapability) {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, """
                {"roots": %s}
                """.formatted(rootsCapability));

        assertThat(session.capabilities().contains(McpCapability.ROOTS), is(true));
    }

    @Test
    void doesNotNegotiateMissingRootsCapability() {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, "{}");

        assertThat(session.capabilities().contains(McpCapability.ROOTS), is(false));
    }

    @Test
    void preservesLegacyElicitationCapability() {
        McpSession session = session(McpProtocolVersion.VERSION_2025_06_18, """
                {"elicitation": {}}
                """);

        assertThat(session.capabilities().contains(McpCapability.ELICITATION_FORM), is(true));
    }

    @Test
    void ignoresResponseWithNonNumericId() {
        McpServerConfig config = McpServerConfig.create();
        McpSessions sessions = new McpSessions(config.maxSessionCount());
        McpSession session = new McpSession(sessions,
                                            mock(McpTransportManager.class),
                                            config,
                                            "test-session");
        JsonObject malformedResponse = JsonObject.builder()
                .set("id", "not-a-number")
                .build();
        JsonObject expectedResponse = JsonObject.builder()
                .set("id", 42)
                .build();

        session.prepareResponse(42);
        session.acceptResponse(malformedResponse, Context.create());
        session.acceptResponse(expectedResponse, Context.create());

        JsonObject response = pollResponse(session, 42, Duration.ofSeconds(1));
        assertThat(response, is(expectedResponse));
    }

    @Test
    void correlatesOutOfOrderResponsesByRequestId() {
        McpServerConfig config = McpServerFeature.builder()
                .maxRequestsPerSession(2)
                .buildPrototype();
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, "{}", config);
        long firstId = session.jsonRpcId();
        long secondId = session.jsonRpcId();
        session.prepareResponse(firstId);
        session.prepareResponse(secondId);
        JsonObject firstResponse = JsonObject.builder().set("id", firstId).build();
        JsonObject secondResponse = JsonObject.builder().set("id", secondId).build();

        session.acceptResponse(secondResponse, Context.create());
        session.acceptResponse(firstResponse, Context.create());

        assertThat(pollResponse(session, firstId, Duration.ofSeconds(1)), sameInstance(firstResponse));
        assertThat(pollResponse(session, secondId, Duration.ofSeconds(1)), sameInstance(secondResponse));
    }

    @Test
    void rejectsPendingResponseBeyondCapacityWithoutEviction() {
        McpServerConfig config = McpServerFeature.builder()
                .maxRequestsPerSession(2)
                .buildPrototype();
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, "{}", config);
        long firstId = session.jsonRpcId();
        long secondId = session.jsonRpcId();
        long thirdId = session.jsonRpcId();
        session.prepareResponse(firstId);
        session.prepareResponse(secondId);

        McpInternalException exception = assertThrows(McpInternalException.class,
                                                       () -> session.prepareResponse(thirdId));

        assertThat(exception.getMessage(), is("Maximum pending response count reached"));
        JsonObject firstResponse = JsonObject.builder().set("id", firstId).build();
        JsonObject secondResponse = JsonObject.builder().set("id", secondId).build();
        JsonObject thirdResponse = JsonObject.builder().set("id", thirdId).build();
        session.acceptResponse(secondResponse, Context.create());
        session.acceptResponse(firstResponse, Context.create());
        assertThat(pollResponse(session, firstId, Duration.ofSeconds(1)), sameInstance(firstResponse));

        session.prepareResponse(thirdId);
        session.acceptResponse(thirdResponse, Context.create());

        assertThat(pollResponse(session, secondId, Duration.ofSeconds(1)), sameInstance(secondResponse));
        assertThat(pollResponse(session, thirdId, Duration.ofSeconds(1)), sameInstance(thirdResponse));
    }

    @Test
    void disconnectUnblocksPendingResponse() throws InterruptedException {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, "{}");
        long requestId = session.jsonRpcId();
        session.prepareResponse(requestId);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread poller = Thread.ofVirtual().start(() -> {
            try {
                pollResponse(session, requestId, Duration.ofSeconds(5));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            while (poller.getState() != Thread.State.WAITING
                    && poller.getState() != Thread.State.TIMED_WAITING) {
                Thread.onSpinWait();
            }
        });

        session.onDisconnect(mock(ServerResponse.class));
        poller.join(1000);

        assertThat(poller.isAlive(), is(false));
        assertThat(failure.get(), instanceOf(McpInternalException.class));
        assertThat(failure.get().getMessage(), is("Session disconnected"));
    }

    @Test
    void ignoresResponseWithoutPendingRequest() {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, "{}");
        JsonObject unsolicited = JsonObject.builder().set("id", 0).build();
        session.acceptResponse(unsolicited, Context.create());
        long requestId = session.jsonRpcId();
        session.prepareResponse(requestId);

        JsonObject response = pollResponse(session, requestId, Duration.ZERO);

        assertThat(response.objectValue("error").isPresent(), is(true));
    }

    @Test
    void preservesRequestContextUntilResponseIsSent() {
        McpServerConfig config = McpServerConfig.create();
        McpTransportManager manager = mock(McpTransportManager.class);
        McpSession session = new McpSession(new McpSessions(config.maxSessionCount()),
                                            manager,
                                            config,
                                            "test-session");
        JsonRpcRequest request = mock(JsonRpcRequest.class);
        JsonRpcResponse response = mock(JsonRpcResponse.class);
        McpTransport transport = mock(McpStreamableHttpTransport.class);
        Context requestContext = Context.create();
        JsonValue requestId = JsonObject.builder().set("id", 1).build().value("id").orElseThrow();
        when(request.context()).thenReturn(requestContext);
        when(manager.create(request, response)).thenReturn(transport);
        session.createTransport(requestId, request, response);

        McpFeatures features = session.createFeatures(requestId, request, response);

        assertThat(features.requestContext(), sameInstance(requestContext));
        session.send(requestId, response);
        assertThat(session.findFeatures(requestId).isEmpty(), is(true));
    }

    @Test
    void sendsTaskResultThroughCapturedTransportAfterCacheEviction() {
        McpServerConfig config = McpServerFeature.builder()
                .maxRequestsPerSession(1)
                .buildPrototype();
        McpTransportManager manager = mock(McpTransportManager.class);
        McpSession session = new McpSession(new McpSessions(config.maxSessionCount()),
                                            manager,
                                            config,
                                            "test-session");
        JsonRpcRequest firstRequest = mock(JsonRpcRequest.class);
        JsonRpcRequest secondRequest = mock(JsonRpcRequest.class);
        JsonRpcResponse firstResponse = mock(JsonRpcResponse.class);
        JsonRpcResponse secondResponse = mock(JsonRpcResponse.class);
        McpTransport firstTransport = mock(McpStreamableHttpTransport.class);
        McpTransport secondTransport = mock(McpStreamableHttpTransport.class);
        JsonValue firstId = JsonObject.builder().set("id", 1).build().value("id").orElseThrow();
        JsonValue secondId = JsonObject.builder().set("id", 2).build().value("id").orElseThrow();
        when(manager.create(firstRequest, firstResponse)).thenReturn(firstTransport);
        when(manager.create(secondRequest, secondResponse)).thenReturn(secondTransport);
        session.createTransport(firstId, firstRequest, firstResponse);
        session.createTransport(secondId, secondRequest, secondResponse);

        session.send(firstId, firstResponse, firstTransport);

        verify(firstTransport).send(firstResponse);
    }

    @Test
    void createsFeaturesWithCapturedTransportAfterCacheEviction() {
        McpServerConfig config = McpServerFeature.builder()
                .maxRequestsPerSession(1)
                .buildPrototype();
        McpTransportManager manager = mock(McpTransportManager.class);
        McpSession session = new McpSession(new McpSessions(config.maxSessionCount()),
                                            manager,
                                            config,
                                            "test-session");
        JsonRpcRequest firstRequest = mock(JsonRpcRequest.class);
        JsonRpcRequest secondRequest = mock(JsonRpcRequest.class);
        JsonRpcResponse firstResponse = mock(JsonRpcResponse.class);
        JsonRpcResponse secondResponse = mock(JsonRpcResponse.class);
        McpTransport firstTransport = mock(McpStreamableHttpTransport.class);
        McpTransport secondTransport = mock(McpStreamableHttpTransport.class);
        Context firstContext = Context.create();
        JsonValue firstId = JsonObject.builder().set("id", 1).build().value("id").orElseThrow();
        JsonValue secondId = JsonObject.builder().set("id", 2).build().value("id").orElseThrow();
        when(manager.create(firstRequest, firstResponse)).thenReturn(firstTransport);
        when(manager.create(secondRequest, secondResponse)).thenReturn(secondTransport);
        session.createTransport(firstId, firstRequest, firstResponse);
        session.createTransport(secondId, secondRequest, secondResponse);

        McpFeatures features = session.createFeatures(firstId, firstTransport, firstContext);

        assertThat(features.requestContext(), sameInstance(firstContext));
        assertThat(session.findFeatures(firstId).orElseThrow(), sameInstance(features));
    }

    @Test
    void closedSessionRejectsTaskCreationAndClosesManagerOnce() {
        McpServerConfig config = McpServerConfig.create();
        McpTransportManager manager = mock(McpTransportManager.class);
        McpSession session = new McpSession(new McpSessions(config.maxSessionCount()),
                                            manager,
                                            config,
                                            "closed-session");
        session.close();
        session.close();

        assertThrows(McpInternalException.class,
                     () -> session.createTask(new McpTasks(), Context.create()));
        verify(manager).close();
    }

    @Test
    void createsTransportForSameRequestOnlyOnce() throws InterruptedException {
        McpServerConfig config = McpServerConfig.create();
        McpTransportManager manager = mock(McpTransportManager.class);
        McpSession session = new McpSession(new McpSessions(config.maxSessionCount()),
                                            manager,
                                            config,
                                            "test-session");
        JsonRpcRequest request = mock(JsonRpcRequest.class);
        JsonRpcResponse response = mock(JsonRpcResponse.class);
        McpTransport expected = mock(McpStreamableHttpTransport.class);
        JsonValue requestId = JsonObject.builder().set("id", 1).build().value("id").orElseThrow();
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        AtomicReference<McpTransport> firstResult = new AtomicReference<>();
        AtomicReference<McpTransport> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        when(manager.create(request, response)).thenAnswer(invocation -> {
            createEntered.countDown();
            if (!releaseCreate.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release transport creation");
            }
            return expected;
        });

        Thread first = Thread.ofVirtual().start(() -> {
            try {
                firstResult.set(session.createTransport(requestId, request, response));
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        assertThat(createEntered.await(5, TimeUnit.SECONDS), is(true));
        Thread second = Thread.ofVirtual().start(() -> {
            try {
                secondResult.set(session.createTransport(requestId, request, response));
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        awaitWaiting(second);

        releaseCreate.countDown();
        join(first);
        join(second);

        assertThat(failure.get(), nullValue());
        assertThat(firstResult.get(), sameInstance(expected));
        assertThat(secondResult.get(), sameInstance(expected));
        verify(manager).create(request, response);
    }

    @Test
    void closeWaitsForAdmittedTransportCreationAndRejectsNewOperations() throws InterruptedException {
        McpServerConfig config = McpServerConfig.create();
        McpTransportManager manager = mock(McpTransportManager.class);
        McpSession session = new McpSession(new McpSessions(config.maxSessionCount()),
                                            manager,
                                            config,
                                            "test-session");
        JsonRpcRequest request = mock(JsonRpcRequest.class);
        JsonRpcResponse response = mock(JsonRpcResponse.class);
        McpTransport expected = mock(McpStreamableHttpTransport.class);
        JsonValue requestId = JsonObject.builder().set("id", 1).build().value("id").orElseThrow();
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        AtomicReference<McpTransport> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        when(manager.create(request, response)).thenAnswer(invocation -> {
            createEntered.countDown();
            if (!releaseCreate.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release transport creation");
            }
            return expected;
        });

        Thread creator = Thread.ofVirtual().start(() -> {
            try {
                result.set(session.createTransport(requestId, request, response));
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        assertThat(createEntered.await(5, TimeUnit.SECONDS), is(true));
        Thread closer = Thread.ofVirtual().start(session::close);
        awaitWaiting(closer);

        assertThrows(McpInternalException.class,
                     () -> session.createTask(new McpTasks(), Context.create()));
        releaseCreate.countDown();
        join(creator);
        join(closer);

        assertThat(failure.get(), nullValue());
        assertThat(result.get(), sameInstance(expected));
        verify(manager).close();
    }

    @Test
    void defersReentrantCloseUntilTransportCreationFinishes() {
        McpServerConfig config = McpServerConfig.create();
        McpTransportManager manager = mock(McpTransportManager.class);
        McpSession session = new McpSession(new McpSessions(config.maxSessionCount()),
                                            manager,
                                            config,
                                            "test-session");
        JsonRpcRequest request = mock(JsonRpcRequest.class);
        JsonRpcResponse response = mock(JsonRpcResponse.class);
        McpTransport expected = mock(McpStreamableHttpTransport.class);
        JsonValue requestId = JsonObject.builder().set("id", 1).build().value("id").orElseThrow();
        when(manager.create(request, response)).thenAnswer(invocation -> {
            session.close();
            return expected;
        });

        McpTransport result = session.createTransport(requestId, request, response);

        assertThat(result, sameInstance(expected));
        assertThrows(McpInternalException.class,
                     () -> session.createTask(new McpTasks(), Context.create()));
        verify(manager).close();
    }

    @Test
    void taskResponseRequiresMatchingAuthorizationIdentity() {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, "{}");
        Context alice = requestContext("alice", true, true);
        McpTask task = new McpTasks().create(session, alice);
        JsonObject bobResponse = JsonObject.builder().set("id", 7).set("result", "bob").build();
        JsonObject changedAuthorizationResponse = JsonObject.builder()
                .set("id", 7)
                .set("result", "changed-authorization-alice")
                .build();
        JsonObject aliceResponse = JsonObject.builder().set("id", 7).set("result", "alice").build();
        session.prepareResponse(7, task.transport());

        session.acceptResponse(bobResponse, requestContext("bob", true, true));
        session.acceptResponse(changedAuthorizationResponse, requestContext("alice", false, false));
        session.acceptResponse(aliceResponse, requestContext("alice", true, true));

        assertThat(pollResponse(session, 7, Duration.ofSeconds(1)), is(changedAuthorizationResponse));
    }

    @Test
    void correlatesOwnedAndUnownedResponsesIndependently() {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, "{}");
        Context alice = requestContext("alice", true, true);
        McpTask task = new McpTasks().create(session, alice);
        JsonObject ordinaryResponse = JsonObject.builder().set("id", 1).set("result", "ordinary").build();
        JsonObject poisonedTaskResponse = JsonObject.builder().set("id", 2).set("result", "bob").build();
        JsonObject taskResponse = JsonObject.builder().set("id", 2).set("result", "alice").build();
        session.prepareResponse(1);
        session.prepareResponse(2, task.transport());

        session.acceptResponse(poisonedTaskResponse, requestContext("bob", true, true));
        session.acceptResponse(ordinaryResponse, requestContext("bob", true, true));
        session.acceptResponse(taskResponse, alice);

        assertThat(pollResponse(session, 2, Duration.ofSeconds(1)), is(taskResponse));
        assertThat(pollResponse(session, 1, Duration.ofSeconds(1)), is(ordinaryResponse));
    }

    @Test
    void bindsSessionToAuthorizationIdentity() {
        McpSession session = session(McpProtocolVersion.VERSION_2025_11_25, "{}");
        session.bindAuthorization(requestContext("alice", true, true));

        assertThat(session.authorized(requestContext("alice", true, true)), is(true));
        assertThat(session.authorized(requestContext("bob", true, true)), is(false));
        assertThat(session.authorized(requestContext("alice", false, true)), is(true));
        assertThat(session.authorized(requestContext("alice", true, false)), is(true));
    }

    @SuppressWarnings("unchecked")
    private static Context requestContext(String userName, boolean authenticated, boolean authorized) {
        Context context = Context.create();
        SecurityContext<Principal> security = mock(SecurityContext.class);
        when(security.isAuthenticated()).thenReturn(authenticated);
        when(security.isAuthorized()).thenReturn(authorized);
        when(security.userPrincipal()).thenReturn(Optional.of(() -> userName));
        when(security.servicePrincipal()).thenReturn(Optional.empty());
        context.register(security);
        return context;
    }

    private static JsonObject pollResponse(McpSession session, long requestId, Duration timeout) {
        try {
            return session.pollResponse(requestId, timeout);
        } finally {
            session.discardResponse(requestId);
        }
    }

    private static McpSession session(McpProtocolVersion protocolVersion, String capabilities) {
        McpServerConfig config = McpServerConfig.create();
        return session(protocolVersion, capabilities, config);
    }

    private static void awaitWaiting(Thread thread) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (thread.getState() != Thread.State.WAITING
                    && thread.getState() != Thread.State.TIMED_WAITING) {
                if (!thread.isAlive()) {
                    throw new AssertionError("Thread terminated before waiting");
                }
                Thread.onSpinWait();
            }
        });
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(thread.isAlive(), is(false));
    }

    private static McpSession session(McpProtocolVersion protocolVersion,
                                      String capabilities,
                                      McpServerConfig config) {
        McpSessions sessions = new McpSessions(config.maxSessionCount());
        McpSession session = new McpSession(sessions,
                                            mock(McpTransportManager.class),
                                            config,
                                            "test-session");
        session.protocolVersion(protocolVersion);
        JsonObject object = JsonParser.create(capabilities).readJsonObject();
        session.initializeClientCapabilities(new McpParameters(JsonRpcParams.create(object)));
        return session;
    }
}
