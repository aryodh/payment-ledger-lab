package io.github.aryodh.ledger.api;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler
	ResponseEntity<ProblemDetail> handle(ApiException ex, HttpServletRequest request) {
		return problem(ex.status(), ex.code(), ex.getMessage(), ex.headers(), request);
	}

	@ExceptionHandler
	ResponseEntity<ProblemDetail> handle(HttpMessageNotReadableException ex, HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request body is not valid JSON of the expected shape",
				new HttpHeaders(), request);
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
			HttpHeaders headers, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		problem.setProperty("request_id", RequestId.of(request));
		return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}

}
