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

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import io.helidon.common.context.Context;
import io.helidon.common.security.SecurityContext;

record McpTaskOwner(AuthorizationIdentity authorizationIdentity) {
    McpTaskOwner {
        Objects.requireNonNull(authorizationIdentity);
    }

    McpTaskOwner(Context requestContext) {
        this(authorizationIdentity(requestContext));
    }

    static AuthorizationIdentity authorizationIdentity(Context context) {
        var securityContext = context.get(SecurityContext.class);
        if (securityContext.isEmpty()) {
            return new AuthorizationIdentity(List.of());
        }
        SecurityContext<?> security = (SecurityContext<?>) securityContext.get();
        Stream<PrincipalIdentity> user = security.userPrincipal()
                .stream()
                .map(principal -> new PrincipalIdentity("user",
                                                        Objects.toString(principal.getName(), "")));
        Stream<PrincipalIdentity> service = security.servicePrincipal()
                .stream()
                .map(principal -> new PrincipalIdentity("service",
                                                        Objects.toString(principal.getName(), "")));
        return new AuthorizationIdentity(Stream.concat(user, service).toList());
    }

    record AuthorizationIdentity(List<PrincipalIdentity> principals) {
        AuthorizationIdentity {
            principals = List.copyOf(principals);
        }
    }

    record PrincipalIdentity(String role, String name) {
    }
}
