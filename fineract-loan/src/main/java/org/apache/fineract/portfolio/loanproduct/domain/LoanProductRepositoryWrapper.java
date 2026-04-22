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
package org.apache.fineract.portfolio.loanproduct.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.loanproduct.exception.LoanProductNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wrapper for {@link LoanProductRepository} that adds NULL checking, error handling, and cache eviction.
 *
 * <p>
 * Note: {@link #findById(Long)} is intentionally NOT cached because JPA entities contain Hibernate proxies and lazy
 * collections that cannot be serialized to Redis (L2). Caching of loan product data for read paths happens in
 * {@code LoanProductReadPlatformServiceImpl} which returns serializable DTOs.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanProductRepositoryWrapper {

    private final LoanProductRepository repository;

    /**
     * Find a loan product by ID.
     *
     * @param loanProductId
     *            the loan product ID
     * @return the loan product entity
     * @throws LoanProductNotFoundException
     *             if not found
     */
    @Transactional(readOnly = true)
    public LoanProduct findById(final Long loanProductId) {
        return this.repository.findById(loanProductId).orElseThrow(() -> new LoanProductNotFoundException(loanProductId));
    }

}
