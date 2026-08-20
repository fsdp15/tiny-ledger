package com.teya.tinyledger.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class LedgerApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void money_paid_in_and_taken_out_shows_up_in_the_balance() throws Exception {
        UUID account = openAccount("EUR");

        deposit(account, "100.00", "salary")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sequence").value(1))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.balanceAfter").value(100.00));

        withdraw(account, "30.50", "groceries")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value(69.50));

        mvc.perform(get("/api/v1/accounts/{id}/balance", account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.balance").value(69.50));
    }

    @Test
    void a_withdrawal_the_balance_cannot_cover_is_refused() throws Exception {
        UUID account = openAccount("EUR");
        deposit(account, "10.00", null);

        withdraw(account, "10.01", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void a_fraction_smaller_than_the_currency_allows_is_refused_rather_than_rounded() throws Exception {
        UUID euros = openAccount("EUR");
        deposit(euros, "10.005", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT_SCALE"));

        // The krona has no minor unit at all, so even two decimals are too many.
        UUID kronur = openAccount("ISK");
        deposit(kronur, "10.50", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT_SCALE"));
    }

    @Test
    void a_negative_amount_is_rejected_before_it_reaches_the_ledger() throws Exception {
        UUID account = openAccount("EUR");

        deposit(account, "-1.00", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void a_movement_without_an_idempotency_key_is_refused() throws Exception {
        UUID account = openAccount("EUR");

        mvc.perform(post("/api/v1/accounts/{id}/deposits", account)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("100.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void an_account_opened_with_no_body_at_all_uses_the_default_currency() throws Exception {
        String response = created(post("/api/v1/accounts"));

        assertThat(field(response, "currency")).isEqualTo("EUR");
    }

    @Test
    void a_transaction_can_be_fetched_by_id_but_only_through_its_own_account() throws Exception {
        UUID mine = openAccount("EUR");
        UUID theirs = openAccount("EUR");

        String response = created(depositRequest(mine, "100.00", "salary").header("Idempotency-Key", "k1"));
        String transactionId = field(response, "transactionId");

        mvc.perform(get("/api/v1/accounts/{a}/transactions/{t}", mine, transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("salary"))
                .andExpect(jsonPath("$.amount").value(100.00));

        mvc.perform(get("/api/v1/accounts/{a}/transactions/{t}", theirs, transactionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void an_unknown_account_is_not_found() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}/balance", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void a_deposit_retried_with_the_same_key_is_only_applied_once() throws Exception {
        UUID account = openAccount("EUR");

        String first = created(depositRequest(account, "100.00", "rent").header("Idempotency-Key", "abc"));
        String retry = created(depositRequest(account, "100.00", "rent").header("Idempotency-Key", "abc"));

        assertThat(field(retry, "transactionId")).isEqualTo(field(first, "transactionId"));

        mvc.perform(get("/api/v1/accounts/{id}/balance", account))
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void the_same_key_sent_with_a_different_amount_is_a_conflict() throws Exception {
        UUID account = openAccount("EUR");

        mvc.perform(depositRequest(account, "100.00", "rent").header("Idempotency-Key", "abc"))
                .andExpect(status().isCreated());

        mvc.perform(depositRequest(account, "250.00", "rent").header("Idempotency-Key", "abc"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void the_history_comes_back_newest_first_and_pages() throws Exception {
        UUID account = openAccount("EUR");
        for (int i = 1; i <= 3; i++) {
            deposit(account, "1.00", "deposit " + i);
        }

        mvc.perform(get("/api/v1/accounts/{id}/transactions", account).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(2))
                .andExpect(jsonPath("$.transactions[0].reference").value("deposit 3"))
                .andExpect(jsonPath("$.transactions[1].reference").value("deposit 2"))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").value(2));

        mvc.perform(get("/api/v1/accounts/{id}/transactions", account).param("cursor", "2"))
                .andExpect(jsonPath("$.transactions.length()").value(1))
                .andExpect(jsonPath("$.transactions[0].reference").value("deposit 1"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void the_history_can_be_narrowed_to_one_kind_of_movement() throws Exception {
        UUID account = openAccount("EUR");
        deposit(account, "100.00", "salary");
        withdraw(account, "10.00", "groceries");

        mvc.perform(get("/api/v1/accounts/{id}/transactions", account).param("type", "WITHDRAWAL"))
                .andExpect(jsonPath("$.transactions.length()").value(1))
                .andExpect(jsonPath("$.transactions[0].reference").value("groceries"));
    }

    @Test
    void one_account_never_sees_another_accounts_ledger() throws Exception {
        UUID mine = openAccount("EUR");
        UUID theirs = openAccount("EUR");
        deposit(theirs, "500.00", "not mine");

        mvc.perform(get("/api/v1/accounts/{id}/balance", mine))
                .andExpect(jsonPath("$.balance").value(0.00));
        mvc.perform(get("/api/v1/accounts/{id}/transactions", mine))
                .andExpect(jsonPath("$.transactions.length()").value(0));
    }

    private UUID openAccount(String currency) throws Exception {
        String body = created(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"%s\"}".formatted(currency)));
        return UUID.fromString(field(body, "accountId"));
    }

    private ResultActions deposit(UUID account, String amount, String reference) throws Exception {
        return mvc.perform(depositRequest(account, amount, reference)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private ResultActions withdraw(UUID account, String amount, String reference) throws Exception {
        return mvc.perform(post("/api/v1/accounts/{id}/withdrawals", account)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body(amount, reference)));
    }

    private MockHttpServletRequestBuilder depositRequest(UUID account, String amount, String reference) {
        return post("/api/v1/accounts/{id}/deposits", account)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(amount, reference));
    }

    private String created(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static String body(String amount, String reference) {
        return reference == null
                ? "{\"amount\":%s}".formatted(amount)
                : "{\"amount\":%s,\"reference\":\"%s\"}".formatted(amount, reference);
    }

    private String field(String body, String name) throws Exception {
        return json.readTree(body).get(name).asText();
    }
}
