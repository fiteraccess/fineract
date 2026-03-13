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
package org.apache.fineract.portfolio.account.jobs.executestandinginstructions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
import org.apache.fineract.portfolio.account.data.StandingInstructionData;
import org.apache.fineract.portfolio.account.data.StandingInstructionDuesData;
import org.apache.fineract.portfolio.account.domain.AccountTransferRecurrenceType;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.account.domain.StandingInstructionStatus;
import org.apache.fineract.portfolio.account.domain.StandingInstructionType;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.account.service.StandingInstructionReadPlatformService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class ExecuteStandingInstructionsTaskletTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 12);

    @Mock
    private StandingInstructionReadPlatformService standingInstructionReadPlatformService;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DatabaseSpecificSQLGenerator sqlGenerator;
    @Mock
    private AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    @Mock
    private StepContribution contribution;
    @Mock
    private ChunkContext chunkContext;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Africa/Kampala", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(
                new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE, BusinessDateType.COB_DATE, BUSINESS_DATE)));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void executeShouldBatchProcessingAndContinueAfterIndividualFailures() {
        CountingTransactionManager transactionManager = new CountingTransactionManager();
        ExecuteStandingInstructionsTasklet tasklet = new ExecuteStandingInstructionsTasklet(standingInstructionReadPlatformService,
                jdbcTemplate, sqlGenerator, accountTransfersWritePlatformService, transactionManager, 2);

        when(standingInstructionReadPlatformService.retrieveAll(StandingInstructionStatus.ACTIVE.getValue()))
                .thenReturn(List.of(dueInstruction(1L, 101L), dueInstruction(2L, 102L), dueInstruction(3L, 103L)));
        when(standingInstructionReadPlatformService.retriveLoanDuesData(101L))
                .thenReturn(new StandingInstructionDuesData(BUSINESS_DATE.minusDays(1), BigDecimal.ONE));
        when(standingInstructionReadPlatformService.retriveLoanDuesData(102L))
                .thenReturn(new StandingInstructionDuesData(BUSINESS_DATE.minusDays(1), BigDecimal.ONE));
        when(standingInstructionReadPlatformService.retriveLoanDuesData(103L))
                .thenReturn(new StandingInstructionDuesData(BUSINESS_DATE.minusDays(1), BigDecimal.ONE));
        when(sqlGenerator.escape("status")).thenReturn("status");
        doThrow(new RuntimeException("boom")).doReturn(1L, 1L).when(accountTransfersWritePlatformService).transferFunds(any());

        assertThatThrownBy(() -> tasklet.execute(contribution, chunkContext)).isInstanceOf(JobExecutionException.class);

        verify(accountTransfersWritePlatformService, times(3)).transferFunds(any());
        verify(jdbcTemplate, times(3)).update(startsWith("INSERT INTO m_account_transfer_standing_instructions_history"));
        verify(jdbcTemplate).update(eq("UPDATE m_account_transfer_standing_instructions SET last_run_date = ? where id = ?"),
                eq(BUSINESS_DATE), eq(2L));
        verify(jdbcTemplate).update(eq("UPDATE m_account_transfer_standing_instructions SET last_run_date = ? where id = ?"),
                eq(BUSINESS_DATE), eq(3L));
        assertThat(transactionManager.getStartedTransactions()).isEqualTo(2);
    }

    private StandingInstructionData dueInstruction(Long instructionId, Long toLoanId) {
        return StandingInstructionData.instance(instructionId, 10L, "SI-" + instructionId, null, null, null, null,
                enumOption(PortfolioAccountType.SAVINGS.getValue()), PortfolioAccountData.lookup(1000L + instructionId, "FROM"),
                enumOption(PortfolioAccountType.LOAN.getValue()), PortfolioAccountData.lookup(toLoanId, "TO"),
                enumOption(AccountTransferType.LOAN_REPAYMENT.getValue()), null, enumOption(StandingInstructionType.FIXED.getValue()),
                enumOption(StandingInstructionStatus.ACTIVE.getValue()), BigDecimal.TEN, BUSINESS_DATE.minusDays(10), null,
                enumOption(AccountTransferRecurrenceType.AS_PER_DUES.getValue()), null, 1, null);
    }

    private EnumOptionData enumOption(Integer value) {
        return new EnumOptionData(value.longValue(), "code-" + value, "value-" + value);
    }

    private static final class CountingTransactionManager implements PlatformTransactionManager {

        private int startedTransactions;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            startedTransactions++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {}

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {}

        int getStartedTransactions() {
            return startedTransactions;
        }
    }
}
