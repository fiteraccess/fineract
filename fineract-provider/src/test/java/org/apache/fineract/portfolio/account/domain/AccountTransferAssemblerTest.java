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

import org.junit.jupiter.api.Test;

/**
 * AB-487: transferDescription is optional. When absent or blank it must fall back to a human-readable default built
 * from the two account numbers; when supplied it must be passed through untouched.
 */
public class AccountTransferAssemblerTest {

    @Test
    public void defaultsWhenDescriptionIsNull() {
        assertThat(AccountTransferAssembler.defaultTransferDescription(null, "7010000068", "7010000374"))
                .isEqualTo("Bank transfer from account 7010000068 to 7010000374");
    }

    @Test
    public void defaultsWhenDescriptionIsBlank() {
        assertThat(AccountTransferAssembler.defaultTransferDescription("   ", "7010000068", "7010000374"))
                .isEqualTo("Bank transfer from account 7010000068 to 7010000374");
    }

    @Test
    public void passesThroughWhenDescriptionIsSupplied() {
        assertThat(AccountTransferAssembler.defaultTransferDescription("Rent", "7010000068", "7010000374")).isEqualTo("Rent");
    }
}
