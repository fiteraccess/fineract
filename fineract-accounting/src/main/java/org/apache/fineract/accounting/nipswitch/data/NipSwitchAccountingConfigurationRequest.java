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
package org.apache.fineract.accounting.nipswitch.data;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;

public record NipSwitchAccountingConfigurationRequest(
        @Schema(description = "Enabled detail liability GL credited for NIP principal.", example = "101") Long switchPayableGlAccountId,
        @Schema(description = "Enabled detail GL credited for the supplied Switch Fee leg.", example = "102") Long switchFeeGlAccountId,
        @Schema(description = "Enabled detail income GL credited for the supplied Bank Commission leg.", example = "103") Long commissionIncomeGlAccountId,
        @Schema(description = "Whether this switch configuration may be used for new NIP withdrawals.", example = "true") Boolean active)
        implements
            Serializable {
}
