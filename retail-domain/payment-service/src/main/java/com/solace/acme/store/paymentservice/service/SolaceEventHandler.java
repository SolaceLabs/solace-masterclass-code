package com.solace.acme.store.paymentservice.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.acme.store.paymentservice.config.SolaceConfigProperties;
import com.solace.acme.store.paymentservice.config.SolaceConnectionParameters;
import com.solace.acme.store.paymentservice.models.Order;
import com.solace.acme.store.paymentservice.models.Payment;
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

            final PersistentMessageReceiver ordersConfirmedEventReceiver = messagingService.createPersistentMessageReceiverBuilder().build(Queue.durableExclusiveQueue(configProperties.getOrderConfirmedQueueName()));
            ordersConfirmedEventReceiver.setReceiveFailureListener(failedReceiveEvent -> System.out.println("### FAILED RECEIVE EVENT " + failedReceiveEvent));
            ordersConfirmedEventReceiver.start();
            ordersConfirmedEventReceiver.receiveAsync(buildOrdersConfirmedEventHandler(ordersConfirmedEventReceiver));

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

    private MessageReceiver.MessageHandler buildOrdersConfirmedEventHandler(final PersistentMessageReceiver ordersConfirmedEventReceiver) {
        return (inboundMessage -> {
            try {
                final String inboundTopic = inboundMessage.getDestinationName();
                log.info("Processing message on incoming topic :{} with payload:{}", inboundTopic, inboundMessage.getPayloadAsString());
                boolean eventProcessed = processPaymentForConfirmedOrder(inboundMessage.getPayloadAsString());
                if (eventProcessed) {
                    ordersConfirmedEventReceiver.ack(inboundMessage);
                }
            } catch (RuntimeException runtimeException) {
                log.error("Runtime exception encountered while processing incoming event payload :{} on topic:{}. Error is :", inboundMessage.getPayloadAsString(), inboundMessage.getDestinationName(), runtimeException);
            }
        });
    }

    private boolean processPaymentForConfirmedOrder(final String orderConfirmedEventJson) {
        try {
            final Order orderConfirmed = objectMapper.readValue(orderConfirmedEventJson, Order.class);
            // this would be place where you implement your PSP or Payment Gateway integration
            log.info("Processed payment service integration for Order:{}, customer:{}", orderConfirmed.getId(), orderConfirmed.getCustomerId());
            processPaymentCreatedEventForOrder(orderConfirmed);
            // To emulate payment flows where there is a separate payment initialization and confirmation, we will be publishing a payment updated event with a delay.
            schedulePaymentUpdatedEvent(orderConfirmed);
            return true;
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while processing Order event:{}, exception:", orderConfirmedEventJson, jsonProcessingException);
            return false;
        }
    }

    private void processPaymentCreatedEventForOrder(final Order orderConfirmed) {
        final Payment paymentCreatedEvent = createPaymentCreatedEvent(orderConfirmed);
        publishPaymentEvent(paymentCreatedEvent, orderConfirmed.getDeliveryAddress().getCountry(), EventVerb.created);
    }

    private void processPaymentUpdatedEventForOrder(final Order orderConfirmed) {
        final Payment paymentUpdatedEvent = createPaymentCreatedEvent(orderConfirmed);
        publishPaymentEvent(paymentUpdatedEvent, orderConfirmed.getDeliveryAddress().getCountry(), EventVerb.updated);
    }

    private void schedulePaymentUpdatedEvent(final Order orderConfirmed) {
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.schedule(() -> processPaymentUpdatedEventForOrder(orderConfirmed), 15, TimeUnit.SECONDS);
        executorService.shutdown();
    }


    private Payment createPaymentCreatedEvent(final Order orderConfirmed) {
        return Instancio.of(Payment.class)
                .generate(field(Payment::getId), gen -> gen.ints().asString())
                .set(field(Payment::getOrderId), orderConfirmed.getId())
                .set(field(Payment::getCcy), String.valueOf(orderConfirmed.getPaymentInfo().getCvv()))
                .set(field(Payment::getAmount), orderConfirmed.getPrice())
                .create();
    }

    public void publishPaymentEvent(final Payment paymentEvent, String paymentRegion, EventVerb verb) {
        try {
            String paymentEventJson = objectMapper.writeValueAsString(paymentEvent);
            final OutboundMessage message = messageBuilder.build(paymentEventJson);
            final Map<String, Object> params = new HashMap<>();
            params.put("verb", verb.name());
            params.put("regionId", paymentRegion);
            params.put("paymentId", paymentEvent.getId());
            String topicString = StringSubstitutor.replace(configProperties.getPaymentTopicString(), params, "{", "}");
            persistentMessagePublisher.publish(message, Topic.of(topicString));
            log.info("Published Payment event :{} on topic : {}", paymentEventJson, topicString);
        } catch (final RuntimeException runtimeException) {
            log.error("Error encountered while publishing event, exception :", runtimeException);
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while converting Payment to JSON string, exception :", jsonProcessingException);
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