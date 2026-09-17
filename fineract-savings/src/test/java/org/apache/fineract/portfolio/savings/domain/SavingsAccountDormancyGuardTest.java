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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.fineract.portfolio.savings.exception.SavingsAccountDormantException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AB-550: DORMANT blocks every transaction type. The guard lives on the entity so the deposit, withdrawal and reversal
 * paths all share one definition of what dormant means.
 */
class SavingsAccountDormancyGuardTest {

    private SavingsAccount accountWithSubStatus(final SavingsAccountSubStatusEnum subStatus) {
        final SavingsAccount account = new SavingsAccount();
        ReflectionTestUtils.setField(account, "sub_status", subStatus.getValue());
        ReflectionTestUtils.setField(account, "id", 77L);
        return account;
    }

    @Test
    void dormantAccountIsRejected() {
        assertThatThrownBy(() -> accountWithSubStatus(SavingsAccountSubStatusEnum.DORMANT).validateForDormancy())
                .isInstanceOf(SavingsAccountDormantException.class);
    }

    @Test
    void activeAccountIsAllowed() {
        assertThatCode(() -> accountWithSubStatus(SavingsAccountSubStatusEnum.NONE).validateForDormancy()).doesNotThrowAnyException();
    }

    @Test
    void blockedAccountIsNotRejectedByTheDormancyGuard() {
        // BLOCK has its own guard; conflating the two would make a blocked account report the wrong error.
        assertThatCode(() -> accountWithSubStatus(SavingsAccountSubStatusEnum.BLOCK).validateForDormancy()).doesNotThrowAnyException();
    }
}
