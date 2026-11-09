package io.github.aryodh.ledger.payment;

import java.nio.charset.StandardCharsets;

import io.github.aryodh.ledger.api.ApiException;
import io.github.aryodh.ledger.api.RequestId;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PaymentController {

	private final PaymentAcceptance acceptance;

	PaymentController(PaymentAcceptance acceptance) {
		this.acceptance = acceptance;
	}

	@PostMapping(path = "/v1/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<String> create(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreatePaymentRequest body, HttpServletRequest request) {
		if (idempotencyKey == null || idempotencyKey.isEmpty() || idempotencyKey.length() > 255) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
					"The Idempotency-Key header must be 1 to 255 characters");
		}
		if (body.merchantReference() == null || body.amountMinor() == null || body.currency() == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
					"merchant_reference, amount_minor, and currency are required");
		}
		if (body.merchantReference().isEmpty() || body.merchantReference().length() > 200
				|| body.merchantReference().chars().anyMatch(Character::isISOControl)
				|| !isWellFormedUnicode(body.merchantReference())) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_MERCHANT_REFERENCE",
					"merchant_reference must be 1 to 200 characters of valid Unicode with no control characters");
		}
		if (body.amountMinor() < 1 || body.amountMinor() > 100_000_000) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_AMOUNT",
					"amount_minor must be between 1 and 100000000");
		}
		if (!body.currency().equals("SGD")) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_CURRENCY", "currency must be SGD");
		}

		PaymentAcceptance.Response response = this.acceptance.accept(idempotencyKey, body.merchantReference(),
				body.amountMinor(), body.currency(), RequestId.of(request));
		return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(response.body());
	}

	// A JSON escape can produce an unpaired surrogate, such as U+D800 on its own. It has no UTF-8 encoding, so it
	// can't be stored or hashed as sent.
	private static boolean isWellFormedUnicode(String value) {
		return StandardCharsets.UTF_8.newEncoder().canEncode(value);
	}

	record CreatePaymentRequest(String merchantReference, Long amountMinor, String currency) {
	}

}
