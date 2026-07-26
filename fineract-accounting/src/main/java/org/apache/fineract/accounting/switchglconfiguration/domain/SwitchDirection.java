/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.accounting.switchglconfiguration.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * AB-416: a switch (NIBSS/Hydrogen/Etranzact/...) can process both outbound and inbound transactions, each needing its
 * own GL configuration. Direction is never sent by the caller as a separate value - it is always derived from the
 * transaction command already being run (a deposit is inbound, a withdrawal is outbound) and is not persisted on the
 * transaction itself.
 */
public enum SwitchDirection {

    OUTBOUND(1, "switchDirection.outbound"), //
    INBOUND(2, "switchDirection.inbound"); //

    private final Integer value;
    private final String code;

    SwitchDirection(final Integer value, final String code) {
        this.value = value;
        this.code = code;
    }

    public Integer getValue() {
        return this.value;
    }

    public String getCode() {
        return this.code;
    }

    private static final Map<Integer, SwitchDirection> BY_VALUE = new HashMap<>();

    static {
        for (final SwitchDirection direction : SwitchDirection.values()) {
            BY_VALUE.put(direction.value, direction);
        }
    }

    public static SwitchDirection fromInt(final Integer value) {
        return value == null ? null : BY_VALUE.get(value);
    }

    public boolean isOutbound() {
        return this == OUTBOUND;
    }

    public boolean isInbound() {
        return this == INBOUND;
    }
}
