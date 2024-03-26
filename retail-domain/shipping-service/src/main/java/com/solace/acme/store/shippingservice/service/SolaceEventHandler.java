package com.solace.acme.store.shippingservice.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.acme.store.shippingservice.config.SolaceConfigProperties;
import com.solace.acme.store.shippingservice.config.SolaceConnectionParameters;
import com.solace.acme.store.shippingservice.models.Payment;
import com.solace.acme.store.shippingservice.models.Shipping;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.instancio.Select.field;

@Slf4j
@Component
public class SolaceEventHandler {

    @Autowired
    private SolaceConfigProperties configProperties;
    private PersistentMessagePublisher persistentMessagePublisher;
    private OutboundMessageBuilder messageBuilder;
    ObjectMapper objectMapper = new ObjectMapper();

    private enum EventVerb {
        created, updated,
    }


    public boolean connectAndConfigureConsumers(final SolaceConnectionParameters solaceConnectionParameters) {
        try {
            final Properties properties = setupPropertiesForConnection(solaceConnectionParameters);
            final MessagingService messagingService = MessagingService.builder(ConfigurationProfile.V1).fromProperties(properties).build();
            messagingService.connect();
            setupConnectivityHandlingInMessagingService(messagingService);
            messageBuilder = messagingService.messageBuilder();
            persistentMessagePublisher = messagingService.createPersistentMessagePublisherBuilder().onBackPressureWait(1).build();

            final PersistentMessageReceiver paymentConfirmedEventReceiver = messagingService.createPersistentMessageReceiverBuilder().build(Queue.durableExclusiveQueue(configProperties.getPaymentConfirmedQueueName()));
            paymentConfirmedEventReceiver.setReceiveFailureListener(failedReceiveEvent -> System.out.println("### FAILED RECEIVE EVENT " + failedReceiveEvent));
            paymentConfirmedEventReceiver.start();
            paymentConfirmedEventReceiver.receiveAsync(buildPaymentsConfirmedEventHandler(paymentConfirmedEventReceiver));

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

    private MessageReceiver.MessageHandler buildPaymentsConfirmedEventHandler(final PersistentMessageReceiver paymentConfirmedEventReceiver) {
        return (inboundMessage -> {
            try {
                final String inboundTopic = inboundMessage.getDestinationName();
                log.info("Processing message on incoming topic :{} with payload:{}", inboundTopic, inboundMessage.getPayloadAsString());
                boolean eventProcessed = processShipmentForConfirmedPayments(inboundMessage.getPayloadAsString());
                if (eventProcessed) {
                    paymentConfirmedEventReceiver.ack(inboundMessage);
                }
            } catch (RuntimeException runtimeException) {
                log.error("Runtime exception encountered while processing incoming event payload :{} on topic:{}. Error is :", inboundMessage.getPayloadAsString(), inboundMessage.getDestinationName(), runtimeException);
            }
        });
    }

    private boolean processShipmentForConfirmedPayments(final String paymentConfirmedEventJson) {
        try {
            final Payment paymentConfirmed = objectMapper.readValue(paymentConfirmedEventJson, Payment.class);
            // this would be place where you implement your 3PL integration
            log.info("Processed shipping service integration for Order:{}", paymentConfirmed.getId());
            processShipmentCreatedEvent(paymentConfirmed);
            // To emulate shipment flows where there is a separate shipment initialization and tracking code generation etc, we will be publishing a shipment updated event with a delay.
            scheduleShipmentUpdatedEvent(paymentConfirmed);
            return true;
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while processing Payment event:{}, exception:", paymentConfirmedEventJson, jsonProcessingException);
            return false;
        }
    }

    private void processShipmentCreatedEvent(final Payment paymentConfirmed) {
        final Shipping shipmentCreatedEvent = createShipmentCreatedEvent(paymentConfirmed);
        publishShipmentEvent(shipmentCreatedEvent, EventVerb.created);
    }

    private void processShipmentUpdatedEvent(final Payment paymentConfirmed) {
        final Shipping shippingUpdatedEvent = createShipmentUpdatedEvent(paymentConfirmed);
        publishShipmentEvent(shippingUpdatedEvent, EventVerb.updated);
    }

    private void scheduleShipmentUpdatedEvent(final Payment paymentConfirmed) {
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.schedule(() -> processShipmentUpdatedEvent(paymentConfirmed), 15, TimeUnit.SECONDS);
        executorService.shutdown();
    }


    private Shipping createShipmentCreatedEvent(final Payment paymentConfirmed) {
        return Instancio.of(Shipping.class)
                .generate(field(Shipping::getId), gen -> gen.ints().asString())
                .set(field(Shipping::getOrderId), paymentConfirmed.getOrderId())
                .create();
    }

    private Shipping createShipmentUpdatedEvent(final Payment paymentConfirmed) {
        return Instancio.of(Shipping.class)
                .generate(field(Shipping::getId), gen -> gen.ints().asString())
                .set(field(Shipping::getOrderId), paymentConfirmed.getOrderId())
                .generate(field(Shipping::getTrackingNumber), gen -> gen.ints())
                .create();
    }

    public void publishShipmentEvent(final Shipping shipmentEvent, EventVerb verb) {
        try {
            String shipmentEventJson = objectMapper.writeValueAsString(shipmentEvent);
            final OutboundMessage message = messageBuilder.build(shipmentEventJson);
            final Map<String, Object> params = new HashMap<>();
            params.put("verb", verb.name());
            params.put("shipmentId", shipmentEvent.getId());
            params.put("orderId", shipmentEvent.getOrderId());
            String topicString = StringSubstitutor.replace(configProperties.getShippingTopicString(), params, "{", "}");
            persistentMessagePublisher.publish(message, Topic.of(topicString));
            log.info("Published Shipment event :{} on topic : {}", shipmentEventJson, topicString);
        } catch (final RuntimeException runtimeException) {
            log.error("Error encountered while publishing event, exception :", runtimeException);
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while converting Shipping to JSON string, exception :", jsonProcessingException);
        }
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