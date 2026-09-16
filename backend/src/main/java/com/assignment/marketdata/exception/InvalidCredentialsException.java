package com.assignment.marketdata.exception;

/**
 * Raised when a login submission does not identify a user.
 * <p>
 * Carries no detail on purpose. An unknown username and a wrong password must be indistinguishable
 * to the caller, so there is nothing for this type to hold that would be safe to report.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("invalid credentials");
	}

}
