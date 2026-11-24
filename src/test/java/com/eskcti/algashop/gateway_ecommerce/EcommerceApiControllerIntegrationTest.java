package com.eskcti.algashop.gateway_ecommerce;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.reactive.function.client.ServerBearerExchangeFilterFunction;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"eureka.client.enabled=false",
				"spring.cloud.discovery.enabled=false",
				"management.health.redis.enabled=false"
		})
@Import(EcommerceApiControllerIntegrationTest.StubCatalogConfiguration.class)
class EcommerceApiControllerIntegrationTest {

	private static final String TOKEN = "token";
	private static final String BOTH_SCOPES = "products:read categories:read";

	private static final String PRODUCTS_PAGE = """
			{
			  "content": [{"id": "product-1", "name": "Product 1", "hasDiscount": true}],
			  "number": 0,
			  "size": 10,
			  "totalPages": 1,
			  "totalElements": 1
			}
			""";

	private static final String CATEGORIES_PAGE = """
			{
			  "content": [{"id": "category-1", "name": "Category 1"}],
			  "number": 0,
			  "size": 10,
			  "totalPages": 1,
			  "totalElements": 1
			}
			""";

	@LocalServerPort
	private int port;

	@MockitoBean
	private ReactiveJwtDecoder jwtDecoder;

	private WebTestClient webTestClient;

	private ProductCatalogStub productCatalogStub;

	@BeforeEach
	void setUp() {
		productCatalogStub = StubCatalogConfiguration.STUB;
		productCatalogStub.reset();
		authenticateWith(BOTH_SCOPES);
		webTestClient = WebTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.build();
	}

	@Test
	void shouldAggregateProductHighlightsAndCategories() {
		productCatalogStub.respondJson("/api/v1/products", HttpStatus.OK, PRODUCTS_PAGE);
		productCatalogStub.respondJson("/api/v1/categories", HttpStatus.OK, CATEGORIES_PAGE);

		webTestClient.get()
				.uri("/api/v1/ecommerce/home")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.highlights[0].id").isEqualTo("product-1")
				.jsonPath("$.highlights[0].hasDiscount").isEqualTo(true)
				.jsonPath("$.categories[0].id").isEqualTo("category-1");

		assertThat(productCatalogStub.requests()).hasSize(2);
		assertThat(requestTo("/api/v1/products").url().getQuery()).isEqualTo("hasDiscount=true");
	}

	@Test
	void shouldPropagateBearerTokenToProductCatalog() {
		productCatalogStub.respondJson("/api/v1/products", HttpStatus.OK, PRODUCTS_PAGE);
		productCatalogStub.respondJson("/api/v1/categories", HttpStatus.OK, CATEGORIES_PAGE);

		webTestClient.get()
				.uri("/api/v1/ecommerce/home")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isOk();

		assertThat(requestTo("/api/v1/products").headers().getFirst(HttpHeaders.AUTHORIZATION))
				.isEqualTo("Bearer " + TOKEN);
		assertThat(requestTo("/api/v1/categories").headers().getFirst(HttpHeaders.AUTHORIZATION))
				.isEqualTo("Bearer " + TOKEN);
	}

	@Test
	void shouldReturnEmptyListsWhenCatalogPagesHaveNoContent() {
		productCatalogStub.respondJson("/api/v1/products", HttpStatus.OK, """
				{"content": [], "number": 0, "size": 10, "totalPages": 0, "totalElements": 0}
				""");
		productCatalogStub.respondJson("/api/v1/categories", HttpStatus.OK, """
				{"content": [], "number": 0, "size": 10, "totalPages": 0, "totalElements": 0}
				""");

		webTestClient.get()
				.uri("/api/v1/ecommerce/home")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.highlights").isEmpty()
				.jsonPath("$.categories").isEmpty();
	}

	@Test
	void shouldRejectRequestWithoutToken() {
		webTestClient.get()
				.uri("/api/v1/ecommerce/home")
				.exchange()
				.expectStatus().isUnauthorized();

		assertThat(productCatalogStub.requests()).isEmpty();
	}

