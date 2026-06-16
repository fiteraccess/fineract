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
package org.apache.fineract.integrationtests.client.feign.helpers;

import static org.apache.fineract.client.feign.util.FeignCalls.ok;

import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;
import org.apache.fineract.client.feign.FineractFeignClient;
import org.apache.fineract.client.models.GetClientsClientIdResponse;
import org.apache.fineract.client.models.GetCodeValuesDataResponse;
import org.apache.fineract.client.models.PostClientsRequest;
import org.apache.fineract.client.models.PostClientsResponse;
import org.apache.fineract.integrationtests.client.feign.modules.LoanTestData;
import org.apache.fineract.integrationtests.common.Utils;

public class FeignClientHelper {

    /**
     * System-defined "Gender" code id. Its code values are seeded dynamically, so their ids must be resolved at
     * runtime.
     */
    private static final Long GENDER_CODE_ID = 4L;
    private static final String GENDER_MALE = "Male";

    private final FineractFeignClient fineractClient;

    private Long maleGenderId;

    public FeignClientHelper(FineractFeignClient fineractClient) {
        this.fineractClient = fineractClient;
    }

    /**
     * Resolves the code value id of the "Male" gender by fetching the values of the Gender code
     * ({@value #GENDER_CODE_ID}) instead of relying on a hard-coded id, which is no longer stable since gender code
     * values are seeded dynamically.
     */
    public Long getMaleGenderId() {
        if (maleGenderId == null) {
            maleGenderId = ok(() -> fineractClient.codeValues().retrieveAllCodeValues(GENDER_CODE_ID)).stream()
                    .filter(codeValue -> GENDER_MALE.equalsIgnoreCase(codeValue.getName())).map(GetCodeValuesDataResponse::getId)
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "Code value '" + GENDER_MALE + "' not found for code with id " + GENDER_CODE_ID));
        }
        return maleGenderId;
    }

    public Long createClient() {
        return createClient(Utils.dateFormatter.format(Utils.getLocalDateOfTenant()));
    }

    public Long createClient(String activationDate) {
        String externalId = Utils.randomStringGenerator("EXT_", 7);

        PostClientsRequest request = new PostClientsRequest()//
                .officeId(1L)//
                .legalFormId(1L)//
                .firstname(Utils.randomFirstNameGenerator())//
                .lastname(Utils.randomLastNameGenerator())//
                .externalId(externalId)//
                .active(true)//
                .activationDate(activationDate)//
                .dateFormat(LoanTestData.DATETIME_PATTERN)//
                .locale(LoanTestData.LOCALE)//
                .mobileNo(Utils.randomStringGenerator("M", 10))//
                .emailAddress(UUID.randomUUID().toString() + "@example.com")//
                .dateOfBirth(LocalDate.of(1990, 1, 1))//
                .genderId(getMaleGenderId());

        return createClient(request);
    }

    public Long createClient(PostClientsRequest request) {
        PostClientsResponse response = ok(() -> fineractClient.clients().create6(request));
        return response.getClientId();
    }

    public GetClientsClientIdResponse getClient(Long clientId) {
        return ok(() -> fineractClient.clients().retrieveOne11(clientId, Collections.emptyMap()));
    }
}
