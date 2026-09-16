package com.assignment.marketdata.model;

import java.math.BigDecimal;

/**
 * One row of the market overview.
 * <p>
 * {@code instId}, {@code last} and {@code vol24h} are OKX's raw strings, passed through
 * unreformatted so precision survives for instruments quoted at values like
 * {@code 0.000003361}. {@code change24hPct} is a number and is null when it cannot be computed.
 * <p>
 * Despite its name, {@code vol24h} carries OKX's {@code volCcy24h} (quote-currency notional), not
 * OKX's own {@code vol24h} field (base-currency volume). The name follows the client contract while
 * the value follows the ranking rule; base volume is not comparable across instruments.
 */
public record TickerDto(String instId, String last, BigDecimal change24hPct, String vol24h) {
}
