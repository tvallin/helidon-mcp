/*
 * Copyright (c) 2025 Oracle and/or its affiliates.
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

package io.helidon.extensions.mcp.examples.coffee.shop;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.helidon.common.mapper.OptionalValue;
import io.helidon.extensions.mcp.server.McpParameters;
import io.helidon.extensions.mcp.server.McpRequest;
import io.helidon.extensions.mcp.server.McpTool;
import io.helidon.extensions.mcp.server.McpToolContent;
import io.helidon.extensions.mcp.server.McpToolContents;
import io.helidon.service.registry.Services;
import io.helidon.transaction.Tx;
import io.helidon.transaction.TxException;

class TakeOrderTool implements McpTool {
    private final OrderRepository orderRepository = Services.get(OrderRepository.class);
    private final MenuItemRepository itemRepository = Services.get(MenuItemRepository.class);

    @Override
    public String name() {
        return "take-order-manager";
    }

    @Override
    public String description() {
        return "Take an order";
    }

    @Override
    public String schema() {
        return """
                {
                    "type": "object",
                    "description": "Description of a new order",
                    "properties": {
                        "name": {
                            "type": "string",
                            "description": "Name of the person who make an order"
                        },
                        "content": {
                            "description": "The order list of menu items name",
                            "type": "array",
                            "items": {
                                "type": "string"
                            },
                            "minItems": 1
                        }
                    },
                    "required": [ "name", "content" ]
                }
                """;
    }

    @Override
    public Function<McpRequest, List<McpToolContent>> tool() {
        return request -> {
            try {
                AtomicReference<BigDecimal> totalPrice = new AtomicReference<>(new BigDecimal(0));
                String name = request.parameters().get("name").asString().orElse("Name is missing");
                List<String> names = request.parameters()
                        .get("content")
                        .asList()
                        .get()
                        .stream()
                        .map(McpParameters::asString)
                        .map(OptionalValue::get)
                        .toList();
                List<MenuItem> items = itemRepository.listOrderById()
                        .stream()
                        .filter(item -> names.contains(item.getName()))
                        .peek(order -> totalPrice.getAndUpdate(it -> it.add(order.getPrice())))
                        .toList();

                Tx.transaction(() -> {
                    Order order = new Order(name, String.join(", ", names), totalPrice.get(), items);
                    return orderRepository.insert(order);
                });
            } catch (TxException e) {
                return List.of(McpToolContents.textContent("There was an issue when taking your order."));
            }
            return List.of(McpToolContents.textContent("The order was taken successfully."));
        };
    }
}
