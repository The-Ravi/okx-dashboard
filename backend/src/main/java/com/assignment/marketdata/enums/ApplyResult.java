package com.assignment.marketdata.enums;

/**
 * Outcome of applying an incremental books frame to a maintained instrument book.
 */
public enum ApplyResult {

	APPLIED,

	DUPLICATE,

	AWAITING_SNAPSHOT,

	SEQUENCE_GAP

}
