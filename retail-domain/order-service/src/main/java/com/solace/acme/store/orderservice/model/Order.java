package com.solace.acme.store.orderservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Order {
    private String id;
    private String customerId;
    private OrderState state;
    private String product;
    private int quantity;
    private double price;
    private DeliveryAddress deliveryAddress;
    private PaymentInfo paymentInfo;

    public enum OrderState {
        INITIALIZED,
        CREATED,
        VALIDATED,
        FAILED,
        SHIPPED,
        PAYMENT_PROCESSED
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DeliveryAddress {
        private String street;
        private String city;
        private String state;
        private String postalCode;
        private String country;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PaymentInfo {
        private String cardNumber;
        private String expirationDate;
        private int cvv;
    }
}