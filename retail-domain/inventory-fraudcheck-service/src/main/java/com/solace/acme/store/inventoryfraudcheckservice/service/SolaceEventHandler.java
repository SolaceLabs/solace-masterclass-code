package com.solace.acme.store.inventoryfraudcheckservice.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.acme.store.inventoryfraudcheckservice.config.SolaceConfigProperties;
import com.solace.acme.store.inventoryfraudcheckservice.config.SolaceConnectionParameters;
import com.solace.acme.store.inventoryfraudcheckservice.models.Order;
import com.solace.acme.store.inventoryfraudcheckservice.models.StockReservation;
import com.solace.messaging.MessagingService;
import com.solace.messaging.PubSubPlusClientException;
import com.solace.messaging.config.SolaceProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.OutboundMessageBuilder;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.receiver.MessageReceiver;
import com.solace.messaging.receiver.PersistentMessageReceiver;
import com.solace.messaging.resources.Queue;
import com.solace.messaging.resources.Topic;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringSubstitutor;
import org.instancio.Instancio;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.instancio.Select.field;

@Slf4j
@Component
public class SolaceEventHandler {

    @Autowired
    private SolaceConfigProperties configProperties;
    private PersistentMessagePublisher persistentMessagePublisher;
    private OutboundMessageBuilder messageBuilder;
    ObjectMapper objectMapper = new ObjectMapper();


    public boolean connectAndConfigureConsumers(final SolaceConnectionParameters solaceConnectionParameters) {
        try {
            final Properties properties = setupPropertiesForConnection(solaceConnectionParameters);
            final MessagingService messagingService = MessagingService.builder(ConfigurationProfile.V1).fromProperties(properties).build();
            messagingService.connect();
            setupConnectivityHandlingInMessagingService(messagingService);
            messageBuilder = messagingService.messageBuilder();
            persistentMessagePublisher = messagingService.createPersistentMessagePublisherBuilder().onBackPressureWait(1).build();

            final PersistentMessageReceiver ordersCreatedEventReceiver = messagingService.createPersistentMessageReceiverBuilder().build(Queue.durableExclusiveQueue(configProperties.getOrderCreatedQueueName()));
            ordersCreatedEventReceiver.setReceiveFailureListener(failedReceiveEvent -> System.out.println("### FAILED RECEIVE EVENT " + failedReceiveEvent));
            ordersCreatedEventReceiver.start();
            ordersCreatedEventReceiver.receiveAsync(buildOrdersCreatedEventHandler(ordersCreatedEventReceiver));

            persistentMessagePublisher.start();
            persistentMessagePublisher.setMessagePublishReceiptListener(publishReceipt -> {
                final PubSubPlusClientException e = publishReceipt.getException();
                if (e == null) {
                    OutboundMessage outboundMessage = publishReceipt.getMessage();
                    log.info(String.format("ACK for Message %s", outboundMessage));
                } else {
                    Object userContext = publishReceipt.getUserContext();
                    if (userContext != null) {
                        log.warn(String.format("NACK for Message %s - %s", userContext, e));
                    } else {
                        OutboundMessage outboundMessage = publishReceipt.getMessage();  // which message got NACKed?
                        log.warn(String.format("NACK for Message %s - %s", outboundMessage, e));
                    }
                }
            });
            log.info("Configuration of Receivers and Producers successful");
            return true;
        } catch (Exception exception) {
            log.error("Error encountered while connecting to the Solace broker, error :{}", exception.getMessage());
            return false;
        }
    }

    private MessageReceiver.MessageHandler buildOrdersCreatedEventHandler(final PersistentMessageReceiver ordersCreatedEventReceiver) {
        return (inboundMessage -> {
            try {
                final String inboundTopic = inboundMessage.getDestinationName();
                log.info("Processing message on incoming topic :{} with payload:{}", inboundTopic, inboundMessage.getPayloadAsString());
                boolean eventProcessed = reserveStockForCreatedOrder(inboundMessage.getPayloadAsString());
                if (eventProcessed) {
                    ordersCreatedEventReceiver.ack(inboundMessage);
                }
            } catch (RuntimeException runtimeException) {  // threw from send(), only thing that is throwing here
                log.error("Runtime exception encountered while processing incoming event payload :{} on topic:{}. Error is :",
                        inboundMessage.getPayloadAsString(), inboundMessage.getDestinationName(), runtimeException);
            }
        });
    }

    private boolean reserveStockForCreatedOrder(final String incomingOrderCreatedJson) {
        try {
            final Order orderCreated = objectMapper.readValue(incomingOrderCreatedJson, Order.class);
            //this is usually the location where you would implement your
            //fraud checking logic for e.g. validate the customer against a black list, check cumulative order value, cart quantity levels etc.
            log.info("Fraud check for Order:{}, customer:{}, passed", orderCreated.getId(), orderCreated.getCustomerId());
            //stock reservation logic and make api calls to reserve physical stock in your ERP
            log.info("Stock reserved on product:{}, quantity:{} for orderid:{} and customerId:{}", orderCreated.getProduct(), orderCreated.getQuantity(), orderCreated.getId(), orderCreated.getCustomerId());
            createAndPublishOrderStockReservedEvent(orderCreated);
            createAndPublishOrderConfirmedEvent(orderCreated);
            return true;
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while processing Order event:{}, exception:", incomingOrderCreatedJson, jsonProcessingException);
            return false;
        }
    }

