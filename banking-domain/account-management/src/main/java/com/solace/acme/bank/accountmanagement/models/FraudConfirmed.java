package com.solace.acme.bank.accountmanagement.models;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FraudConfirmed {
    private Integer detectionNum;
    private Integer transactionNum;
    private String accountNum;
    private String transactionType;
    private double amount;
    private String currency;
    private String incidentDescription;
    private String fraudConfirmedBy;
    private String incidentTimestamp;
    private String timestamp;
}
