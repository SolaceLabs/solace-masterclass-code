package com.solace.acme.store.orderservice.service;


import com.solace.acme.store.orderservice.config.SolaceConnectionParameters;
import com.solace.acme.store.orderservice.model.Order;
import com.solace.acme.store.orderservice.model.OrderCache;
import lombok.extern.slf4j.Slf4j;
import org.instancio.Instancio;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.instancio.Select.field;


@Service
@Slf4j
public class OrderService {

    private SolaceEventPublisher solaceEventPublisher;

    @Autowired
    public void setSolaceEventPublisher(SolaceEventPublisher solaceEventPublisher) {
        this.solaceEventPublisher = solaceEventPublisher;
    }

    public boolean connectToBroker(final SolaceConnectionParameters solaceConnectionParameters) {
        return solaceEventPublisher.connectToBroker(solaceConnectionParameters);
    }

    public void createBasket() {
        final Order order = generateNewOrderModelForBasket();
        OrderCache.getInstance().getOrderMap().put(order.getId(), order);
        scheduleOrderCreatedEvent(order);
    }

    private void scheduleOrderCreatedEvent(final Order order) {
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.schedule(() -> processOrderCreation(order), 15, TimeUnit.SECONDS);
        executorService.shutdown();
    }


    void processOrderCreation(final Order order) {
        order.setState(Order.OrderState.CREATED);
        solaceEventPublisher.publishOrderCreatedEvent(order);
        OrderCache.getInstance().getOrderMap().put(order.getId(), order);
    }

    private Order generateNewOrderModelForBasket() {
        return Instancio.of(Order.class)
                .generate(field(Order::getId), gen -> gen.string())
                .generate(field(Order::getCustomerId), gen -> gen.string())
                .set(field(Order::getState), Order.OrderState.INITIALIZED)
                .generate(field(Order::getProduct), gen -> gen.oneOf("Hoodie", "Leather Jacket", "Spider-man lego set", "Iphone 15 Pro Max", "Apple watch Ultra 2", "Macbook"))
                .generate(field(Order::getQuantity), gen -> gen.ints().range(1, 5))
                .set(field(Order::getPrice), Math.round((Math.random() * 100) * 100.0) / 100.0)
                .set(field(Order::getDeliveryAddress), (Instancio.of(Order.DeliveryAddress.class)
                        .generate(field(Order.DeliveryAddress::getStreet), gen -> gen.string())
                        .generate(field(Order.DeliveryAddress::getCity), gen -> gen.string())
                        .generate(field(Order.DeliveryAddress::getState), gen -> gen.string())
                        .set(field(Order.DeliveryAddress::getPostalCode), UUID.randomUUID().toString().replaceAll("[^a-zA-Z0-9]", "").substring(0, 6))
                        .generate(field(Order.DeliveryAddress::getCountry), gen -> gen.string())
                        .create()))
                .set(field(Order::getPaymentInfo), (Instancio.of(Order.PaymentInfo.class)
                        .generate(field(Order.PaymentInfo::getCardNumber), gen -> gen.id().pol().nip())
                        .set(field(Order.PaymentInfo::getExpirationDate), generateFutureDate())
                        .generate(field(Order.PaymentInfo::getCvv), gen -> gen.ints().range(100, 999))
                        .create()))
                .create();
    }

    private String generateFutureDate() {
        LocalDate futureDate = LocalDate.now().plusYears((long) (Math.random() * 5) + 1);
        return futureDate.toString();
    }
}
