package io.edupilot.auth;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIpResolver {
	private ClientIpResolver() {
	}

	public static String resolve(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		if (forwarded != null) {
			// Production nginx appends its observed peer; never trust a client-prepended entry.
			String lastHop = forwarded.substring(forwarded.lastIndexOf(',') + 1).trim();
			if (lastHop.length() <= 45 && lastHop.matches("[0-9a-fA-F:.]+")) {
				return lastHop;
			}
		}
		return request.getRemoteAddr();
	}
}
