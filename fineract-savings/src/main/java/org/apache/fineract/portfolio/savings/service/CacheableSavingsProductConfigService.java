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
package org.apache.fineract.portfolio.savings.service;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.savings.data.CacheableSavingsProductConfig;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.domain.SavingsProductRepository;
import org.apache.fineract.portfolio.savings.exception.SavingsProductNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CacheableSavingsProductConfigService {

    private final SavingsProductRepository repository;

    @Cacheable(value = "savingsProductConfig", key = "#productId")
    @Transactional(readOnly = true)
    public CacheableSavingsProductConfig getSavingsProduct(Long productId) {
        SavingsProduct p = repository.findById(productId).orElseThrow(() -> new SavingsProductNotFoundException(productId));
        return new CacheableSavingsProductConfig(p.getId(), p.getName(), p.getShortName(), p.getAccountingType(), p.overdraftLimit(),
                p.isCashBasedAccountingEnabled(), p.isAccrualBasedAccountingEnabled());
    }
}
