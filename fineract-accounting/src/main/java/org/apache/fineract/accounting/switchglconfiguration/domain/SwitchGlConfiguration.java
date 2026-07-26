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
package org.apache.fineract.accounting.switchglconfiguration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

@Entity
@Table(name = "acc_gl_switch_configuration", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "switch_code", "direction" }, name = "uq_switch_code_direction") })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class SwitchGlConfiguration extends AbstractPersistableCustom<Long> {

    @Column(name = "switch_code", nullable = false, length = 20)
    private String switchCode;

    @Column(name = "direction", nullable = false)
    private Integer direction;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "principal_gl_account_id", nullable = false)
    private GLAccount principalGlAccount;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "switch_fee_gl_account_id", nullable = true)
    private GLAccount switchFeeGlAccount;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "bank_commission_gl_account_id", nullable = true)
    private GLAccount bankCommissionGlAccount;

    @Column(name = "active", nullable = false)
    private boolean active;

    private SwitchGlConfiguration(final String switchCode, final SwitchDirection direction, final GLAccount principalGlAccount,
            final GLAccount switchFeeGlAccount, final GLAccount bankCommissionGlAccount, final boolean active) {
        this.switchCode = switchCode;
        this.direction = direction.getValue();
        this.principalGlAccount = principalGlAccount;
        this.switchFeeGlAccount = switchFeeGlAccount;
        this.bankCommissionGlAccount = bankCommissionGlAccount;
        this.active = active;
    }

    public static SwitchGlConfiguration createNew(final String switchCode, final SwitchDirection direction,
            final GLAccount principalGlAccount, final GLAccount switchFeeGlAccount, final GLAccount bankCommissionGlAccount,
            final boolean active) {
        return new SwitchGlConfiguration(switchCode, direction, principalGlAccount, switchFeeGlAccount, bankCommissionGlAccount, active);
    }

    public SwitchDirection getDirection() {
        return SwitchDirection.fromInt(this.direction);
    }

    public void updateSwitchCode(final String switchCode) {
        this.switchCode = switchCode;
    }

    public void updateDirection(final SwitchDirection direction) {
        this.direction = direction.getValue();
    }

    public void updatePrincipalGlAccount(final GLAccount principalGlAccount) {
        this.principalGlAccount = principalGlAccount;
    }

    public void updateSwitchFeeGlAccount(final GLAccount switchFeeGlAccount) {
        this.switchFeeGlAccount = switchFeeGlAccount;
    }

    public void updateBankCommissionGlAccount(final GLAccount bankCommissionGlAccount) {
        this.bankCommissionGlAccount = bankCommissionGlAccount;
    }

    public void updateActive(final boolean active) {
        this.active = active;
    }
}
