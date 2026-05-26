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
package org.apache.fineract.portfolio.savings.domain;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.cob.data.COBIdAndLastClosedBusinessDate;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.portfolio.savings.data.SavingsAccrualData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

//Use SavingsAccountRepositoryWrapper.
public interface SavingsAccountRepository extends JpaRepository<SavingsAccount, Long>, JpaSpecificationExecutor<SavingsAccount> {

    @Query("select s_acc from SavingsAccount s_acc where s_acc.client.id = :clientId")
    List<SavingsAccount> findSavingAccountByClientId(@Param("clientId") Long clientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sa from SavingsAccount sa where sa.id = :savingsId")
    SavingsAccount findOneLocked(@Param("savingsId") Long id);

    @Query("select s_acc from SavingsAccount s_acc where s_acc.gsim.id = :gsimId")
    List<SavingsAccount> findSavingAccountByGsimId(@Param("gsimId") Long gsimId);

    @Query("select s_acc from SavingsAccount s_acc where s_acc.status = :status")
    List<SavingsAccount> findSavingAccountByStatus(@Param("status") Integer status);

    @Query("select sa from SavingsAccount sa where sa.client.id = :clientId and sa.group.id = :groupId")
    List<SavingsAccount> findByClientIdAndGroupId(@Param("clientId") Long clientId, @Param("groupId") Long groupId);

    @Query("select case when (count (saving) > 0) then 'true' else 'false' end from SavingsAccount saving where saving.client.id = :clientId and saving.status in (100,200,300,303,304)")
    boolean doNonClosedSavingAccountsExistForClient(@Param("clientId") Long clientId);

    @Query("select sa from SavingsAccount sa where sa.client.id is null and sa.group.id = :groupId")
    List<SavingsAccount> findByGroupId(@Param("groupId") Long groupId);

    @Query("select sa from SavingsAccount sa where sa.id = :accountId and sa.depositType = :depositAccountTypeId")
    SavingsAccount findByIdAndDepositAccountType(@Param("accountId") Long accountId,
            @Param("depositAccountTypeId") Integer depositAccountTypeId);

    @Query("SELECT DISTINCT sa FROM SavingsAccount sa LEFT JOIN FETCH sa.charges LEFT JOIN FETCH sa.savingsOfficerHistory LEFT JOIN FETCH sa.product LEFT JOIN FETCH sa.group WHERE sa.id = :id")
    Optional<SavingsAccount> findByIdWithLightweightCollections(@Param("id") Long id);

    @Query("SELECT DISTINCT sa FROM SavingsAccount sa LEFT JOIN FETCH sa.charges LEFT JOIN FETCH sa.savingsOfficerHistory LEFT JOIN FETCH sa.product LEFT JOIN FETCH sa.group WHERE sa.id = :accountId and sa.depositType = :depositAccountTypeId")
    Optional<SavingsAccount> findByIdAndDepositAccountTypeWithLightweightCollections(@Param("accountId") Long accountId,
            @Param("depositAccountTypeId") Integer depositAccountTypeId);

    @Query("select sa from SavingsAccount sa where sa.accountNumber = :accountNumber and sa.status in (100, 200, 300, 303, 304) ")
    SavingsAccount findNonClosedAccountByAccountNumber(@Param("accountNumber") String accountNumber);

    @Query("select sa from SavingsAccount sa where sa.accountNumber = :accountNumber ")
    SavingsAccount findSavingsAccountByAccountNumber(@Param("accountNumber") String accountNumber);

    Page<SavingsAccount> findByStatus(Integer status, Pageable pageable);

    SavingsAccount findByExternalId(ExternalId externalId);

    @Query("SELECT sa.id FROM SavingsAccount sa WHERE sa.externalId = :externalId")
    Long findIdByExternalId(@Param("externalId") ExternalId externalId);

    @Query("""
            SELECT new org.apache.fineract.portfolio.savings.data.SavingsAccrualData(
                savings.id,
                savings.accountNumber,
                savings.accruedTillDate,
                CASE WHEN apm.financialAccountType = 18 THEN TRUE ELSE FALSE END,
                msp.allowOverdraft,
                savings.depositType
            )
            FROM SavingsAccount savings
            LEFT JOIN SavingsProduct msp ON msp = savings.product
            LEFT JOIN ProductToGLAccountMapping apm ON apm.productId = msp.id and (apm.financialAccountType = 18 or apm.financialAccountType IS NULL)
            WHERE savings.status = :status
              AND (savings.nominalAnnualInterestRate IS NOT NULL AND savings.nominalAnnualInterestRate > 0)
              AND msp.accountingRule = :accountingRule
              AND ( savings.closedOnDate <= :tillDate OR savings.closedOnDate IS NULL)
              AND ( savings.accruedTillDate <= :tillDate OR savings.accruedTillDate IS NULL )
            ORDER BY savings.id
            """)
    List<SavingsAccrualData> findAccrualData(@Param("tillDate") LocalDate tillDate, @Param("savingsId") Long savingsId,
            @Param("status") Integer status, @Param("accountingRule") Integer accountingRule);

    @Query("SELECT sa.id FROM SavingsAccount sa WHERE sa.status = :status")
    List<Long> findSavingsAccountIdsByStatusId(Integer status);

    // COB related queries
    @Query("""
            SELECT sa.id FROM SavingsAccount sa
            WHERE sa.id BETWEEN :minSavingsId AND :maxSavingsId
            AND sa.status IN :savingsStatuses
            AND (:cobBusinessDate = sa.lastClosedBusinessDate OR sa.lastClosedBusinessDate IS NULL)
            """)
    List<Long> findAllSavingsByLastClosedBusinessDateAndMinAndMaxSavingsIdAndStatuses(@Param("minSavingsId") Long minSavingsId,
            @Param("maxSavingsId") Long maxSavingsId, @Param("cobBusinessDate") LocalDate cobBusinessDate,
            @Param("savingsStatuses") Collection<Integer> savingsStatuses);

    @Query("""
            SELECT sa.id FROM SavingsAccount sa
            WHERE sa.id BETWEEN :minSavingsId AND :maxSavingsId
            AND sa.status IN :savingsStatuses
            AND sa.lastClosedBusinessDate = :cobBusinessDate
            """)
    List<Long> findAllSavingsByLastClosedBusinessDateNotNullAndMinAndMaxSavingsIdAndStatuses(@Param("minSavingsId") Long minSavingsId,
            @Param("maxSavingsId") Long maxSavingsId, @Param("cobBusinessDate") LocalDate cobBusinessDate,
            @Param("savingsStatuses") Collection<Integer> savingsStatuses);

    @Query("""
            SELECT sa.id, sa.lastClosedBusinessDate
            FROM SavingsAccount sa
            WHERE sa.id IN :savingsIds
            AND (sa.lastClosedBusinessDate < :businessDate OR sa.lastClosedBusinessDate IS NULL)
            """)
    List<COBIdAndLastClosedBusinessDate> findAllSavingsIdsBehindDateOrNull(@Param("businessDate") LocalDate businessDate,
            @Param("savingsIds") List<Long> savingsIds);

    @Query("""
            SELECT sa.id, sa.lastClosedBusinessDate
            FROM SavingsAccount sa
            WHERE sa.id IN :savingsIds
            AND sa.lastClosedBusinessDate < :businessDate
            """)
    List<COBIdAndLastClosedBusinessDate> findAllSavingsIdsBehindDate(@Param("businessDate") LocalDate businessDate,
            @Param("savingsIds") List<Long> savingsIds);

    @Query("""
            SELECT sa.id, sa.lastClosedBusinessDate
            FROM SavingsAccount sa
            WHERE sa.status IN (100, 200, 300, 303, 304)
            AND sa.lastClosedBusinessDate IS NOT NULL
            ORDER BY sa.lastClosedBusinessDate ASC
            """)
    List<COBIdAndLastClosedBusinessDate> findAllSavingsIdsOldestCobProcessed();

    /**
     * O(1) direct update of summary fields and sub_status, bypassing Hibernate cascade/orphan-removal checks on the
     * transactions collection. Uses optimistic locking via the version column. Returns 1 if the update succeeded, 0 if
     * the version has changed (concurrent modification).
     */
    @Modifying
    @Query("""
            UPDATE SavingsAccount sa SET
                sa.summary.totalDeposits = :totalDeposits,
                sa.summary.totalWithdrawals = :totalWithdrawals,
                sa.summary.totalInterestPosted = :totalInterestPosted,
                sa.summary.totalWithdrawalFees = :totalWithdrawalFees,
                sa.summary.totalFeeCharge = :totalFeeCharge,
                sa.summary.totalPenaltyCharge = :totalPenaltyCharge,
                sa.summary.totalAnnualFees = :totalAnnualFees,
                sa.summary.accountBalance = :accountBalance,
                sa.summary.totalOverdraftInterestDerived = :totalOverdraftInterestDerived,
                sa.summary.totalWithholdTax = :totalWithholdTax,
                sa.summary.totalInterestEarned = :totalInterestEarned,
                sa.summary.lastInterestCalculationDate = :lastInterestCalculationDate,
                sa.summary.interestPostedTillDate = :interestPostedTillDate,
                sa.sub_status = :subStatus,
                sa.version = sa.version + 1
            WHERE sa.id = :id AND sa.version = :version
            """)
    int updateSummaryDirect(@Param("id") Long id, @Param("totalDeposits") BigDecimal totalDeposits,
            @Param("totalWithdrawals") BigDecimal totalWithdrawals, @Param("totalInterestPosted") BigDecimal totalInterestPosted,
            @Param("totalWithdrawalFees") BigDecimal totalWithdrawalFees, @Param("totalFeeCharge") BigDecimal totalFeeCharge,
            @Param("totalPenaltyCharge") BigDecimal totalPenaltyCharge, @Param("totalAnnualFees") BigDecimal totalAnnualFees,
            @Param("accountBalance") BigDecimal accountBalance,
            @Param("totalOverdraftInterestDerived") BigDecimal totalOverdraftInterestDerived,
            @Param("totalWithholdTax") BigDecimal totalWithholdTax, @Param("totalInterestEarned") BigDecimal totalInterestEarned,
            @Param("lastInterestCalculationDate") LocalDate lastInterestCalculationDate,
            @Param("interestPostedTillDate") LocalDate interestPostedTillDate, @Param("subStatus") Integer subStatus,
            @Param("version") int version);

    /**
     * Narrow O(1) update for the optimized current-day deposit path. Increments {@code totalDeposits} and
     * {@code accountBalance} by the deposit amount, sets {@code sub_status}, and bumps {@code version}. Optimistic-
     * locked on {@code version}.
     * <p>
     * Safe to use only on paths that provably do not mutate any other summary field in the same transaction. The
     * optimized deposit branch in {@link org.apache.fineract.portfolio.savings.domain.SavingsAccountDomainServiceJpa}
     * satisfies that invariant — it bypasses {@code account.deposit(...)},
     * {@code summary.updateSummaryWithTransaction(...)}, interest accrual, and charge payment.
     * <p>
     * For paths that compute multiple changed fields (interest posting, account close, backdated transactions), use
     * {@link #updateSummaryDirect}.
     */
    @Modifying
    @Query("""
            UPDATE SavingsAccount sa SET
                sa.summary.totalDeposits  = COALESCE(sa.summary.totalDeposits, 0)  + :depositAmount,
                sa.summary.accountBalance = COALESCE(sa.summary.accountBalance, 0) + :depositAmount,
                sa.sub_status = :subStatus,
                sa.version = sa.version + 1
            WHERE sa.id = :id AND sa.version = :version
            """)
    int applyDepositDelta(@Param("id") Long id, @Param("depositAmount") BigDecimal depositAmount, @Param("subStatus") Integer subStatus,
            @Param("version") int version);

    /**
     * Narrow O(1) update for the optimized current-day withdrawal path. Increments {@code totalWithdrawals} by the
     * withdrawal amount, increments {@code totalWithdrawalFees} and {@code totalFeeCharge} by the fee amount, subtracts
     * {@code (withdrawalAmount + feeAmount)} from {@code accountBalance}, sets {@code sub_status}, and bumps
     * {@code version}. Optimistic-locked on {@code version}.
     * <p>
     * Same invariant as {@link #applyDepositDelta} — only safe on the optimized withdrawal branch which provably does
     * not mutate other summary fields. {@code feeAmount} may be {@link BigDecimal#ZERO} when no withdrawal fee applies.
     */
    @Modifying
    @Query("""
            UPDATE SavingsAccount sa SET
                sa.summary.totalWithdrawals    = COALESCE(sa.summary.totalWithdrawals, 0)    + :withdrawalAmount,
                sa.summary.totalWithdrawalFees = COALESCE(sa.summary.totalWithdrawalFees, 0) + :feeAmount,
                sa.summary.totalFeeCharge      = COALESCE(sa.summary.totalFeeCharge, 0)      + :feeAmount,
                sa.summary.accountBalance      = COALESCE(sa.summary.accountBalance, 0)      - (:withdrawalAmount + :feeAmount),
                sa.sub_status = :subStatus,
                sa.version = sa.version + 1
            WHERE sa.id = :id AND sa.version = :version
            """)
    int applyWithdrawalDelta(@Param("id") Long id, @Param("withdrawalAmount") BigDecimal withdrawalAmount,
            @Param("feeAmount") BigDecimal feeAmount, @Param("subStatus") Integer subStatus, @Param("version") int version);
}
