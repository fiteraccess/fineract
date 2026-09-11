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
package org.apache.fineract.portfolio.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * AB-548: the marker that lets a reopening restore exactly the standing instructions its closure disabled.
 *
 * <p>
 * The distinction this pins down is the whole point. A DISABLED instruction carries no history, so without the marker a
 * reopening could not tell one that a closure switched off from one a customer or operator switched off deliberately —
 * and restoring the latter would restart money movement nobody asked to restart.
 */
class AccountTransferStandingInstructionClosureTest {

    @Nested
    class DisabledByAClosure {

        @Test
        void isMarkedSoAReopeningCanFindIt() {
            AccountTransferStandingInstruction instruction = active();

            instruction.disableForClosure();

            assertThat(instruction.isDisabled()).isTrue();
            assertThat(instruction.isDisabledByClosure()).isTrue();
        }

        @Test
        void isRestoredToActiveByAReopening() {
            AccountTransferStandingInstruction instruction = active();
            instruction.disableForClosure();

            instruction.restoreAfterReopening();

            assertThat(instruction.isActive()).isTrue();
        }

        @Test
        void losesItsMarkerOnceRestoredSoALaterManualDisableIsNotMistakenForAClosure() {
            AccountTransferStandingInstruction instruction = active();
            instruction.disableForClosure();
            instruction.restoreAfterReopening();

            instruction.updateStatus(StandingInstructionStatus.DISABLED.getValue());

            assertThat(instruction.isDisabledByClosure()).isFalse();
        }
    }

    @Nested
    class DisabledDeliberately {

        @Test
        void carriesNoMarker() {
            AccountTransferStandingInstruction instruction = active();

            instruction.updateStatus(StandingInstructionStatus.DISABLED.getValue());

            assertThat(instruction.isDisabled()).isTrue();
            assertThat(instruction.isDisabledByClosure()).isFalse();
        }

        @Test
        void aFreshInstructionIsNotMarked() {
            assertThat(active().isDisabledByClosure()).isFalse();
        }
    }

    private static AccountTransferStandingInstruction active() {
        AccountTransferStandingInstruction instruction = new AccountTransferStandingInstruction();
        instruction.updateStatus(StandingInstructionStatus.ACTIVE.getValue());
        return instruction;
    }
}
