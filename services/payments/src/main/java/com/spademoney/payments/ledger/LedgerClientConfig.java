package com.spademoney.payments.ledger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the one RestClient Payments owns.
 *
 * <h2>Where the timeouts are, and why they are not here</h2>
 * The connect and read timeouts are set in {@code application.yml} under
 * {@code spring.http.clients.*}, which configures the request factory behind
 * Boot's auto-configured {@code RestClient.Builder}. They are not applied with
 * {@code builder.requestFactory(...)} in this class on purpose: doing so would
 * overwrite whatever the injected builder already carries, including the
 * request factory that {@code MockRestServiceServer} installs, and the tests
 * would silently start making real network calls.
 *
 * The values themselves are load-bearing, not defaults left alone. Spring's
 * out-of-the-box behaviour is effectively "wait forever", which would turn
 * Session 12's chaos demo — kill the Ledger mid-transfer — into a hung terminal
 * instead of a 504 anybody can see. {@code LedgerTimeoutsTest} fails the build
 * if they go missing.
 */
@Configuration(proxyBeanMethods = false)
class LedgerClientConfig {

    @Bean
    RestClient ledgerRestClient(RestClient.Builder builder, LedgerProperties properties) {
        return builder.baseUrl(properties.baseUrl()).build();
    }
}
