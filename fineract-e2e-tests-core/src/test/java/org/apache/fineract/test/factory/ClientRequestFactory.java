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
package org.apache.fineract.test.factory;

import java.time.LocalDate;
import java.util.UUID;
import org.apache.fineract.client.models.PostClientsRequest;
import org.apache.fineract.test.helper.Utils;
import org.springframework.stereotype.Component;

@Component
public class ClientRequestFactory {

    private static final Long HEAD_OFFICE_ID = 1L;
    private static final Long LEGAL_FORM_ID_PERSON = 1L;
    // Use ISO date format because the generated client serializes LocalDate fields (e.g. dateOfBirth) as ISO
    // (yyyy-MM-dd).
    // dateFormat must match what gets serialized, otherwise backend validation fails.
    public static final String DATE_FORMAT = "yyyy-MM-dd";
    public static final String DEFAULT_LOCALE = "en";
    public static final Long GENDER_ID_MALE = 20L;

    public PostClientsRequest defaultClientCreationRequest() {
        return new PostClientsRequest()//
                .officeId(HEAD_OFFICE_ID)//
                .legalFormId(LEGAL_FORM_ID_PERSON)//
                .firstname(Utils.randomFirstNameGenerator())//
                .lastname(Utils.randomLastNameGenerator())//
                .externalId(randomClientId("ID_", 7))//
                .dateFormat(DATE_FORMAT)//
                .locale(DEFAULT_LOCALE)//
                .active(true)//
                .activationDate("2011-03-04")//
                .mobileNo(Utils.randomStringGenerator("M", 10))//
                .emailAddress(UUID.randomUUID().toString() + "@example.com")//
                .dateOfBirth(LocalDate.of(1990, 1, 1))//
                .genderId(GENDER_ID_MALE);//
    }

    private String randomClientId(final String prefix, final int lenOfRandomSuffix) {
        return Utils.randomStringGenerator(prefix, lenOfRandomSuffix, "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    }
}
