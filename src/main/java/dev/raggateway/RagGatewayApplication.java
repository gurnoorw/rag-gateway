package dev.raggateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point. One Spring Boot service: the OpenAI-compatible gateway path, the RAG query path
 * and the asynchronous ingestion worker. Request handling runs on virtual threads
 * ({@code spring.threads.virtual.enabled=true}); see docs/process/DECISIONS.md D-03.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RagGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagGatewayApplication.class, args);
    }
}