	@Test
	void shouldForbidRequestWithoutProductsReadScope() {
		authenticateWith("categories:read");

		webTestClient.get()
				.uri("/api/v1/ecommerce/home")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isForbidden();

		assertThat(productCatalogStub.requests()).isEmpty();
	}

	@Test
	void shouldForbidRequestWithoutCategoriesReadScope() {
		authenticateWith("products:read");

		webTestClient.get()
				.uri("/api/v1/ecommerce/home")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isForbidden();

		assertThat(productCatalogStub.requests()).isEmpty();
	}

	@Test
	void shouldFailWithInternalServerErrorWhenProductsDownstreamFails() {
		productCatalogStub.respondJson("/api/v1/products", HttpStatus.INTERNAL_SERVER_ERROR,
				"{\"error\": \"boom\"}");
		productCatalogStub.respondJson("/api/v1/categories", HttpStatus.OK, CATEGORIES_PAGE);

		webTestClient.get()
				.uri("/api/v1/ecommerce/home")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	void shouldFailWithInternalServerErrorWhenCategoriesDownstreamFails() {
		productCatalogStub.respondJson("/api/v1/products", HttpStatus.OK, PRODUCTS_PAGE);
		productCatalogStub.respondJson("/api/v1/categories", HttpStatus.SERVICE_UNAVAILABLE,
				"{\"error\": \"unavailable\"}");

		webTestClient.get()
				.uri("/api/v1/ecommerce/home")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	void shouldReturnEmptyBodyWhenDownstreamResponseHasNoBody() {
		productCatalogStub.respondEmpty("/api/v1/products");
		productCatalogStub.respondEmpty("/api/v1/categories");

		webTestClient.get()
				.uri("/api/v1/ecommerce/home")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isOk()
				.expectBody().isEmpty();
	}

	@Test
	void shouldFailWithInternalServerErrorWhenDownstreamPageHasNoContentField() {
		productCatalogStub.respondJson("/api/v1/products", HttpStatus.OK, """
				{"number": 0, "size": 10, "totalPages": 0, "totalElements": 0}
				""");
		productCatalogStub.respondJson("/api/v1/categories", HttpStatus.OK, CATEGORIES_PAGE);

		webTestClient.get()
				.uri("/api/v1/ecommerce/home")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	private void authenticateWith(String scopes) {
		Jwt jwt = Jwt.withTokenValue(TOKEN)
				.header("alg", "none")
				.subject("customer-1")
				.claim("scope", scopes)
				.build();
		Mockito.when(jwtDecoder.decode(TOKEN)).thenReturn(Mono.just(jwt));
	}

	private ClientRequest requestTo(String path) {
		return productCatalogStub.requests().stream()
				.filter(request -> path.equals(request.url().getPath()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No upstream request to " + path));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class StubCatalogConfiguration {

		static final ProductCatalogStub STUB = new ProductCatalogStub();

		@Bean
		@Primary
		WebClient stubWebClient() {
			return WebClient.builder()
					.filter(new ServerBearerExchangeFilterFunction())
					.exchangeFunction(STUB)
					.build();
		}
	}

	static final class ProductCatalogStub implements ExchangeFunction {

		private final Map<String, StubResponse> responses = new ConcurrentHashMap<>();
		private final List<ClientRequest> requests = new CopyOnWriteArrayList<>();

		void reset() {
			this.responses.clear();
			this.requests.clear();
		}

		void respondJson(String path, HttpStatus status, String body) {
			this.responses.put(path, new StubResponse(status, body));
		}

		void respondEmpty(String path) {
			this.responses.put(path, new StubResponse(HttpStatus.OK, null));
		}

		List<ClientRequest> requests() {
			return this.requests;
		}

		@Override
		public Mono<ClientResponse> exchange(ClientRequest request) {
			this.requests.add(request);
			StubResponse response = this.responses.get(request.url().getPath());
			if (response == null) {
				return Mono.error(new AssertionError("Unexpected upstream call: " + request.url()));
			}
			return Mono.just(response.toClientResponse());
		}

		private record StubResponse(HttpStatus status, String body) {

			ClientResponse toClientResponse() {
				ClientResponse.Builder builder = ClientResponse.create(this.status)
						.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
				if (this.body != null) {
					builder.body(this.body);
				}
				return builder.build();
			}
		}
	}

}
