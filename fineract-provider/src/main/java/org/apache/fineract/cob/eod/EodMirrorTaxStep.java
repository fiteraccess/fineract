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
package org.apache.fineract.cob.eod;

import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class EodMirrorTaxStep extends EodMirrorStep {

    public static final String NAME = "EOD_MIRROR_TAX";

    public EodMirrorTaxStep(EodRunService runService, FineractProperties properties, ObjectProvider<SynapseTransactionClient> synapseClient,
            GLAccountRepository glAccounts) {
        super(runService, properties, synapseClient, glAccounts);
    }

    @Override
    protected String job() {
        return "TAX";
    }

    @Override
    protected String previousStep() {
        return EodMirrorIncomeStep.NAME;
    }

    @Override
    public String getEnumStyledName() {
        return NAME;
    }

    @Override
    public String getHumanReadableName() {
        return "EOD tax mirror: post the day's taxes to the bridge GLs and mirror them to Flexcube";
    }
}
