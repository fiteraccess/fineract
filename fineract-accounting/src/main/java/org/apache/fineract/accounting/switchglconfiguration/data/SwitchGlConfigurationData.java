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
package org.apache.fineract.accounting.switchglconfiguration.data;

import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.glaccount.data.GLAccountData;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;

@RequiredArgsConstructor
@Data
public class SwitchGlConfigurationData {

    private final Long id;
    private final String switchCode;
    private final EnumOptionData direction;
    private final GLAccountData principalGlAccountData;
    private final GLAccountData switchFeeGlAccountData;
    private final GLAccountData bankCommissionGlAccountData;
    private final boolean active;
    private Map<String, List<GLAccountData>> glAccountOptions;
    private List<EnumOptionData> directionOptions;

    public SwitchGlConfigurationData() {
        this.id = null;
        this.switchCode = null;
        this.direction = null;
        this.principalGlAccountData = null;
        this.switchFeeGlAccountData = null;
        this.bankCommissionGlAccountData = null;
        this.active = false;
        this.glAccountOptions = null;
        this.directionOptions = null;
    }
}
