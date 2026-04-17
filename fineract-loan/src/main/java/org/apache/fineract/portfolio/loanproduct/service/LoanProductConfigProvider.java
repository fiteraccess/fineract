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
package org.apache.fineract.portfolio.loanproduct.service;

import org.apache.fineract.portfolio.loanproduct.data.CacheableLoanProductConfig;

/**
 * Provider interface for accessing cached loan product configuration. This interface lives in fineract-loan so that
 * classes in this module can access the cached product config without depending on fineract-provider.
 *
 * <p>
 * The implementation ({@code LoanProductConfigService} in fineract-provider) backs this with an EhCache/Redis-backed
 * {@code @Cacheable} method, avoiding repeated full LoanProduct entity loads.
 * </p>
 */
public interface LoanProductConfigProvider {

    /**
     * Get the cached loan product configuration for the given product ID.
     *
     * @param productId
     *            the loan product ID
     * @return the cached configuration DTO
     */
    CacheableLoanProductConfig getConfig(Long productId);
}
