package com.eskcti.algashop.gateway_ecommerce;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigTest {

	private final RateLimitConfig rateLimitConfig = new RateLimitConfig();

	@Test
	void shouldResolveKeyFromJwtSubject() {
		Jwt jwt = Jwt.withTokenValue("token")
				.header("alg", "none")
				.claim("sub", "customer-1")
				.build();

		String key = resolveKey(new JwtAuthenticationToken(jwt));

		assertThat(key).isEqualTo("customer-1");
	}

	@Test
	void shouldFallbackToAnonymousWhenJwtHasNoSubject() {
		Jwt jwt = Mockito.mock(Jwt.class);
		JwtAuthenticationToken authentication = Mockito.mock(JwtAuthenticationToken.class);
		Mockito.when(authentication.getToken()).thenReturn(jwt);
		Mockito.when(jwt.getClaimAsString("sub")).thenReturn(null);

		String key = resolveKey(authentication);

		assertThat(key).isEqualTo("anonymous");
	}

	@Test
	void shouldFallbackToAnonymousForNonJwtPrincipal() {
		String key = resolveKey(new UsernamePasswordAuthenticationToken("user", "password"));

		assertThat(key).isEqualTo("anonymous");
	}

	@Test
	void shouldFallbackToAnonymousWhenPrincipalIsEmpty() {
		ServerWebExchange exchange = Mockito.mock(ServerWebExchange.class);
		Mockito.when(exchange.getPrincipal()).thenReturn(Mono.empty());

		String key = rateLimitConfig.rateLimitKeyResolver().resolve(exchange).block();

		assertThat(key).isEqualTo("anonymous");
	}

	private String resolveKey(Authentication authentication) {
		ServerWebExchange exchange = Mockito.mock(ServerWebExchange.class);
		Mockito.when(exchange.getPrincipal()).thenReturn(Mono.just(authentication));
		return rateLimitConfig.rateLimitKeyResolver().resolve(exchange).block();
	}

}
