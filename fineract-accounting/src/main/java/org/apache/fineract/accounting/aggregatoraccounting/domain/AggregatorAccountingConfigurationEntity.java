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
package org.apache.fineract.accounting.aggregatoraccounting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

@Entity
@Table(name = "m_aggregator_accounting_configuration")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AggregatorAccountingConfigurationEntity extends AbstractPersistableCustom<Long> {

    @Column(name = "aggregator_code", nullable = false, unique = true, length = 64)
    private String aggregatorCode;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "aggregator_payable_gl_account_id")
    private GLAccount aggregatorPayableGlAccount;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "commission_income_gl_account_id")
    private GLAccount commissionIncomeGlAccount;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "convenience_fee_income_gl_account_id")
    private GLAccount convenienceFeeIncomeGlAccount;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_on_utc", nullable = false, updatable = false)
    private LocalDateTime createdOnUtc;

    @Column(name = "last_modified_on_utc", nullable = false)
    private LocalDateTime lastModifiedOnUtc;

    public static AggregatorAccountingConfigurationEntity create(String aggregatorCode, GLAccount aggregatorPayableGlAccount,
            GLAccount commissionIncomeGlAccount, GLAccount convenienceFeeIncomeGlAccount, boolean active) {
        AggregatorAccountingConfigurationEntity entity = new AggregatorAccountingConfigurationEntity();
        entity.aggregatorCode = aggregatorCode;
        entity.replace(aggregatorPayableGlAccount, commissionIncomeGlAccount, convenienceFeeIncomeGlAccount, active);
        return entity;
    }

    public void replace(GLAccount aggregatorPayableGlAccount, GLAccount commissionIncomeGlAccount, GLAccount convenienceFeeIncomeGlAccount,
            boolean active) {
        this.aggregatorPayableGlAccount = aggregatorPayableGlAccount;
        this.commissionIncomeGlAccount = commissionIncomeGlAccount;
        this.convenienceFeeIncomeGlAccount = convenienceFeeIncomeGlAccount;
        this.active = active;
    }

    public AggregatorAccountingConfigurationProvider.Configuration toConfiguration() {
        return new AggregatorAccountingConfigurationProvider.Configuration(aggregatorCode, aggregatorPayableGlAccount.getId(),
                commissionIncomeGlAccount.getId(), idOf(convenienceFeeIncomeGlAccount));
    }

    private static Long idOf(GLAccount glAccount) {
        return glAccount == null ? null : glAccount.getId();
    }

    @PrePersist
    void setCreationTimestamps() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        createdOnUtc = now;
        lastModifiedOnUtc = now;
    }

    @PreUpdate
    void setLastModifiedTimestamp() {
        lastModifiedOnUtc = LocalDateTime.now(ZoneOffset.UTC);
    }
}
