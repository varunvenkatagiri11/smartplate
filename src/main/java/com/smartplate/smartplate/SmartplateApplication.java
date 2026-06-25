package com.smartplate.smartplate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableKafka
@EnableScheduling
public class SmartplateApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmartplateApplication.class, args);
	}

}
