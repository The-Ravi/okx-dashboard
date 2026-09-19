package com.assignment.marketdata.httphandlers.orderbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.assignment.marketdata.enums.BookAction;
import com.assignment.marketdata.enums.WireOp;
import com.assignment.marketdata.utility.AppConstants;

/**
 * OKX {@code books} wire format: subscribe/unsubscribe frames out, typed inbound frames in. Does
 * not own connection state or the book itself.
 */
public final class OkxBooksCodec {

	private final ObjectMapper objectMapper;

	public OkxBooksCodec(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String subscribe(String instId) {
		return channelOp(WireOp.SUBSCRIBE.value(), instId);
	}

	public String unsubscribe(String instId) {
		return channelOp(WireOp.UNSUBSCRIBE.value(), instId);
	}

	public Frame parse(String raw) {
		if (AppConstants.Okx.PONG.equals(raw)) {
			return new Frame.KeepalivePong();
		}
		JsonNode root;
		try {
			root = this.objectMapper.readTree(raw);
		}
		catch (Exception ex) {
			return new Frame.Unparseable(ex.toString());
		}
		if (root.hasNonNull(AppConstants.Json.EVENT)) {
			return new Frame.ControlEvent(root.path(AppConstants.Json.EVENT).asText(),
					root.path(AppConstants.Json.CODE).asText(), root.path(AppConstants.Json.MSG).asText(),
					root.path(AppConstants.Json.ARG).path(AppConstants.Json.INST_ID).asText());
		}
		JsonNode arg = root.path(AppConstants.Json.ARG);
		if (!AppConstants.Okx.BOOKS_CHANNEL.equals(arg.path(AppConstants.Json.CHANNEL).asText())) {
			return Frame.Ignored.INSTANCE;
		}
		String instId = arg.path(AppConstants.Json.INST_ID).asText(null);
		JsonNode data = root.path(AppConstants.Json.DATA);
		if (instId == null || !data.isArray() || data.isEmpty()) {
			return Frame.Ignored.INSTANCE;
		}
		return new Frame.BookData(instId,
				BookAction.SNAPSHOT.matches(root.path(AppConstants.Json.ACTION).asText()), data.get(0));
	}

	private String channelOp(String op, String instId) {
		ObjectNode arg = this.objectMapper.createObjectNode();
		arg.put(AppConstants.Json.CHANNEL, AppConstants.Okx.BOOKS_CHANNEL);
		arg.put(AppConstants.Json.INST_ID, instId);
		ArrayNode args = this.objectMapper.createArrayNode();
		args.add(arg);
		ObjectNode frame = this.objectMapper.createObjectNode();
		frame.put(AppConstants.Json.OP, op);
		frame.set(AppConstants.Json.ARGS, args);
		return frame.toString();
	}

	/**
	 * Parsed inbound payload. Sealed so the client can switch on kind without a stringly protocol
	 * in the orchestrator.
	 */
	public sealed interface Frame {

		record KeepalivePong() implements Frame {
		}

		record ControlEvent(String event, String code, String msg, String instId) implements Frame {
		}

		record BookData(String instId, boolean snapshot, JsonNode book) implements Frame {
		}

		record Unparseable(String detail) implements Frame {
		}

		enum Ignored implements Frame {

			INSTANCE

		}

	}

}
