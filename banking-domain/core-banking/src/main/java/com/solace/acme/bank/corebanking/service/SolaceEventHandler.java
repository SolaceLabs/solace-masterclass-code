package com.solace.acme.bank.corebanking.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.acme.bank.corebanking.config.SolaceConfigProperties;
import com.solace.acme.bank.corebanking.config.SolaceConnectionParameters;
import com.solace.acme.bank.corebanking.models.Transaction;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Slf4j
@Component
public class SolaceEventHandler {

    @Autowired
    private SolaceConfigProperties configProperties;
    @Autowired
    private AccountsEventProcessor accountsEventProcessor;
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
            final PersistentMessageReceiver accountOpenedEventReceiver = messagingService.createPersistentMessageReceiverBuilder().build(Queue.durableExclusiveQueue(configProperties.getAccountsOpenedQueueName()));
            accountOpenedEventReceiver.setReceiveFailureListener(failedReceiveEvent -> System.out.println("### FAILED RECEIVE EVENT " + failedReceiveEvent));
            accountOpenedEventReceiver.start();
            accountOpenedEventReceiver.receiveAsync(buildAccountsOpenedEventHandler(accountOpenedEventReceiver));

            // code in here for receiving Account Suspended events

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

    private MessageReceiver.MessageHandler buildAccountsOpenedEventHandler(PersistentMessageReceiver accountOpenedEventReceiver) {
        return (inboundMessage -> {
            try {
                final String inboundTopic = inboundMessage.getDestinationName();
                log.info("Processing message on incoming topic :{} with payload:{}", inboundTopic, inboundMessage.getPayloadAsString());
                boolean eventProcessed = accountsEventProcessor.processAccountOpenedEvent(inboundMessage.getPayloadAsString());
                if (eventProcessed) {
                    accountOpenedEventReceiver.ack(inboundMessage);
                }
            } catch (RuntimeException runtimeException) {  // threw from send(), only thing that is throwing here
                log.error("Runtime exception encountered while processing incoming event payload :{} on topic:{}. Error is :",
                        inboundMessage.getPayloadAsString(), inboundMessage.getDestinationName(), runtimeException);
            }
        });
    }

    private static void setupConnectivityHandlingInMessagingService(final MessagingService messagingService) {
        messagingService.addServiceInterruptionListener(serviceEvent -> System.out.println("### SERVICE INTERRUPTION: " + serviceEvent.getCause()));
        messagingService.addReconnectionAttemptListener(serviceEvent -> System.out.println("### RECONNECTING ATTEMPT: " + serviceEvent));
        messagingService.addReconnectionListener(serviceEvent -> System.out.println("### RECONNECTED: " + serviceEvent));
    }

    private Properties setupPropertiesForConnection(final SolaceConnectionParameters solaceConnectionParameters) {
        final Properties properties = new Properties();
        properties.setProperty(SolaceProperties.TransportLayerProperties.HOST, solaceConnectionParameters.getHostUrl()); // host:port
        properties.setProperty(SolaceProperties.ServiceProperties.VPN_NAME, solaceConnectionParameters.getVpnName()); // message-vpn
        properties.setProperty(SolaceProperties.AuthenticationProperties.SCHEME_BASIC_USER_NAME, solaceConnectionParameters.getUserName());
        properties.setProperty(SolaceProperties.AuthenticationProperties.SCHEME_BASIC_PASSWORD, solaceConnectionParameters.getPassword());
        properties.setProperty(SolaceProperties.TransportLayerProperties.RECONNECTION_ATTEMPTS, configProperties.getReconnectionAttempts());
        properties.setProperty(SolaceProperties.TransportLayerProperties.CONNECTION_RETRIES_PER_HOST, configProperties.getConnectionRetriesPerHost());
        return properties;
    }


    public void publishTransactionEvent(final Transaction transaction) {
        try {
            String transactionJson = objectMapper.writeValueAsString(transaction);
            final OutboundMessage message = messageBuilder.build(transactionJson);
            final Map<String, Object> params = new HashMap<>();
            params.put("currency", transaction.getCurrency());
            params.put("amount", transaction.getAmount());
            params.put("transactionID", transaction.getTransactionNum());
            params.put("transactionType", transaction.getTransactionType().toLowerCase());
            String topicString = StringSubstitutor.replace(configProperties.getTransactionEventTopicString(), params, "{", "}");
            persistentMessagePublisher.publish(message, Topic.of(topicString));
            log.info("Published Transaction event :{} on topic : {}", transactionJson, topicString);
        } catch (final RuntimeException runtimeException) {
            log.error("Error encountered while publishing event, exception :", runtimeException);
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while converting Transaction to JSON string, exception :", jsonProcessingException);
        }
    }
}
