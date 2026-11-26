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
package org.apache.fineract.portfolio.savings.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Savings transaction commands are matched without regard to case, so existing callers keep working. */
class SavingsAccountTransactionsApiResourceCommandTest {

    private static final String[] KNOWN = { "deposit", "withdrawal", "holdAmount", "replayInterestPosting", "reverse", "undo", "modify",
            "releaseAmount" };

    @ParameterizedTest
    @CsvSource({ "REPLAYINTERESTPOSTING,replayInterestPosting", "replayinterestposting,replayInterestPosting", "REVERSE,reverse",
            "Reverse,reverse", "UNDO,undo", "HOLDAMOUNT,holdAmount", "' deposit ',deposit", "releaseamount,releaseAmount",
            "withdrawal,withdrawal" })
    void knownCommandIsMatchedIgnoringCaseAndSurroundingSpace(final String sent, final String expected) {
        assertThat(SavingsAccountTransactionsApiResource.canonicalCommand(sent, KNOWN)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = { "bogus,bogus", "NULL,''", "'',''" }, nullValues = "NULL")
    void unknownCommandIsPassedThroughSoItIsRejected(final String sent, final String expected) {
        assertThat(SavingsAccountTransactionsApiResource.canonicalCommand(sent, KNOWN)).isEqualTo(expected);
    }
}
