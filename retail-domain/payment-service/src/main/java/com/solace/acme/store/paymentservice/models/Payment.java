package com.solace.acme.store.paymentservice.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Payment {

    private String id;
    private String orderId;
    private String ccy;
    private double amount;
}
