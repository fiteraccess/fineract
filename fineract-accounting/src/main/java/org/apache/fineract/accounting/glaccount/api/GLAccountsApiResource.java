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
package org.apache.fineract.accounting.glaccount.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.accounting.common.AccountingConstants;
import org.apache.fineract.accounting.common.AccountingDropdownReadPlatformService;
import org.apache.fineract.accounting.glaccount.command.GLAccountCommand;
import org.apache.fineract.accounting.glaccount.data.GLAccountBalanceData;
import org.apache.fineract.accounting.glaccount.data.GLAccountData;
import org.apache.fineract.accounting.glaccount.data.GLAccountDetailsData;
import org.apache.fineract.accounting.glaccount.domain.GLAccountType;
import org.apache.fineract.accounting.glaccount.service.GLAccountBalanceReadPlatformService;
import org.apache.fineract.accounting.glaccount.service.GLAccountReadPlatformService;
import org.apache.fineract.accounting.journalentry.data.JournalEntryAssociationParametersData;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.bulkimport.data.GlobalEntityType;
import org.apache.fineract.infrastructure.bulkimport.service.BulkImportWorkbookPopulatorService;
import org.apache.fineract.infrastructure.bulkimport.service.BulkImportWorkbookService;
import org.apache.fineract.infrastructure.codes.data.CodeValueData;
import org.apache.fineract.infrastructure.codes.service.CodeValueReadPlatformService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.api.DateParam;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.DateFormat;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.data.UploadRequest;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.springframework.stereotype.Component;

@Path("/v1/glaccounts")
@Component
@Tag(name = "General Ledger Account", description = """
        Ledger accounts represent an Individual account within an Organizations Chart Of Accounts(COA) and are assigned a name and unique number by which they can be identified.
        All transactions relating to a company's assets, liabilities, owners' equity, revenue and expenses are recorded against these accounts
        """)
@RequiredArgsConstructor
public class GLAccountsApiResource {

    private static final String RESOURCE_NAME_FOR_PERMISSION = "GLACCOUNT";

    /**
     * The balance-by-code endpoint is served entirely from {@code acc_gl_journal_entry}, so it is gated on the
     * journal-entry read permission rather than the chart-of-accounts one. It reveals strictly less than
     * {@code GET journalentries?glAccountId=...}, which carries the same permission.
     */
    private static final String RESOURCE_NAME_FOR_PERMISSION_JOURNAL_ENTRY = "JOURNALENTRY";

    private static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd";
    private static final String DEFAULT_LOCALE = "en";

    private final PlatformSecurityContext context;
    private final GLAccountReadPlatformService glAccountReadPlatformService;
    private final GLAccountBalanceReadPlatformService glAccountBalanceReadPlatformService;
    private final DefaultToApiJsonSerializer<GLAccountData> apiJsonSerializerService;
    private final ApiRequestParameterHelper apiRequestParameterHelper;
    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private final AccountingDropdownReadPlatformService dropdownReadPlatformService;
    private final CodeValueReadPlatformService codeValueReadPlatformService;
    private final BulkImportWorkbookService bulkImportWorkbookService;
    private final BulkImportWorkbookPopulatorService bulkImportWorkbookPopulatorService;

