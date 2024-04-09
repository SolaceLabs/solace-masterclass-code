package com.solace.acme.bank.accountmanagement.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.acme.bank.accountmanagement.config.SolaceConfigProperties;
import com.solace.acme.bank.accountmanagement.config.SolaceConnectionParameters;
import com.solace.acme.bank.accountmanagement.models.AccountAction;
import com.solace.acme.bank.accountmanagement.models.FraudConfirmed;
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
    private FraudService fraudService;
    private PersistentMessagePublisher publisher;
    private OutboundMessageBuilder messageBuilder;
    private MessagingService messagingService;
    ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public void setConfigProperties(SolaceConfigProperties configProperties) {
        this.configProperties = configProperties;
    }

    @Autowired
    public void setFraudService(FraudService fraudService) {
        this.fraudService = fraudService;
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


            final PersistentMessageReceiver fraudDetectedEventReceiver = messagingService.createPersistentMessageReceiverBuilder().build(Queue.durableExclusiveQueue(configProperties.getSolaceFraudDetectedEventQueue()));
            fraudDetectedEventReceiver.setReceiveFailureListener(failedReceiveEvent -> log.error("### FAILED RECEIVE EVENT " + failedReceiveEvent));
            fraudDetectedEventReceiver.start();
            fraudDetectedEventReceiver.receiveAsync(buildFraudDetectedEventHandler(fraudDetectedEventReceiver));

            return true;
        } catch (Exception exception) {
            log.error("Error encountered while connecting to the Solace broker, error :{}", exception.getMessage());
            return false;
        }
    }

    private MessageReceiver.MessageHandler buildFraudDetectedEventHandler(PersistentMessageReceiver fraudDetectedEventReceiver) {
        return (inboundMessage -> {
            try {
                final String inboundTopic = inboundMessage.getDestinationName();
                log.info("Processing message on incoming topic :{} with payload:{}", inboundTopic, inboundMessage.getPayloadAsString());
                boolean eventProcessed = fraudService.processFraudDetectedEvent(inboundMessage.getPayloadAsString());
                if (eventProcessed) {
                    fraudDetectedEventReceiver.ack(inboundMessage);
                }
            } catch (RuntimeException runtimeException) {
                log.error("Runtime exception encountered while processing incoming event payload :{} on topic:{}. Error is :", inboundMessage.getPayloadAsString(), inboundMessage.getDestinationName(), runtimeException);
            }
        });
    }

    
    public void publishFraudConfirmedEvent(final FraudConfirmed fraudConfirmed) {
                  try {
                      String fraudConfirmedJson = objectMapper.writeValueAsString(fraudConfirmed);
                      final OutboundMessage message = messageBuilder.build(fraudConfirmedJson);
                      final Map<String, Object> params = new HashMap<>();
                      params.put("accountID", fraudConfirmed.getAccountNum());
                      params.put("transactionID", fraudConfirmed.getTransactionNum());
                      params.put("amount", fraudConfirmed.getAmount());
                      String topicString = StringSubstitutor.replace(configProperties.getSolaceFraudConfirmedTopic(), params, "{", "}");
                      publisher.publish(message, Topic.of(topicString));
                      log.info("Published FraudConfirmed event :{} on topic : {}", fraudConfirmedJson, topicString);
                  } catch (final RuntimeException runtimeException) {
                      log.error("Error encountered while publishing event, exception :", runtimeException);
                  } catch (JsonProcessingException jsonProcessingException) {
                      log.error("Error encountered while converting fraudConfirmed to JSON string, exception :", jsonProcessingException);
                  }
                }
    public void publishAccountSuspendedEvent(final AccountAction accountSuspendedAction) {
                try {
                    String accountSuspendedActionJson = objectMapper.writeValueAsString(accountSuspendedAction);
                    final OutboundMessage message = messageBuilder.build(accountSuspendedActionJson);
                    final Map<String, Object> params = new HashMap<>();
                    params.put("accountID", accountSuspendedAction.getAccountNum());
                    String topicString = StringSubstitutor.replace(configProperties.getSolaceAccountSuspendedTopic(), params, "{", "}");
                    publisher.publish(message, Topic.of(topicString));
                    log.info("Published AccountSuspended event :{} on topic : {}", accountSuspendedActionJson, topicString);
                } catch (final RuntimeException runtimeException) {
                    log.error("Error encountered while publishing event, exception :", runtimeException);
                } catch (JsonProcessingException jsonProcessingException) {
                    log.error("Error encountered while converting accountSuspendedActionEvent to JSON string, exception :", jsonProcessingException);
                }
            }

    private static void setupConnectivityHandlingInMessagingService(final MessagingService messagingService) {
        messagingService.addServiceInterruptionListener(
                serviceEvent -> System.out.println("### SERVICE INTERRUPTION: " + serviceEvent.getCause()));
        messagingService.addReconnectionAttemptListener(
                serviceEvent -> System.out.println("### RECONNECTING ATTEMPT: " + serviceEvent));
        messagingService
                .addReconnectionListener(serviceEvent -> System.out.println("### RECONNECTED: " + serviceEvent));
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


    public void publishAccountAppliedEvent(final AccountAction accountAppliedAction) {
        try {
            String accountAppliedActionJson = objectMapper.writeValueAsString(accountAppliedAction);
            final OutboundMessage message = messageBuilder.build(accountAppliedActionJson);
            final Map<String, Object> params = new HashMap<>();
            params.put("accountID", accountAppliedAction.getAccountNum());
            String topicString = StringSubstitutor.replace(configProperties.getSolaceAccountAppliedTopic(), params, "{", "}");
            publisher.publish(message, Topic.of(topicString));
            log.info("Published AccountApplied event :{} on topic : {}", accountAppliedActionJson, topicString);
        } catch (final RuntimeException runtimeException) {
            log.error("Error encountered while publishing event, exception :", runtimeException);
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while converting accountAppliedActionEvent to JSON string, exception :", jsonProcessingException);
        }
    }


    public void publishAccountOpenedEvent(final AccountAction accountOpenedAction) {
        try {
            String accountOpenedActionJson = objectMapper.writeValueAsString(accountOpenedAction);
            final OutboundMessage message = messageBuilder.build(accountOpenedActionJson);
            final Map<String, Object> params = new HashMap<>();
            params.put("accountID", accountOpenedAction.getAccountNum());
            String topicString = StringSubstitutor.replace(configProperties.getSolaceAccountOpenedTopic(), params, "{", "}");
            publisher.publish(message, Topic.of(topicString));
            log.info("Published AccountOpened event :{} on topic : {}", accountOpenedActionJson, topicString);
        } catch (final RuntimeException runtimeException) {
            log.error("Error encountered while publishing event, exception :", runtimeException);
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while converting accountOpenedActionEvent to JSON string, exception :", jsonProcessingException);
        }
    }

    public void publishAccountResumedEvent(final AccountAction accountResumedAction) {
        try {
            String accountResumedActionJson = objectMapper.writeValueAsString(accountResumedAction);
            final OutboundMessage message = messageBuilder.build(accountResumedActionJson);
            final Map<String, Object> params = new HashMap<>();
            params.put("accountID", accountResumedAction.getAccountNum());
            String topicString = StringSubstitutor.replace(configProperties.getSolaceAccountResumedTopic(), params, "{", "}");
            publisher.publish(message, Topic.of(topicString));
            log.info("Published AccountResumed event :{} on topic : {}", accountResumedActionJson, topicString);
        } catch (final RuntimeException runtimeException) {
            log.error("Error encountered while publishing event, exception :", runtimeException);
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while converting accountResumedActionEvent to JSON string, exception :", jsonProcessingException);
        }
    }

    @PreDestroy
    public void houseKeepingOnBeanDestroy() {
        log.info("The bean is getting destroyed, doing housekeeping activities");
        publisher.terminate(1000);
        messagingService.disconnect();
    }

}
