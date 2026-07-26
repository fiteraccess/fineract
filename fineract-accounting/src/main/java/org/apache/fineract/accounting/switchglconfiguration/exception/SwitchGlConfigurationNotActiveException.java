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
package org.apache.fineract.accounting.switchglconfiguration.exception;

import org.apache.fineract.accounting.switchglconfiguration.domain.SwitchDirection;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformDomainRuleException;

/**
 * A {@link RuntimeException} thrown when a transaction references a (switchCode, direction) pair that is configured but
 * disabled ({@code active = false}) - rejected the same way an unknown pair is, before any posting occurs.
 */
public class SwitchGlConfigurationNotActiveException extends AbstractPlatformDomainRuleException {

    private static final String ERROR_CODE = "error.msg.switchglconfiguration.not.active";

    public SwitchGlConfigurationNotActiveException(final String switchCode, final SwitchDirection direction) {
        super(ERROR_CODE, "Switch GL configuration for switch '" + switchCode + "' and direction '" + direction.name() + "' is not active",
                switchCode, direction.name());
    }
}
