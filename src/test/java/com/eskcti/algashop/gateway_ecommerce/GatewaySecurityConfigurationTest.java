package com.eskcti.algashop.gateway_ecommerce;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
class GatewaySecurityConfigurationTest {

	private static final String TOKEN = "token";

	private static final Jwt JWT = Jwt.withTokenValue(TOKEN)
			.header("alg", "none")
			.subject("customer-1")
			.claim("scope", "products:read")
			.build();

	@LocalServerPort
	private int port;

	@MockitoBean
	private ReactiveJwtDecoder jwtDecoder;

	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		Mockito.when(jwtDecoder.decode(TOKEN)).thenReturn(Mono.just(JWT));
		webTestClient = WebTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.build();
	}

	@Test
	void shouldAllowHealthEndpointWithoutAuthentication() {
		webTestClient.get()
				.uri("/actuator/health")
				.exchange()
				.expectStatus().isOk();
	}

	@Test
	void shouldAllowWebhooksWithoutAuthentication() {
		webTestClient.post()
				.uri("/api/v1/webhooks/fastpay")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{}")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void shouldRequireAuthenticationForApiRoutes() {
		webTestClient.get()
				.uri("/api/v1/orders")
				.exchange()
				.expectStatus().isUnauthorized();
	}

	@Test
	void shouldAllowAuthenticatedRequestsToApiRoutes() {
		webTestClient.get()
				.uri("/api/v1/orders")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void shouldDenyAnyOtherExchangeForAuthenticatedUsers() {
		webTestClient.get()
				.uri("/any-other-path")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isForbidden();
	}

	@Test
	void shouldAllowCorsPreflightRequests() {
		webTestClient.options()
				.uri("/api/v1/products")
				.header(HttpHeaders.ORIGIN, "http://localhost:4200")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200");
	}

}
