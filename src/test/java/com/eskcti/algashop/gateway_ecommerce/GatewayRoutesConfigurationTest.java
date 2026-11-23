package com.eskcti.algashop.gateway_ecommerce;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GatewayRoutesConfigurationTest {

	@Autowired
	private GatewayProperties gatewayProperties;

	@Autowired
	private CircuitBreakerRegistry circuitBreakerRegistry;

	@Autowired
	private TimeLimiterRegistry timeLimiterRegistry;

	@Autowired
	private Environment environment;

	@Test
	void shouldConfigureOrderingRouteWithCircuitBreaker() {
		RouteDefinition route = route("ordering-route");

		assertThat(route.getUri()).isEqualTo(URI.create("lb://ordering"));
		assertThat(route.getFilters()).extracting(FilterDefinition::getName)
				.contains("CircuitBreaker");

		FilterDefinition circuitBreaker = filter(route, "CircuitBreaker");

		assertThat(circuitBreaker.getArgs())
				.containsEntry("name", "ordering-route-cb")
				.containsEntry("statusCodes.0", "502")
				.containsEntry("statusCodes.1", "504")
				.containsEntry("statusCodes.2", "500");
	}

	@Test
	void shouldConfigureProductCatalogRouteWithResponseCacheAndRateLimiter() {
		RouteDefinition route = route("product-catalog-route");

		assertThat(route.getUri()).isEqualTo(URI.create("lb://product-catalog"));
		assertThat(route.getFilters()).extracting(FilterDefinition::getName)
				.contains("LocalResponseCache", "RequestRateLimiter");

		FilterDefinition responseCache = filter(route, "LocalResponseCache");

		assertThat(responseCache.getArgs())
				.containsEntry("_genkey_0", "5m")
				.containsEntry("_genkey_1", "30MB");

		FilterDefinition rateLimiter = filter(route, "RequestRateLimiter");

		assertThat(rateLimiter.getArgs())
				.containsEntry("redis-rate-limiter.replenishRate", "50")
				.containsEntry("redis-rate-limiter.burstCapacity", "200")
				.containsEntry("redis-rate-limiter.requestedTokens", "1")
				.containsEntry("key-resolver", "#{@rateLimitKeyResolver}");
	}

	@Test
	void shouldConfigureDefaultRetryAndDedupeFilters() {
		assertThat(gatewayProperties.getDefaultFilters()).extracting(FilterDefinition::getName)
				.contains("DedupeResponseHeader", "Retry");

		FilterDefinition retry = gatewayProperties.getDefaultFilters().stream()
				.filter(filter -> "Retry".equals(filter.getName()))
				.findFirst()
				.orElseThrow();

		assertThat(retry.getArgs())
				.containsEntry("retries", "3")
				.containsEntry("statuses", "BAD_GATEWAY, INTERNAL_SERVER_ERROR, GATEWAY_TIMEOUT")
				.containsEntry("methods", "GET,PUT")
				.containsEntry("backoff.firstBackoff", "10ms")
				.containsEntry("backoff.maxBackoff", "20ms")
				.containsEntry("backoff.factor", "2")
				.containsEntry("backoff.basedOnPreviousValue", "false");
	}

	@Test
	void shouldConfigureHttpClientTimeouts() {
		assertThat(environment.getProperty("spring.cloud.gateway.server.webflux.httpclient.connect-timeout"))
				.isEqualTo("1000");
		assertThat(environment.getProperty("spring.cloud.gateway.server.webflux.httpclient.response-timeout"))
				.isEqualTo("5000");
	}

	@Test
	void shouldConfigureOrderingCircuitBreakerInstance() {
		CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("ordering-route-cb");
		CircuitBreakerConfig config = circuitBreaker.getCircuitBreakerConfig();

		assertThat(config.getSlidingWindowSize()).isEqualTo(8);
		assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
		assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
		assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
		assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
		assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(10_000L);
		assertThat(config.getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void shouldConfigureDefaultTimeLimiter() {
		assertThat(timeLimiterRegistry.getDefaultConfig().getTimeoutDuration())
				.isEqualTo(Duration.ofSeconds(60));
	}

	@Test
	void shouldExposeCircuitBreakersActuatorEndpoint() {
		assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
				.contains("circuitbreakers");
		assertThat(environment.getProperty(
				"resilience4j.circuitbreaker.instances.ordering-route-cb.registerHealthIndicator",
				Boolean.class))
				.isTrue();
	}

	private RouteDefinition route(String id) {
		List<RouteDefinition> routes = gatewayProperties.getRoutes();
		return routes.stream()
				.filter(route -> id.equals(route.getId()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Route not found: " + id));
	}

	private FilterDefinition filter(RouteDefinition route, String name) {
		return route.getFilters().stream()
				.filter(filter -> name.equals(filter.getName()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Filter not found: " + name));
	}

}
