package com.solace.acme.bank.frauddetection.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Transaction {
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

    @JsonProperty("timestamp")
    private String timestamp;
}
