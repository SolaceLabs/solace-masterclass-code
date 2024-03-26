package com.solace.acme.store.inventoryfraudcheckservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "solace")
@Getter
@Setter
public class SolaceConfigProperties {
    private String reconnectionAttempts;
    private String connectionRetriesPerHost;
    private String orderCreatedQueueName;
    private String stockReservationTopicString;
    private String orderConfirmedEventTopicString;
}
