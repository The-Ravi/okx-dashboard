package com.assignment.marketdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MarketdataApplication {

	public static void main(String[] args) {
		SpringApplication.run(MarketdataApplication.class, args);
	}

}
