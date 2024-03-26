package com.solace.acme.store.inventoryfraudcheckservice.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockReservation {
    private int reservationId;
    private String orderId;
    private String customerId;
    private String productId;
    private int quantity;
    private String reservationTime;
    private String expiryTime;
}
