package com.assignment.marketdata.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.assignment.marketdata.model.ErrorResponse;
import com.assignment.marketdata.utility.AppConstants;

/**
 * Single exit point for HTTP errors. Every failure leaves as {@code {"error": "..."}} with a status
 * the contract defines, replacing Spring's default error body.
 * <p>
 * Messages are fixed strings rather than the exception's own text. Framework messages quote the
 * offending input and name internal types, which tells a caller about the server's shape without
 * telling them anything useful about their request; the detail goes to the log instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(InvalidCredentialsException.class)
	public ResponseEntity<ErrorResponse> handleInvalidCredentials() {
		return respond(HttpStatus.UNAUTHORIZED, AppConstants.Errors.INVALID_CREDENTIALS);
	}

	/**
	 * A body that is not JSON, or is JSON of the wrong shape, never reaches the controller. This is
	 * a malformed request rather than a rejected one, so it is reported as such and not as a
	 * credential failure.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
		logger.debug("Rejected an unreadable request body: {}", ex.getMessage());
		return respond(HttpStatus.BAD_REQUEST, AppConstants.Errors.MALFORMED_REQUEST_BODY);
	}

	@ExceptionHandler({ MissingServletRequestParameterException.class,
			MethodArgumentTypeMismatchException.class })
	public ResponseEntity<ErrorResponse> handleBadParameters(Exception ex) {
		logger.debug("Rejected a request with bad parameters: {}", ex.getMessage());
		return respond(HttpStatus.BAD_REQUEST, AppConstants.Errors.INVALID_REQUEST_PARAMETERS);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleWrongMethod(HttpRequestMethodNotSupportedException ex) {
		return respond(HttpStatus.METHOD_NOT_ALLOWED, AppConstants.Errors.METHOD_NOT_ALLOWED_PREFIX
				+ ex.getMethod() + AppConstants.Errors.METHOD_NOT_ALLOWED_SUFFIX);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleUnknownPath() {
		return respond(HttpStatus.NOT_FOUND, AppConstants.Errors.NOT_FOUND);
	}

	/**
	 * Last resort. An unexpected failure is logged with its stack trace and answered with a message
	 * that describes nothing, because at this point nothing is known to be safe to describe.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		logger.error("Unhandled exception while serving a request", ex);
		return respond(HttpStatus.INTERNAL_SERVER_ERROR, AppConstants.Errors.INTERNAL_SERVER_ERROR);
	}

	private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(new ErrorResponse(message));
	}

}
