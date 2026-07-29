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
package org.apache.fineract.accounting.glaccount.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformException;

/**
 * Thrown when a GL account has been posted to in more than one currency and the caller did not disambiguate with
 * {@code currencyCode}. Fineract does not model a currency on a GL account, so a mixed-currency account has no single
 * correct figure to report; summing across currencies would silently produce a meaningless number.
 *
 * <p>
 * Mapped to HTTP 409 by {@link GLAccountMultipleCurrenciesExceptionMapper} — this is a resolvable conflict in the
 * request (add {@code ?currencyCode=}), not a validation failure (400) or a permission failure (403).
 */
public class GLAccountMultipleCurrenciesException extends AbstractPlatformException {

    public GLAccountMultipleCurrenciesException(final String glCode) {
        super("error.msg.glaccount.balance.multiple.currencies",
                "GL account " + glCode
                        + " has been posted to in more than one currency; supply a `currencyCode` query parameter to disambiguate.",
                new Object[] { glCode });
    }
}
