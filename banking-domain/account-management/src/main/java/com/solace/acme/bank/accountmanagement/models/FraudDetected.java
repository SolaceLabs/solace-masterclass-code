package com.solace.acme.bank.accountmanagement.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudDetected {
    @JsonProperty("detectionNum")
    private Integer detectionNum;

    @JsonProperty("transactionNum")
    private Integer transactionNum;

    @JsonProperty("accountNum")
    private String accountNum;

    @JsonProperty("transactionType")
    private String transactionType;

    @JsonProperty("amount")
    private Double amount;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("incidentDescription")
    private String incidentDescription;

    @JsonProperty("incidentTimestamp")
    private String incidentTimestamp;

    @JsonProperty("timestamp")
    private String timestamp;
}
