/**
 * Application services and the ports they (and the session WebSocket handler) depend on.
 * Controllers and the handler call into this package; adapters in {@code httphandlers} implement
 * the ports. This is the only layer that talks to the session store and the OKX adapters on their
 * behalf.
 */
package com.assignment.marketdata.services;
