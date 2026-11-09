package io.github.aryodh.ledger.api;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

/**
 * One random ID per HTTP request, for correlating an error or audit record with the request. It is never a
 * deduplication key.
 */
public final class RequestId {

	private static final String ATTRIBUTE = RequestId.class.getName();

	private RequestId() {
	}

	public static String of(HttpServletRequest request) {
		if (request.getAttribute(ATTRIBUTE) instanceof String id) {
			return id;
		}
		String id = UUID.randomUUID().toString();
		request.setAttribute(ATTRIBUTE, id);
		return id;
	}

}
