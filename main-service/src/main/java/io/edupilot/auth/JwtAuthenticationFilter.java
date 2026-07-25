package io.edupilot.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import io.edupilot.global.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	public static final String AUTH_ERROR_ATTRIBUTE =
		JwtAuthenticationFilter.class.getName() + ".authError";
	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtTokenProvider jwtTokenProvider;

	public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
		this.jwtTokenProvider = jwtTokenProvider;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String authorization = request.getHeader("Authorization");
		if (!StringUtils.hasText(authorization)) {
			filterChain.doFilter(request, response);
			return;
		}
		if (!authorization.startsWith(BEARER_PREFIX)) {
			request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.TOKEN_INVALID);
			filterChain.doFilter(request, response);
			return;
		}

		try {
			AuthenticatedUser principal = jwtTokenProvider.parseAccessToken(
				authorization.substring(BEARER_PREFIX.length())
			);
			var authentication = new UsernamePasswordAuthenticationToken(
				principal,
				null,
				List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))
			);
			SecurityContextHolder.getContext().setAuthentication(authentication);
		} catch (JwtTokenValidationException exception) {
			request.setAttribute(AUTH_ERROR_ATTRIBUTE, exception.errorCode());
			SecurityContextHolder.clearContext();
		}

		filterChain.doFilter(request, response);
	}
}
