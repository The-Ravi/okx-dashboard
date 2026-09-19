package com.assignment.marketdata;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React routes from the packaged UI. API and WebSocket paths stay on their own
 * controllers; everything here is a client-side route that would otherwise 404 as JSON.
 */
@Controller
public class SpaController {

	@GetMapping({ "/", "/login", "/overview", "/orderbook/{pair}" })
	public String spa() {
		return "forward:/index.html";
	}

}
