package com.solace.acme.store.orderservice.model;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Data
@Getter
@Setter
public class OrderCache {

    private static volatile OrderCache orderCacheInstance;
    private Map<String, Order> orderMap;

    private OrderCache() {
    }

    public static OrderCache getInstance() {
        if (orderCacheInstance == null) {
            synchronized (OrderCache.class) {
                orderCacheInstance = new OrderCache();
                orderCacheInstance.orderMap = new HashMap<>();
            }
        }
        return orderCacheInstance;
    }
}
