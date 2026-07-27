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
package org.apache.fineract.integrationtests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.AccountHelper;
import org.apache.fineract.integrationtests.common.accounting.GLAccountBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings({ "rawtypes", "deprecation" })
class NipSwitchAccountingConfigurationIntegrationTest {

    private static final String BASE_URL = "/fineract-provider/api/v1/nip-switch-accounting-configurations";

    private RequestSpecification requestSpec;
    private ResponseSpecification okResponse;
    private ResponseSpecification validationErrorResponse;
    private ResponseSpecification notFoundResponse;
    private AccountHelper accountHelper;

    @BeforeEach
    void setUp() {
        Utils.initializeRESTAssured();
        requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        okResponse = new ResponseSpecBuilder().expectStatusCode(200).build();
        validationErrorResponse = new ResponseSpecBuilder().expectStatusCode(400).build();
        notFoundResponse = new ResponseSpecBuilder().expectStatusCode(404).build();
        accountHelper = new AccountHelper(requestSpec, okResponse);
    }

    @Test
    void createsReplacesNormalizesRetrievesAndListsConfiguration() {
        String switchId = "SWITCH_" + System.nanoTime();
        Account payable = accountHelper.createLiabilityAccount();
        Account fee = accountHelper.createExpenseAccount();
        Account income = accountHelper.createIncomeAccount();

        put(" " + switchId.toLowerCase() + " ", configuration(payable, fee, income, true), okResponse);

        Map configuration = get(switchId.toLowerCase(), okResponse);
        assertThat(configuration.get("switchId")).isEqualTo(switchId);
        assertAccountId(configuration, "switchPayableGlAccountId", payable);
        assertAccountId(configuration, "switchFeeGlAccountId", fee);
        assertAccountId(configuration, "commissionIncomeGlAccountId", income);
        assertThat(configuration.get("direction")).isEqualTo("OUTBOUND");
        assertThat(configuration.get("switchReceivableGlAccountId")).isNull();
        assertThat(configuration.get("active")).isEqualTo(true);

        Account replacementPayable = accountHelper.createLiabilityAccount();
        put(switchId, configuration(replacementPayable, fee, income, false), okResponse);

        Map replaced = get(" " + switchId.toLowerCase() + " ", okResponse);
        assertAccountId(replaced, "switchPayableGlAccountId", replacementPayable);
        assertThat(replaced.get("active")).isEqualTo(false);

        List<Map<String, Object>> configurations = given().spec(requestSpec).expect().spec(okResponse).when().get(url(null)).jsonPath()
                .getList("$");
        assertThat(configurations).filteredOn(item -> switchId.equals(item.get("switchId"))).hasSize(1);
    }

    @Test
    void rejectsUnusableGlAtomicallyAndReturnsNotFoundForUnknownSwitch() {
        String switchId = "INVALID_" + System.nanoTime();
        Account fee = accountHelper.createExpenseAccount();
        Account income = accountHelper.createIncomeAccount();
        String headerJson = new GLAccountBuilder().withAccountTypeAsLiability().withAccountUsageAsHeader().build();
        Integer headerAccountId = Utils.performServerPost(requestSpec, okResponse,
                "/fineract-provider/api/v1/glaccounts?" + Utils.TENANT_IDENTIFIER, headerJson, "resourceId");

        Map<String, Object> request = new HashMap<>();
        request.put("switchPayableGlAccountId", headerAccountId);
        request.put("switchFeeGlAccountId", fee.getAccountID());
        request.put("commissionIncomeGlAccountId", income.getAccountID());
        request.put("direction", "OUTBOUND");
        request.put("active", true);

        put(switchId, request, validationErrorResponse);
        get(switchId, notFoundResponse);
        get("UNKNOWN_" + System.nanoTime(), notFoundResponse);
    }

    private Map<String, Object> configuration(Account payable, Account fee, Account income, boolean active) {
        Map<String, Object> request = new HashMap<>();
        request.put("switchPayableGlAccountId", payable.getAccountID());
        request.put("switchFeeGlAccountId", fee.getAccountID());
        request.put("commissionIncomeGlAccountId", income.getAccountID());
        request.put("direction", "OUTBOUND");
        request.put("active", active);
        return request;
    }

    private void put(String switchId, Map<String, Object> request, ResponseSpecification responseSpecification) {
        Utils.performServerPut(requestSpec, responseSpecification, url(switchId), new Gson().toJson(request));
    }

    private Map get(String switchId, ResponseSpecification responseSpecification) {
        return given().spec(requestSpec).expect().spec(responseSpecification).when().get(url(switchId)).jsonPath().getMap("$");
    }

    private String url(String switchId) {
        return BASE_URL + (switchId == null ? "" : "/" + switchId) + "?" + Utils.TENANT_IDENTIFIER;
    }

    private void assertAccountId(Map configuration, String field, Account account) {
        assertThat(((Number) configuration.get(field)).longValue()).isEqualTo(account.getAccountID().longValue());
    }
}
