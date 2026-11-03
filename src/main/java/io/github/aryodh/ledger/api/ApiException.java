package io.github.aryodh.ledger.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * A request failure with a stable code, rendered as {@code application/problem+json}.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private final HttpHeaders headers;

	public ApiException(HttpStatus status, String code, String detail) {
		this(status, code, detail, new HttpHeaders());
	}

	public ApiException(HttpStatus status, String code, String detail, HttpHeaders headers) {
		super(detail);
		this.status = status;
		this.code = code;
		this.headers = headers;
	}

	public HttpStatus status() {
		return this.status;
	}

	public String code() {
		return this.code;
	}

	public HttpHeaders headers() {
		return this.headers;
	}

}