    private void createAndPublishOrderConfirmedEvent(Order orderCreatedEvent) {
        final Order orderConfirmed = createOrderConfirmedEvent(orderCreatedEvent);
        publishOrderConfirmedEvent(orderConfirmed);
    }

    private Order createOrderConfirmedEvent(final Order orderCreatedEvent) {
        return Order.builder()
                .id(orderCreatedEvent.getId())
                .customerId(orderCreatedEvent.getCustomerId())
                .state(Order.OrderState.VALIDATED)
                .product(orderCreatedEvent.getProduct())
                .quantity(orderCreatedEvent.getQuantity())
                .price(orderCreatedEvent.getPrice())
                .deliveryAddress(orderCreatedEvent.getDeliveryAddress())
                .paymentInfo(orderCreatedEvent.getPaymentInfo())
                .build();
    }

    public void publishOrderConfirmedEvent(final Order orderConfirmed) {
        try {
            String orderConfirmedJson = objectMapper.writeValueAsString(orderConfirmed);
            final OutboundMessage message = messageBuilder.build(orderConfirmedJson);
            final Map<String, Object> params = new HashMap<>();
            params.put("regionId", orderConfirmed.getDeliveryAddress().getCountry());
            params.put("orderId", orderConfirmed.getId());
            String topicString = StringSubstitutor.replace(configProperties.getOrderConfirmedEventTopicString(), params, "{", "}");
            persistentMessagePublisher.publish(message, Topic.of(topicString));
            log.info("Published OrderConfirmed event :{} on topic : {}", orderConfirmedJson, topicString);
        } catch (final RuntimeException runtimeException) {
            log.error("Error encountered while publishing event, exception :", runtimeException);
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while converting OrderConfirmed to JSON string, exception :", jsonProcessingException);
        }
    }

    private void createAndPublishOrderStockReservedEvent(Order orderCreatedEvent) {
        final StockReservation stockReservation = createStockReservedEvent(orderCreatedEvent);
        publishStockReservedEvent(stockReservation);
    }


    public void publishStockReservedEvent(final StockReservation stockReservation) {
        try {
            String stockReservationJson = objectMapper.writeValueAsString(stockReservation);
            final OutboundMessage message = messageBuilder.build(stockReservationJson);
            final Map<String, Object> params = new HashMap<>();
            params.put("orderId", stockReservation.getOrderId());
            params.put("productId", stockReservation.getProductId());
            params.put("reservationId", stockReservation.getReservationId());
            String topicString = StringSubstitutor.replace(configProperties.getStockReservationTopicString(), params, "{", "}");
            persistentMessagePublisher.publish(message, Topic.of(topicString));
            log.info("Published StockReservation event :{} on topic : {}", stockReservationJson, topicString);
        } catch (final RuntimeException runtimeException) {
            log.error("Error encountered while publishing event, exception :", runtimeException);
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while converting StockReservation to JSON string, exception :", jsonProcessingException);
        }
    }

    private StockReservation createStockReservedEvent(final Order orderCreatedEvent) {
        return Instancio.of(StockReservation.class)
                .generate(field(StockReservation::getReservationId), gen -> gen.ints())
                .set(field(StockReservation::getOrderId), orderCreatedEvent.getId())
                .set(field(StockReservation::getCustomerId), orderCreatedEvent.getCustomerId())
                .set(field(StockReservation::getProductId), orderCreatedEvent.getProduct())
                .set(field(StockReservation::getQuantity), orderCreatedEvent.getQuantity())
                .generate(field(StockReservation::getReservationTime), gen -> gen.temporal().localDateTime().asString())
                .generate(field(StockReservation::getExpiryTime), gen -> gen.temporal().localDateTime().future().asString())
                .create();
    }


    private static void setupConnectivityHandlingInMessagingService(final MessagingService messagingService) {
        messagingService.addServiceInterruptionListener(serviceEvent -> System.out.println("### SERVICE INTERRUPTION: " + serviceEvent.getCause()));
        messagingService.addReconnectionAttemptListener(serviceEvent -> System.out.println("### RECONNECTING ATTEMPT: " + serviceEvent));
        messagingService.addReconnectionListener(serviceEvent -> System.out.println("### RECONNECTED: " + serviceEvent));
    }

    private Properties setupPropertiesForConnection(final SolaceConnectionParameters solaceConnectionParameters) {
        final Properties properties = new Properties();
        properties.setProperty(SolaceProperties.TransportLayerProperties.HOST, solaceConnectionParameters.getHostUrl());
        properties.setProperty(SolaceProperties.ServiceProperties.VPN_NAME, solaceConnectionParameters.getVpnName());
        properties.setProperty(SolaceProperties.AuthenticationProperties.SCHEME_BASIC_USER_NAME, solaceConnectionParameters.getUserName());
        properties.setProperty(SolaceProperties.AuthenticationProperties.SCHEME_BASIC_PASSWORD, solaceConnectionParameters.getPassword());
        properties.setProperty(SolaceProperties.TransportLayerProperties.RECONNECTION_ATTEMPTS, configProperties.getReconnectionAttempts());
        properties.setProperty(SolaceProperties.TransportLayerProperties.CONNECTION_RETRIES_PER_HOST, configProperties.getConnectionRetriesPerHost());
        return properties;
    }
}