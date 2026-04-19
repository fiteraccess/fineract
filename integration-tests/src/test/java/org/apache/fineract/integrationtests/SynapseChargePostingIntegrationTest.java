package org.apache.fineract.integrationtests;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.integrationtests.common.BusinessDateHelper;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.GlobalConfigurationHelper;
import org.apache.fineract.integrationtests.common.SchedulerJobHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.AccountHelper;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public class SynapseChargePostingIntegrationTest {

    private static final String DATE = "01 January 2023";
    private static final String BATCH_URL = "/api/v1/proxy/savings/interest-postings:batch";

    @RegisterExtension
    static WireMockExtension synapse = WireMockExtension.newInstance()
            .options(wireMockConfig().port(18089))
            .build();

    private RequestSpecification requestSpec;
    private ResponseSpecification responseSpec;
    private GlobalConfigurationHelper globalConfigHelper;

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        globalConfigHelper = new GlobalConfigurationHelper();

        tenantJdbc().update("DELETE FROM synapse_outbox WHERE status IN ('PENDING','FAILED')");

        synapse.stubFor(WireMock.post(WireMock.urlEqualTo(BATCH_URL))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"batchId\":\"stub\",\"accepted\":999,\"failed\":0,\"results\":[]}")));
    }

    @AfterEach
    public void teardown() {
        globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                new PutGlobalConfigurationsRequest().enabled(false));
    }

    @Nested
    class WhenSynapseEnabled {

        @Test
        void routesChargeToOutboxAndBalanceUnchanged() {
            Integer savingsId = createSavingsWithChargeInBusinessDateContext(true);

            payDueCharges();

            String payload = latestOutboxPayload(savingsId);
            assertTrue(payload.contains("\"savingsAccountId\":" + savingsId));
            assertTrue(payload.contains("\"transactionType\":\"SAVINGS_CHARGE\""));
            assertTrue(payload.contains("\"direction\":\"DEBIT\""));
            assertTrue(payload.contains("\"savingsAccountChargeId\""));

            assertEquals(1000.0f, balanceOf(savingsId), 0.01f,
                    "Balance should remain 1000 — charge routed to outbox, not applied directly");

            new SchedulerJobHelper(requestSpec).executeAndAwaitJob("Dispatch Synapse Outbox");
            synapse.verify(postRequestedFor(urlEqualTo(BATCH_URL)));
        }

        @Test
        void outboxEntryContainsBatchAndTraceIds() {
            Account[] gl = createCashBasedGlAccounts();
            Integer savingsId = createActiveSavingsWithDepositAndCharge(gl, true);

            payDueCharges();

            Map<String, Object> row = tenantJdbc().queryForMap(
                    "SELECT batch_id, trace_id FROM synapse_outbox "
                            + "WHERE task_type = 'CHARGE_POSTING' AND account_id = ? ORDER BY id DESC LIMIT 1",
                    savingsId.longValue());

            assertNotNull(row.get("batch_id"), "Outbox entry should have a batch_id");
            assertNotNull(row.get("trace_id"), "Outbox entry should have a trace_id");
        }

        @Test
        void outboxPayloadContainsChargeMetadata() {
            Account[] gl = createCashBasedGlAccounts();
            Integer savingsId = createActiveSavingsWithDepositAndCharge(gl, true);

            payDueCharges();

            String payload = latestOutboxPayload(savingsId);
            assertTrue(payload.contains("\"amount\""));
            assertTrue(payload.contains("\"operation\":\"POST\""));
            assertTrue(payload.contains("\"savingsAccountChargeId\""));
        }
    }

    @Nested
    class WhenSynapseDisabled {

        @Test
        void appliesChargeDirectlyAndNoOutboxEntry() {
            Integer savingsId = createSavingsWithChargeInBusinessDateContext(false);

            payDueCharges();

            List<Map<String, Object>> rows = tenantJdbc().queryForList(
                    "SELECT id FROM synapse_outbox WHERE task_type = 'CHARGE_POSTING' AND account_id = ?",
                    savingsId.longValue());
            assertTrue(rows.isEmpty(), "No CHARGE_POSTING outbox entry expected when Synapse is disabled");

            assertEquals(900.0f, balanceOf(savingsId), 0.01f,
                    "Balance should be 900 (1000 - 100 charge) when applied directly");
        }
    }

    // -- helpers ----------------------------------------------------------------

    private Integer createSavingsWithChargeInBusinessDateContext(boolean enableSynapse) {
        try {
            globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                    new PutGlobalConfigurationsRequest().enabled(enableSynapse));
            globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(true));

            BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE,
                    LocalDate.of(2023, 1, 2));

            Account[] gl = createCashBasedGlAccounts();
            return createActiveSavingsWithDepositAndCharge(gl, false);
        } catch (RuntimeException e) {
            globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(false));
            throw e;
        }
    }

    private Integer createActiveSavingsWithDepositAndCharge(Account[] gl, boolean needBusinessDate) {
        if (needBusinessDate) {
            globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                    new PutGlobalConfigurationsRequest().enabled(true));
            globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(true));
            BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE,
                    LocalDate.of(2023, 1, 2));
        }

        Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, DATE);
        Integer productId = SavingsProductHelper.createSavingsProduct(
                new SavingsProductHelper().withInterestCompoundingPeriodTypeAsDaily()
                        .withInterestPostingPeriodTypeAsDaily()
                        .withInterestCalculationPeriodTypeAsDailyBalance()
                        .withAccountingRuleAsCashBased(gl).build(),
                requestSpec, responseSpec);
        SavingsAccountHelper sh = new SavingsAccountHelper(requestSpec, responseSpec);
        Integer savingsId = sh.applyForSavingsApplicationOnDate(clientId, productId, "INDIVIDUAL", DATE);
        sh.approveSavingsOnDate(savingsId, DATE);
        sh.activateSavingsAccount(savingsId, DATE);
        sh.depositToSavingsAccount(savingsId, "1000", DATE, CommonConstants.RESPONSE_RESOURCE_ID);

        Integer chargeId = ChargesHelper.createCharges(requestSpec, responseSpec,
                ChargesHelper.getSavingsSpecifiedDueDateJSON());
        sh.addChargesForSavingsWithDueDate(savingsId, chargeId, DATE, 100);
        synapse.resetRequests();
        return savingsId;
    }

    private void payDueCharges() {
        new SchedulerJobHelper(requestSpec).executeAndAwaitJob("Pay Due Savings Charges");
    }

    private String latestOutboxPayload(Integer savingsId) {
        List<Map<String, Object>> rows = tenantJdbc().queryForList(
                "SELECT payload FROM synapse_outbox WHERE task_type = 'CHARGE_POSTING' AND account_id = ? ORDER BY id DESC LIMIT 1",
                savingsId.longValue());
        assertTrue(!rows.isEmpty(), "Expected at least one CHARGE_POSTING outbox entry");
        return rows.get(0).get("payload").toString();
    }

    @SuppressWarnings("unchecked")
    private float balanceOf(Integer savingsId) {
        return (Float) ((HashMap) new SavingsAccountHelper(requestSpec, responseSpec)
                .getSavingsSummary(savingsId)).get("accountBalance");
    }

    private Account[] createCashBasedGlAccounts() {
        AccountHelper ah = new AccountHelper(requestSpec, responseSpec);
        return new Account[] { ah.createAssetAccount(), ah.createLiabilityAccount(),
                ah.createIncomeAccount(), ah.createExpenseAccount() };
    }

    private JdbcTemplate tenantJdbc() {
        String host = System.getenv().getOrDefault("FINERACT_DEFAULT_TENANTDB_HOSTNAME", "localhost");
        String port = System.getenv().getOrDefault("FINERACT_DEFAULT_TENANTDB_PORT", "5432");
        String dbName = System.getenv().getOrDefault("FINERACT_DEFAULT_TENANTDB_NAME", "fineract_default");
        String url = System.getenv().getOrDefault("FINERACT_DEFAULT_TENANTDB_URL",
                "jdbc:postgresql://" + host + ":" + port + "/" + dbName);
        String user = System.getenv().getOrDefault("FINERACT_DEFAULT_TENANTDB_UID", "postgres");
        String pwd = System.getenv().getOrDefault("FINERACT_DEFAULT_TENANTDB_PWD", "postgres");

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(pwd);
        return new JdbcTemplate(ds);
    }
}
