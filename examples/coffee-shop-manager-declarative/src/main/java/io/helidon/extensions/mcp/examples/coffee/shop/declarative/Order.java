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

package io.helidon.extensions.mcp.examples.coffee.shop.declarative;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import de.huxhorn.sulky.ulid.ULID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

@Entity
@Table(name = "ORDERS")
public class Order {

    @Id
    @Column(name = "ID")
    String id;

    @Column(name = "NAME")
    String name;

    @Column(name = "CONTENT")
    String content;

    @Column(name = "PRICE")
    BigDecimal price;

    @Transient
    List<MenuItem> items;

    public Order() {
        this.id = new ULID().nextULID();
    }

    public Order(String name, String content) {
        this.id = new ULID().nextULID();
        this.name = name;
        this.content = content;
        this.price = BigDecimal.ZERO;
        this.items = new ArrayList<>();
    }

    public Order(String name, String content, BigDecimal price, List<MenuItem> items) {
        this.id = new ULID().nextULID();
        this.name = name;
        this.price = price;
        this.items = items;
        this.content = content;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
