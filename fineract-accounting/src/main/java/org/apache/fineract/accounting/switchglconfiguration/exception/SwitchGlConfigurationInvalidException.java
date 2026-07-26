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

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformDomainRuleException;

/**
 * A {@link RuntimeException} thrown when switch-fee/bank-commission GL accounts are supplied for an INBOUND
 * configuration (inbound NIP credits carry no fee, so there is nothing to configure), or omitted for an OUTBOUND one
 * (both are required there).
 */
public class SwitchGlConfigurationInvalidException extends AbstractPlatformDomainRuleException {

    private static final String ERROR_CODE = "error.msg.switchglconfiguration.invalid";

    public static SwitchGlConfigurationInvalidException feeCommissionNotAllowedForInbound() {
        return new SwitchGlConfigurationInvalidException(
                "switchFeeGlAccountId and bankCommissionGlAccountId must not be provided when direction is INBOUND");
    }

    public static SwitchGlConfigurationInvalidException feeCommissionRequiredForOutbound() {
        return new SwitchGlConfigurationInvalidException(
                "switchFeeGlAccountId and bankCommissionGlAccountId are required when direction is OUTBOUND");
    }

    private SwitchGlConfigurationInvalidException(final String defaultUserMessage) {
        super(ERROR_CODE, defaultUserMessage);
    }
}
