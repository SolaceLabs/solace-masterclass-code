package com.solace.acme.store.orderservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Shipping {
    private String id;
    private String orderId;
    private int trackingNumber;
}
