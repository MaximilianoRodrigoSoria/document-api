package ar.com.laboratory.integration.handler;

import ar.com.laboratory.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.test.web.reactive.server.WebTestClient;

class HandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired private WebTestClient webTestClient;

    @Test
    void get_case_1() {
        webTestClient
            .get()
            .uri("/test")
            .exchange()
            .expectStatus()
            .isOk();
    }
}
