package com.solace.acme.store.orderservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.acme.store.orderservice.config.SolaceConfigProperties;
import com.solace.acme.store.orderservice.config.SolaceConnectionParameters;
import com.solace.acme.store.orderservice.model.Order;
import com.solace.acme.store.orderservice.model.OrderCache;
import com.solace.acme.store.orderservice.model.Payment;
import com.solace.acme.store.orderservice.model.Shipping;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Slf4j
@Component
public class SolaceEventPublisher {

    private SolaceConfigProperties configProperties;
    private PersistentMessagePublisher publisher;
    private OutboundMessageBuilder messageBuilder;
    private MessagingService messagingService;
    ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public void setConfigProperties(SolaceConfigProperties configProperties) {
        this.configProperties = configProperties;
    }

    public boolean connectToBroker(final SolaceConnectionParameters solaceConnectionParameters) {
        try {
            final Properties properties = setupPropertiesForConnection(solaceConnectionParameters);
            messagingService = MessagingService.builder(ConfigurationProfile.V1).fromProperties(properties).build();
            messagingService.connect(); // This is a blocking connect action
            setupConnectivityHandlingInMessagingService(messagingService);
            publisher = messagingService.createPersistentMessagePublisherBuilder()
                    .onBackPressureWait(1)
                    .build();
            publisher.start();
            messageBuilder = messagingService.messageBuilder();
            publisher.setMessagePublishReceiptListener(publishReceipt -> {
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

            final PersistentMessageReceiver orderUpdatesEventReceiver = messagingService.createPersistentMessageReceiverBuilder().build(Queue.durableExclusiveQueue(configProperties.getOrderUpdatesQueueName()));
            orderUpdatesEventReceiver.setReceiveFailureListener(failedReceiveEvent -> System.out.println("### FAILED RECEIVE EVENT " + failedReceiveEvent));
            orderUpdatesEventReceiver.start();
            orderUpdatesEventReceiver.receiveAsync(buildOrdersUpdatesEventHandler(orderUpdatesEventReceiver));


            return true;
        } catch (Exception exception) {
            log.error("Error encountered while connecting to the Solace broker, error :{}", exception.getMessage());
            return false;
        }
    }

    private MessageReceiver.MessageHandler buildOrdersUpdatesEventHandler(final PersistentMessageReceiver orderUpdatesEventReceiver) {
        return (inboundMessage -> {
          try {
              final String inboundTopic = inboundMessage.getDestinationName();
              log.info("Processing message on incoming topic :{} with payload:{}", inboundTopic, inboundMessage.getPayloadAsString());
              boolean eventProcessed = processOrderUpdate(inboundTopic, inboundMessage.getPayloadAsString());
              if (eventProcessed) {
                orderUpdatesEventReceiver.ack(inboundMessage);
              }
          } catch (RuntimeException runtimeException) {
            log.error("Runtime exception encountered while processing incoming event payload :{} on topic:{}. Error is :", inboundMessage.getPayloadAsString(), inboundMessage.getDestinationName(), runtimeException);
          }
        });
      }

      private boolean processOrderUpdate(final String eventTopic, final String eventJson) {
        try {
            if (eventTopic.contains("order")) {
                final Order order = objectMapper.readValue(eventJson, Order.class);
                final String incomingOrderId = order.getId();
                Order orderObjectFromCache = OrderCache.getInstance().getOrderMap().get(incomingOrderId);
                orderObjectFromCache.setState(Order.OrderState.VALIDATED);
                OrderCache.getInstance().getOrderMap().put(incomingOrderId, orderObjectFromCache);
            } else if (eventTopic.contains("payment")) {
                final Payment payment = objectMapper.readValue(eventJson, Payment.class);
                final String incomingOrderId = payment.getOrderId();
                Order orderObjectFromCache = OrderCache.getInstance().getOrderMap().get(incomingOrderId);
                orderObjectFromCache.setState(Order.OrderState.PAYMENT_PROCESSED);
                OrderCache.getInstance().getOrderMap().put(incomingOrderId, orderObjectFromCache);
            } else if (eventTopic.contains("shipment")) {
                final Shipping shipment = objectMapper.readValue(eventJson, Shipping.class);
                final String incomingOrderId = shipment.getOrderId();
                Order orderObjectFromCache = OrderCache.getInstance().getOrderMap().get(incomingOrderId);
                orderObjectFromCache.setState(Order.OrderState.SHIPPED);
                OrderCache.getInstance().getOrderMap().put(incomingOrderId, orderObjectFromCache);
            }
            return true;
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while processing event:{}, exception:", eventJson, jsonProcessingException);
            return false;
        }
    }

    private Properties setupPropertiesForConnection(final SolaceConnectionParameters solaceConnectionParameters) {
        final Properties properties = new Properties();
        properties.setProperty(SolaceProperties.TransportLayerProperties.HOST, solaceConnectionParameters.getHostUrl()); // host:port
        properties.setProperty(SolaceProperties.ServiceProperties.VPN_NAME, solaceConnectionParameters.getVpnName()); // message-vpn
        properties.setProperty(SolaceProperties.AuthenticationProperties.SCHEME_BASIC_USER_NAME,
                solaceConnectionParameters.getUserName());
        properties.setProperty(SolaceProperties.AuthenticationProperties.SCHEME_BASIC_PASSWORD,
                solaceConnectionParameters.getPassword());
        properties.setProperty(SolaceProperties.TransportLayerProperties.RECONNECTION_ATTEMPTS,
                configProperties.getReconnectionAttempts());
        properties.setProperty(SolaceProperties.TransportLayerProperties.CONNECTION_RETRIES_PER_HOST,
                configProperties.getConnectionRetriesPerHost());
        return properties;
    }

    private static void setupConnectivityHandlingInMessagingService(final MessagingService messagingService) {
        messagingService.addServiceInterruptionListener(
                serviceEvent -> System.out.println("### SERVICE INTERRUPTION: " + serviceEvent.getCause()));
        messagingService.addReconnectionAttemptListener(
                serviceEvent -> System.out.println("### RECONNECTING ATTEMPT: " + serviceEvent));
        messagingService
                .addReconnectionListener(serviceEvent -> System.out.println("### RECONNECTED: " + serviceEvent));
    }

    public void publishOrderCreatedEvent(final Order orderCreatedEvent) {
        try {
            final String orderCreatedEventJson = objectMapper.writeValueAsString(orderCreatedEvent);
            final OutboundMessage message = messageBuilder.build(orderCreatedEventJson);
            final Map<String, Object> params = new HashMap<>();
            params.put("regionId", orderCreatedEvent.getDeliveryAddress().getCountry());
            params.put("orderId", orderCreatedEvent.getId());
            String topicString = StringSubstitutor.replace(configProperties.getOrderCreatedEventTopicString(), params, "{", "}");
            publisher.publish(message, Topic.of(topicString));
            log.info("Published OrderCreated event :{} on topic : {}", orderCreatedEventJson, topicString);
        } catch (final RuntimeException runtimeException) {
            log.error("Error encountered while publishing event, exception :", runtimeException);
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while converting orderCreatedEventJson to JSON string, exception :", jsonProcessingException);
        }
    }

    @PreDestroy
    public void houseKeepingOnBeanDestroy() {
        log.info("The bean is getting destroyed, doing housekeeping activities");
        publisher.terminate(1000);
        messagingService.disconnect();
    }

}
