/**
 * HTTP error translation. Holds the application's own exception types and the single advice that
 * renders any failure reaching the servlet container as the contract's {@code {"error": "..."}}
 * body, so no controller has to assemble an error response itself.
 * <p>
 * WebSocket failures are not routed here: a socket that is already open cannot be answered with a
 * status code, so the WebSocket handler sends {@code error} frames on the connection instead.
 */
package com.assignment.marketdata.exception;
