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
package org.apache.fineract.accounting.journalentry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.accounting.closure.domain.GLClosureRepository;
import org.apache.fineract.accounting.common.AccountingConstants.CashAccountsForLoan;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccountRepositoryWrapper;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMapping;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.portfolio.PortfolioProductType;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountingProcessorHelperBatchCacheTest {

    private static final Long LOAN_PRODUCT_ID = 11L;
    private static final Long PAYMENT_TYPE_ID = 7L;
    private static final Long CHARGE_OFF_REASON_ID = 42L;

    @Mock
    private JournalEntryRepository glJournalEntryRepository;
    @Mock
    private ProductToGLAccountMappingRepository accountMappingRepository;
    @Mock
    private FinancialActivityAccountRepositoryWrapper financialActivityAccountRepository;
    @Mock
    private GLClosureRepository closureRepository;
    @Mock
    private GLAccountRepository glAccountRepository;
    @Mock
    private OfficeRepository officeRepository;
    @Mock
    private AccountTransfersReadPlatformService accountTransfersReadPlatformService;
    @Mock
    private ChargeRepositoryWrapper chargeRepositoryWrapper;
    @Mock
    private BusinessEventNotifierService businessEventNotifierService;

    private AccountingProcessorHelper underTest;

    @BeforeEach
    void setUp() {
        underTest = new AccountingProcessorHelper(glJournalEntryRepository, accountMappingRepository, financialActivityAccountRepository,
                closureRepository, glAccountRepository, officeRepository, accountTransfersReadPlatformService, chargeRepositoryWrapper,
                businessEventNotifierService);
    }

    @Test
    void shouldCacheLoanFundSourceMappingsWithinBatch() {
        GLAccount coreAccount = new GLAccount();
        GLAccount paymentSpecificAccount = new GLAccount();
        ProductToGLAccountMapping coreMapping = new ProductToGLAccountMapping().setGlAccount(coreAccount);
        ProductToGLAccountMapping paymentSpecificMapping = new ProductToGLAccountMapping().setGlAccount(paymentSpecificAccount);
        int accountType = CashAccountsForLoan.FUND_SOURCE.getValue();

        when(accountMappingRepository.findCoreProductToFinAccountMapping(LOAN_PRODUCT_ID, PortfolioProductType.LOAN.getValue(),
                accountType)).thenReturn(coreMapping);
        when(accountMappingRepository.findByProductIdAndProductTypeAndFinancialAccountTypeAndPaymentTypeId(LOAN_PRODUCT_ID,
                PortfolioProductType.LOAN.getValue(), accountType, PAYMENT_TYPE_ID)).thenReturn(paymentSpecificMapping);

        try (AccountingProcessorHelper.JournalEntryProcessingBatch ignored = underTest.startJournalEntryProcessingBatch()) {
            assertThat(underTest.getLinkedGLAccountForLoanProduct(LOAN_PRODUCT_ID, accountType, PAYMENT_TYPE_ID))
                    .isSameAs(paymentSpecificAccount);
            assertThat(underTest.getLinkedGLAccountForLoanProduct(LOAN_PRODUCT_ID, accountType, PAYMENT_TYPE_ID))
                    .isSameAs(paymentSpecificAccount);
        }

        verify(accountMappingRepository, times(1)).findCoreProductToFinAccountMapping(LOAN_PRODUCT_ID, PortfolioProductType.LOAN.getValue(),
                accountType);
        verify(accountMappingRepository, times(1)).findByProductIdAndProductTypeAndFinancialAccountTypeAndPaymentTypeId(LOAN_PRODUCT_ID,
                PortfolioProductType.LOAN.getValue(), accountType, PAYMENT_TYPE_ID);

        underTest.getLinkedGLAccountForLoanProduct(LOAN_PRODUCT_ID, accountType, PAYMENT_TYPE_ID);

        verify(accountMappingRepository, times(2)).findCoreProductToFinAccountMapping(LOAN_PRODUCT_ID, PortfolioProductType.LOAN.getValue(),
                accountType);
        verify(accountMappingRepository, times(2)).findByProductIdAndProductTypeAndFinancialAccountTypeAndPaymentTypeId(LOAN_PRODUCT_ID,
                PortfolioProductType.LOAN.getValue(), accountType, PAYMENT_TYPE_ID);
    }

    @Test
    void shouldCacheMissingChargeOffReasonMappingsWithinBatch() {
        when(accountMappingRepository.findChargeOffReasonMapping(LOAN_PRODUCT_ID, PortfolioProductType.LOAN.getValue(),
                CHARGE_OFF_REASON_ID)).thenReturn(null);

        try (AccountingProcessorHelper.JournalEntryProcessingBatch ignored = underTest.startJournalEntryProcessingBatch()) {
            assertThat(underTest.getChargeOffMappingByCodeValue(LOAN_PRODUCT_ID, PortfolioProductType.LOAN, CHARGE_OFF_REASON_ID))
                    .isNull();
            assertThat(underTest.getChargeOffMappingByCodeValue(LOAN_PRODUCT_ID, PortfolioProductType.LOAN, CHARGE_OFF_REASON_ID))
                    .isNull();
        }

        verify(accountMappingRepository, times(1)).findChargeOffReasonMapping(LOAN_PRODUCT_ID, PortfolioProductType.LOAN.getValue(),
                CHARGE_OFF_REASON_ID);

        underTest.getChargeOffMappingByCodeValue(LOAN_PRODUCT_ID, PortfolioProductType.LOAN, CHARGE_OFF_REASON_ID);

        verify(accountMappingRepository, times(2)).findChargeOffReasonMapping(LOAN_PRODUCT_ID, PortfolioProductType.LOAN.getValue(),
                CHARGE_OFF_REASON_ID);
    }
}
