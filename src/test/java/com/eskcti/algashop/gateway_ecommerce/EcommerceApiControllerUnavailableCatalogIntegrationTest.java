package com.eskcti.algashop.gateway_ecommerce;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"eureka.client.enabled=false",
				"spring.cloud.discovery.enabled=false",
				"management.health.redis.enabled=false"
		})
class EcommerceApiControllerUnavailableCatalogIntegrationTest {

	private static final String TOKEN = "token";

	@LocalServerPort
	private int port;

	@MockitoBean
	private ReactiveJwtDecoder jwtDecoder;

	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		Jwt jwt = Jwt.withTokenValue(TOKEN)
				.header("alg", "none")
				.subject("customer-1")
				.claim("scope", "products:read categories:read")
				.build();
		Mockito.when(jwtDecoder.decode(TOKEN)).thenReturn(Mono.just(jwt));
		webTestClient = WebTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.build();
	}

	@Test
	void shouldFailWithInternalServerErrorWhenProductCatalogHasNoInstances() {
		webTestClient.get()
				.uri("/api/v1/ecommerce/home")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

}
