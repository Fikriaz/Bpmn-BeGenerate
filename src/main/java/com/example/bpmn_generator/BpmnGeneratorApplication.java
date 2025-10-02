package com.example.bpmn_generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
public class BpmnGeneratorApplication {

	public static void main(String[] args) {
		SpringApplication.run(BpmnGeneratorApplication.class, args);
	}

}
