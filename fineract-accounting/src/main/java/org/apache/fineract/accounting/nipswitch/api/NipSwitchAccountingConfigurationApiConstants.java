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
package org.apache.fineract.accounting.nipswitch.api;

import java.util.Set;

public final class NipSwitchAccountingConfigurationApiConstants {

    public static final String RESOURCE_NAME_FOR_PERMISSION = "NIP_SWITCH_ACCOUNTING_CONFIGURATION";
    public static final String SWITCH_PAYABLE_GL_ACCOUNT_ID = "switchPayableGlAccountId";
    public static final String SWITCH_FEE_GL_ACCOUNT_ID = "switchFeeGlAccountId";
    public static final String COMMISSION_INCOME_GL_ACCOUNT_ID = "commissionIncomeGlAccountId";
    public static final String SWITCH_RECEIVABLE_GL_ACCOUNT_ID = "switchReceivableGlAccountId";
    public static final String DIRECTION = "direction";
    public static final String ACTIVE = "active";
    public static final Set<String> UPSERT_PARAMETERS = Set.of(DIRECTION, SWITCH_PAYABLE_GL_ACCOUNT_ID, SWITCH_FEE_GL_ACCOUNT_ID,
            COMMISSION_INCOME_GL_ACCOUNT_ID, SWITCH_RECEIVABLE_GL_ACCOUNT_ID, ACTIVE);

    private NipSwitchAccountingConfigurationApiConstants() {}
}
