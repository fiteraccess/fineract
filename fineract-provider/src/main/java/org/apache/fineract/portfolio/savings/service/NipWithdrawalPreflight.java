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
package org.apache.fineract.portfolio.savings.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccountRepositoryWrapper;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationProvider;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.savings.domain.ReferenceTransaction;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NipWithdrawalPreflight {

    private final NipSwitchAccountingConfigurationProvider switchConfigurationProvider;
    private final FinancialActivityAccountRepositoryWrapper financialActivityAccountRepositoryWrapper;

    void validate(String switchId, List<ReferenceTransaction> references) {
        this.switchConfigurationProvider.requireOutbound(switchId);
        if (containsEmtLevy(references)) {
            requireUsableMapping(FinancialActivity.EMT_LEVY, "error.msg.savings.nip.emt.levy.not.usable", "EMT_LEVY");
        }
        if (containsVat(references)) {
            requireUsableMapping(FinancialActivity.VAT_PAYABLE, "error.msg.savings.nip.vat.payable.not.usable", "VAT_PAYABLE");
        }
    }

    private void requireUsableMapping(FinancialActivity financialActivity, String errorCode, String activityName) {
        final GLAccount glAccount = this.financialActivityAccountRepositoryWrapper
                .findByFinancialActivityTypeWithNotFoundDetection(financialActivity.getValue()).getGlAccount();
        if (glAccount.isDisabled() || !glAccount.isDetailAccount()) {
            throw new GeneralPlatformDomainRuleException(errorCode, activityName + " must map to an enabled detail GL account");
        }
    }

    private static boolean containsEmtLevy(List<ReferenceTransaction> references) {
        for (ReferenceTransaction reference : references) {
            if (reference.type().isEmtLevy()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsVat(List<ReferenceTransaction> references) {
        for (ReferenceTransaction reference : references) {
            if (reference.type().isVat()) {
                return true;
            }
        }
        return false;
    }
}
