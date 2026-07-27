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
package org.apache.fineract.accounting.nipswitch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "m_nip_switch_accounting_configuration")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NipSwitchAccountingConfigurationEntity extends AbstractPersistableCustom<Long> {

    @Column(name = "switch_id", nullable = false, unique = true, length = 64)
    private String switchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private NipSwitchAccountingDirection direction;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "switch_payable_gl_account_id")
    private GLAccount switchPayableGlAccount;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "switch_fee_gl_account_id")
    private GLAccount switchFeeGlAccount;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "commission_income_gl_account_id")
    private GLAccount commissionIncomeGlAccount;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "switch_receivable_gl_account_id")
    private GLAccount switchReceivableGlAccount;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_on_utc", nullable = false, updatable = false)
    private LocalDateTime createdOnUtc;

    @Column(name = "last_modified_on_utc", nullable = false)
    private LocalDateTime lastModifiedOnUtc;

    public static NipSwitchAccountingConfigurationEntity create(String switchId, NipSwitchAccountingDirection direction,
            GLAccount switchPayableGlAccount, GLAccount switchFeeGlAccount, GLAccount commissionIncomeGlAccount,
            GLAccount switchReceivableGlAccount, boolean active) {
        NipSwitchAccountingConfigurationEntity entity = new NipSwitchAccountingConfigurationEntity();
        entity.switchId = switchId;
        entity.replace(direction, switchPayableGlAccount, switchFeeGlAccount, commissionIncomeGlAccount, switchReceivableGlAccount, active);
        return entity;
    }

    public void replace(NipSwitchAccountingDirection direction, GLAccount switchPayableGlAccount, GLAccount switchFeeGlAccount,
            GLAccount commissionIncomeGlAccount, GLAccount switchReceivableGlAccount, boolean active) {
        this.direction = direction;
        this.switchPayableGlAccount = switchPayableGlAccount;
        this.switchFeeGlAccount = switchFeeGlAccount;
        this.commissionIncomeGlAccount = commissionIncomeGlAccount;
        this.switchReceivableGlAccount = switchReceivableGlAccount;
        this.active = active;
    }

    public NipSwitchAccountingConfigurationProvider.OutboundConfiguration toOutboundConfiguration() {
        return new NipSwitchAccountingConfigurationProvider.OutboundConfiguration(switchId, switchPayableGlAccount.getId(),
                switchFeeGlAccount.getId(), commissionIncomeGlAccount.getId());
    }

    public NipSwitchAccountingConfigurationProvider.InboundConfiguration toInboundConfiguration() {
        return new NipSwitchAccountingConfigurationProvider.InboundConfiguration(switchId, switchReceivableGlAccount.getId());
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