    @GET
    @Path("template")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(tags = {
            "General Ledger Account" }, summary = "Retrieve GL Accounts Template", description = """
                    This is a convenience resource. It can be useful when building maintenance user interface screens for client applications. The template data returned consists of any or all of:

                    Field Defaults
                    Allowed Value Lists
                    Example Request:

                    glaccounts/template
                    glaccounts/template?type=1

                    type is optional and integer value from 1 to 5.

                    1.Assets
                    2.Liabilities
                    3.Equity
                    4.Income
                    5.Expenses
                    """)

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = GLAccountsApiResourceSwagger.GetGLAccountsTemplateResponse.class)))
    public GLAccountData retrieveNewAccountDetails(@QueryParam("type") @Parameter(description = "type") final Integer type) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSION);

        return handleTemplate(this.glAccountReadPlatformService.retrieveNewGLAccountDetails(type));
    }

    @GET
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(tags = {
            "General Ledger Account" }, summary = "List General Ledger Account", description = """
                    ARGUMENTS
                    type Integer optional manualEntriesAllowed boolean optional usage Integer optional disabled boolean optional parentId Long optional tagId Long optional
                    Example Requests:

                    glaccounts

                    glaccounts?type=1&manualEntriesAllowed=true&usage=1&disabled=false

                    glaccounts?fetchRunningBalance=true
                    """)

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(array = @ArraySchema(schema = @Schema(implementation = GLAccountsApiResourceSwagger.GetGLAccountsResponse.class))))
    public List<GLAccountData> retrieveAllAccounts(@QueryParam("type") @Parameter(description = "type") final Integer type,
            @QueryParam("searchParam") @Parameter(description = "searchParam") final String searchParam,
            @QueryParam("usage") @Parameter(description = "usage") final Integer usage,
            @QueryParam("manualEntriesAllowed") @Parameter(description = "manualEntriesAllowed") final Boolean manualEntriesAllowed,
            @QueryParam("disabled") @Parameter(description = "disabled") final Boolean disabled,
            @QueryParam("fetchRunningBalance") @Parameter(description = "fetchRunningBalance") final boolean runningBalance) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSION);
        JournalEntryAssociationParametersData associationParametersData = new JournalEntryAssociationParametersData(false, runningBalance);
        return this.glAccountReadPlatformService.retrieveAllGLAccounts(type, searchParam, usage, manualEntriesAllowed, disabled,
                associationParametersData);

    }

    @GET
    @Path("{glAccountId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(tags = { "General Ledger Account" }, summary = "Retrieve a General Ledger Account", description = """
            Example Requests:

            glaccounts/1

            glaccounts/1?template=true

            glaccounts/1?fields=name,glCode

            glaccounts/1?fetchRunningBalance=true
            """)
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = GLAccountsApiResourceSwagger.GetGLAccountsResponse.class)))
    public GLAccountData retreiveAccount(@PathParam("glAccountId") @Parameter(description = "glAccountId") final Long glAccountId,
            @Context final UriInfo uriInfo,
            @QueryParam("fetchRunningBalance") @Parameter(description = "fetchRunningBalance") final boolean runningBalance) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSION);

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        JournalEntryAssociationParametersData associationParametersData = new JournalEntryAssociationParametersData(false, runningBalance);
        final GLAccountData glAccountData = this.glAccountReadPlatformService.retrieveGLAccountById(glAccountId, associationParametersData);
        return settings.isTemplate() ? handleTemplate(glAccountData) : glAccountData;
    }

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(tags = { "General Ledger Account" }, summary = "Create a General Ledger Account", description = """
            Note: You may optionally create Hierarchical Chart of Accounts by using the "parentId" property of an Account
            Mandatory Fields:
            name, glCode, type, usage and manualEntriesAllowed
            """)
    @RequestBody(content = @Content(schema = @Schema(implementation = GLAccountsApiResourceSwagger.PostGLAccountsRequest.class)))
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = GLAccountsApiResourceSwagger.PostGLAccountsResponse.class)))
    public CommandProcessingResult createGLAccount(@Parameter(hidden = true) GLAccountCommand glAccountCommand) {
        final CommandWrapper commandRequest = new CommandWrapperBuilder().createGLAccount()
                .withJson(apiJsonSerializerService.serialize(glAccountCommand)).build();
        return this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
    }

    @PUT
    @Path("{glAccountId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(tags = { "General Ledger Account" }, summary = "Update a GL Account", description = "Updates a GL Account")
    @RequestBody(content = @Content(schema = @Schema(implementation = GLAccountsApiResourceSwagger.PutGLAccountsRequest.class)))
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = GLAccountsApiResourceSwagger.PutGLAccountsResponse.class)))
    public CommandProcessingResult updateGLAccount(@PathParam("glAccountId") @Parameter(description = "glAccountId") final Long glAccountId,
            @Parameter(hidden = true) GLAccountCommand accountCommand) {
        final CommandWrapper commandRequest = new CommandWrapperBuilder().updateGLAccount(glAccountId)
                .withJson(apiJsonSerializerService.serialize(accountCommand)).build();
        return this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
    }

    @DELETE
    @Path("{glAccountId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(tags = { "General Ledger Account" }, summary = "Delete a GL Account", description = "Deletes a GL Account")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = GLAccountsApiResourceSwagger.DeleteGLAccountsResponse.class)))
    public CommandProcessingResult deleteGLAccount(
            @PathParam("glAccountId") @Parameter(description = "glAccountId") final Long glAccountId) {
        final CommandWrapper commandRequest = new CommandWrapperBuilder().deleteGLAccount(glAccountId).build();
        return this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
    }

    @GET
    @Path("downloadtemplate")
    @Produces("application/vnd.ms-excel")
    public Response getGlAccountsTemplate(@QueryParam("dateFormat") final String dateFormat) {
        return bulkImportWorkbookPopulatorService.getTemplate(GlobalEntityType.CHART_OF_ACCOUNTS.toString(), null, null, dateFormat);
    }

    @POST
    @Path("uploadtemplate")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RequestBody(description = "Upload GL accounts template", content = {
            @Content(mediaType = MediaType.MULTIPART_FORM_DATA, schema = @Schema(implementation = UploadRequest.class)) })
    public Long postGlAccountsTemplate(@FormDataParam("file") InputStream uploadedInputStream,
            @FormDataParam("file") FormDataContentDisposition fileDetail, @FormDataParam("locale") final String locale,
            @FormDataParam("dateFormat") final String dateFormat) {
        return bulkImportWorkbookService.importWorkbook(GlobalEntityType.CHART_OF_ACCOUNTS.toString(), uploadedInputStream, fileDetail,
                locale, dateFormat);
    }

    @GET
    @Path("code/{glCode}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(tags = { "General Ledger Account" }, summary = "Retrieve a General Ledger Account by its GL code", description = """
            Resolves a GL account by its unique `glCode` and returns its configuration together with a balance computed
            live from raw journal entries (`acc_gl_journal_entry`).

            The balance is NOT read from the `organization_running_balance` column, which is maintained by the
            "Update Accounting Running Balances" batch job and is therefore up to a day stale. It is also returned at
            full `decimal(19,6)` precision, unlike the `fetchRunningBalance=true` flag on the sibling endpoints, whose
            field is a `Long`. Monetary fields are serialised as JSON **strings** so a consumer parsing JSON numbers as
            binary floating point cannot silently lose precision on a bank-wide control account.

            Sign convention is type-aware and matches Fineract's own running-balance rule: ASSET and EXPENSE accounts
            increase on DEBIT, LIABILITY, EQUITY and INCOME increase on CREDIT. `totalDebits` and `totalCredits` are the
            raw unsigned sums, so either convention can be reconciled.

            Reversed entries are excluded entirely, together with the reversal entry posted against them: a reversal
            means the original posting should not have happened, so neither leg contributes to the balance or the
            totals.

            `officeId` omitted means organisation-wide: all offices, exact match only, with no office-hierarchy roll-up.
            `currencyCode` omitted resolves the account's sole currency; if the account has been posted to in more than
            one currency the request fails with 409 rather than silently summing across currencies — retry with
            `currencyCode`. A HEADER account reports only entries posted directly to it; child accounts are not rolled
            up. Accounting closures (`acc_gl_closure`) are not consulted: this is a live ledger read, not a statutory
            statement.

            `dateFormat` defaults to `yyyy-MM-dd` and `locale` to `en`.

            Example Requests:

            glaccounts/code/10101

            glaccounts/code/10101?asOnDate=2026-07-28&currencyCode=NGN
            """)
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = GLAccountDetailsData.class)))
    public GLAccountDetailsData retrieveAccountByGlCode(
            @PathParam("glCode") @Parameter(description = "Unique GL code from the chart of accounts") final String glCode,
            @QueryParam("asOnDate") @Parameter(description = "Balance cut-off date; defaults to the current business date") final DateParam asOnDateParam,
            @QueryParam("officeId") @Parameter(description = "Restrict to one office; omit for organisation-wide") final Long officeId,
            @QueryParam("currencyCode") @Parameter(description = "Restrict to one currency; omit to sum all") final String currencyCode,
            @QueryParam("dateFormat") @Parameter(description = "defaults to yyyy-MM-dd") final String dateFormat,
            @QueryParam("locale") @Parameter(description = "defaults to en") final String locale) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSION);

        final LocalDate asOnDate = parseQueryDate(asOnDateParam, "asOnDate", dateFormat, locale);
        return this.glAccountBalanceReadPlatformService.retrieveGLAccountDetailsByCode(glCode, asOnDate, officeId, currencyCode);
    }

    @GET
    @Path("code/{glCode}/balance")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(tags = {
            "General Ledger Account" }, summary = "Retrieve General Ledger Account movements and balance for a period", description = """
                    Returns the opening balance, the debit/credit movement totals, and the closing balance for the
                    inclusive window [`fromDate`, `toDate`], computed from raw journal entries in a single query.

                    Both `fromDate` and `toDate` are required. This endpoint has no concept of granularity: a caller
                    wanting a daily, month-to-date or year-to-date view resolves that window itself before calling —
                    Fineract owns no fiscal-calendar policy, so anchoring "month" or "year" here would be a guess.

                    Sign convention, reversal exclusion, office scope, currency scope and the JSON-string encoding of
                    monetary fields are all identical to `GET glaccounts/code/{glCode}`.

                    Requires the `READ_JOURNALENTRY` permission, since the entire response is derived from journal
                    entries and reveals strictly less than `GET journalentries?glAccountId=...`.

                    Example Request:

                    glaccounts/code/10101/balance?fromDate=2026-07-01&toDate=2026-07-31
                    """)
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = GLAccountBalanceData.class)))
    public GLAccountBalanceData retrieveAccountBalanceByGlCode(
            @PathParam("glCode") @Parameter(description = "Unique GL code from the chart of accounts") final String glCode,
            @QueryParam("fromDate") @Parameter(description = "Inclusive window start; required") final DateParam fromDateParam,
            @QueryParam("toDate") @Parameter(description = "Inclusive window end; required") final DateParam toDateParam,
            @QueryParam("officeId") @Parameter(description = "Restrict to one office; omit for organisation-wide") final Long officeId,
            @QueryParam("currencyCode") @Parameter(description = "Restrict to one currency; omit to resolve the account's sole currency") final String currencyCode,
            @QueryParam("dateFormat") @Parameter(description = "defaults to yyyy-MM-dd") final String dateFormat,
            @QueryParam("locale") @Parameter(description = "defaults to en") final String locale) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSION_JOURNAL_ENTRY);

        final LocalDate fromDate = parseQueryDate(fromDateParam, DateParam.FROM_DATE_PARAM, dateFormat, locale);
        final LocalDate toDate = parseQueryDate(toDateParam, DateParam.TO_DATE_PARAM, dateFormat, locale);
        return this.glAccountBalanceReadPlatformService.retrieveGLAccountBalanceByCode(glCode, fromDate, toDate, officeId, currencyCode);
    }

    /**
     * {@link DateParam#getDate} requires both a dateFormat and a locale, so a caller sending a bare ISO date would
     * otherwise get a validation error. Defaulting them here keeps plain {@code ?fromDate=2026-07-01} working while an
     * explicit {@code dateFormat}/{@code locale} pair still behaves exactly as it does on the journal-entries
     * endpoints.
     */
    private static LocalDate parseQueryDate(final DateParam dateParam, final String parameterName, final String dateFormat,
            final String locale) {
        if (dateParam == null) {
            return null;
        }
        final DateFormat format = new DateFormat(StringUtils.isBlank(dateFormat) ? DEFAULT_DATE_FORMAT : dateFormat);
        return dateParam.getDate(parameterName, format, StringUtils.isBlank(locale) ? DEFAULT_LOCALE : locale);
    }

    private GLAccountData handleTemplate(final GLAccountData glAccountData) {
        final List<EnumOptionData> accountTypeOptions = this.dropdownReadPlatformService.retrieveGLAccountTypeOptions();
        final List<EnumOptionData> usageOptions = this.dropdownReadPlatformService.retrieveGLAccountUsageOptions();
        final List<GLAccountData> assetHeaderAccountOptions = defaultIfEmpty(
                this.glAccountReadPlatformService.retrieveAllEnabledHeaderGLAccounts(GLAccountType.ASSET));
        final List<GLAccountData> liabilityHeaderAccountOptions = defaultIfEmpty(
                this.glAccountReadPlatformService.retrieveAllEnabledHeaderGLAccounts(GLAccountType.LIABILITY));
        final List<GLAccountData> equityHeaderAccountOptions = defaultIfEmpty(
                this.glAccountReadPlatformService.retrieveAllEnabledHeaderGLAccounts(GLAccountType.EQUITY));
        final List<GLAccountData> incomeHeaderAccountOptions = defaultIfEmpty(
                this.glAccountReadPlatformService.retrieveAllEnabledHeaderGLAccounts(GLAccountType.INCOME));
        final List<GLAccountData> expenseHeaderAccountOptions = defaultIfEmpty(
                this.glAccountReadPlatformService.retrieveAllEnabledHeaderGLAccounts(GLAccountType.EXPENSE));
        final Collection<CodeValueData> allowedAssetsTagOptions = this.codeValueReadPlatformService
                .retrieveCodeValuesByCode(AccountingConstants.ASSESTS_TAG_OPTION_CODE_NAME);
        final Collection<CodeValueData> allowedLiabilitiesTagOptions = this.codeValueReadPlatformService
                .retrieveCodeValuesByCode(AccountingConstants.LIABILITIES_TAG_OPTION_CODE_NAME);
        final Collection<CodeValueData> allowedEquityTagOptions = this.codeValueReadPlatformService
                .retrieveCodeValuesByCode(AccountingConstants.EQUITY_TAG_OPTION_CODE_NAME);
        final Collection<CodeValueData> allowedIncomeTagOptions = this.codeValueReadPlatformService
                .retrieveCodeValuesByCode(AccountingConstants.INCOME_TAG_OPTION_CODE_NAME);
        final Collection<CodeValueData> allowedExpensesTagOptions = this.codeValueReadPlatformService
                .retrieveCodeValuesByCode(AccountingConstants.EXPENSES_TAG_OPTION_CODE_NAME);

        return new GLAccountData().setId(glAccountData.getId()).setName(glAccountData.getName()).setParentId(glAccountData.getParentId())
                .setGlCode(glAccountData.getGlCode()).setDisabled(glAccountData.getDisabled())
                .setManualEntriesAllowed(glAccountData.getManualEntriesAllowed()).setType(glAccountData.getType())
                .setUsage(glAccountData.getUsage()).setDescription(glAccountData.getDescription())
                .setNameDecorated(glAccountData.getNameDecorated()).setTagId(glAccountData.getTagId())
                .setOrganizationRunningBalance(glAccountData.getOrganizationRunningBalance()).setAccountTypeOptions(accountTypeOptions)
                .setUsageOptions(usageOptions).setAssetHeaderAccountOptions(assetHeaderAccountOptions)
                .setLiabilityHeaderAccountOptions(liabilityHeaderAccountOptions).setEquityHeaderAccountOptions(equityHeaderAccountOptions)
                .setIncomeHeaderAccountOptions(incomeHeaderAccountOptions).setExpenseHeaderAccountOptions(expenseHeaderAccountOptions)
                .setAllowedAssetsTagOptions(allowedAssetsTagOptions).setAllowedLiabilitiesTagOptions(allowedLiabilitiesTagOptions)
                .setAllowedEquityTagOptions(allowedEquityTagOptions).setAllowedIncomeTagOptions(allowedIncomeTagOptions)
                .setAllowedExpensesTagOptions(allowedExpensesTagOptions);
    }

    private List<GLAccountData> defaultIfEmpty(final List<GLAccountData> list) {
        return list != null && !list.isEmpty() ? list : null;
    }
}
