package io.github.aryodh.ledger.api;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as {@code application/problem+json} with a stable {@code code} and the {@code request_id}.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler
	ResponseEntity<Object> handle(ApiException ex, HttpServletRequest request) {
		return problem(ex.status(), ex.code(), ex.getMessage(), ex.headers(), request);
	}

	@ExceptionHandler
	ResponseEntity<Object> handle(Exception ex, HttpServletRequest request) {
		this.logger.error("Unhandled failure for " + request.getMethod() + " " + request.getRequestURI(), ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be completed",
				new HttpHeaders(), request);
	}

	// Spring MVC's own failures, such as 404, 405, 415, and an unreadable body.
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
		if (ex instanceof HttpMessageNotReadableException) {
			return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
					"The request body is not valid JSON of the expected shape", headers, servletRequest);
		}
		HttpStatus resolved = HttpStatus.valueOf(status.value());
		String detail = (body instanceof ProblemDetail problem && problem.getDetail() != null) ? problem.getDetail()
				: resolved.getReasonPhrase();
		return problem(resolved, resolved.name(), detail, headers, servletRequest);
	}

	private static ResponseEntity<Object> problem(HttpStatus status, String code, String detail, HttpHeaders headers,
			HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		problem.setProperty("request_id", RequestId.of(request));
		return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}

}
