package com.solace.acme.bank.frauddetection.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.acme.bank.frauddetection.config.SolaceConfigProperties;
import com.solace.acme.bank.frauddetection.config.SolaceConnectionParameters;
import com.solace.acme.bank.frauddetection.models.FraudDetected;
import com.solace.acme.bank.frauddetection.models.Transaction;
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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import static org.instancio.Select.field;

@Service
@Slf4j
public class BankingTransactionEventProcessor {


    @Autowired
    private SolaceConfigProperties configProperties;
    private PersistentMessagePublisher persistentMessagePublisher;
    private OutboundMessageBuilder messageBuilder;
    ObjectMapper objectMapper = new ObjectMapper();
    private Random random = new Random();


    private static final double FRAUD_PROBABILITY = 0.05; // 5% probability of fraud

    public boolean connectAndConfigureConsumers(final SolaceConnectionParameters solaceConnectionParameters) {
        try {
            final Properties properties = setupPropertiesForConnection(solaceConnectionParameters);
            final MessagingService messagingService = MessagingService.builder(ConfigurationProfile.V1).fromProperties(properties).build();
            messagingService.connect();
            setupConnectivityHandlingInMessagingService(messagingService);
            messageBuilder = messagingService.messageBuilder();
            persistentMessagePublisher = messagingService.createPersistentMessagePublisherBuilder().onBackPressureWait(1).build();
            persistentMessagePublisher.start();

            final PersistentMessageReceiver bankingTransactionsEventReceiver = messagingService.createPersistentMessageReceiverBuilder().build(Queue.durableExclusiveQueue(configProperties.getBankingTransactionsQueueName()));
            bankingTransactionsEventReceiver.setReceiveFailureListener(failedReceiveEvent -> System.out.println("### FAILED RECEIVE EVENT " + failedReceiveEvent));
            bankingTransactionsEventReceiver.start();
            bankingTransactionsEventReceiver.receiveAsync(buildBankingTransactionsEventHandler(bankingTransactionsEventReceiver));

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

    public boolean performFraudCheck(String incomingTransactionJson) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            final Transaction transactionEvent = objectMapper.readValue(incomingTransactionJson, Transaction.class);
            boolean isFraud = random.nextBoolean();
            if (isFraud) {
                log.info("Transaction flagged as fraud: {}", transactionEvent);
                createAndPublishFraudDetectedEvent(transactionEvent);
            }
            return true;
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while processing Transaction event:{}, exception:", incomingTransactionJson, jsonProcessingException);
            return false;
        }
    }

    private void createAndPublishFraudDetectedEvent(final Transaction transaction) {
        final FraudDetected fraudDetected = createFraudDetectedInstance(transaction);
        publishFraudDetectedEvent(fraudDetected);
    }

    private FraudDetected createFraudDetectedInstance(final Transaction transactionEvent) {
        return Instancio.of(FraudDetected.class)
                .generate(field(FraudDetected::getDetectionNum), gen -> gen.ints())
                .set(field(FraudDetected::getTransactionNum), transactionEvent.getTransactionNum())
                .set(field(FraudDetected::getAccountNum), transactionEvent.getAccountNum())
                .set(field(FraudDetected::getTransactionType), transactionEvent.getTransactionType())
                .set(field(FraudDetected::getAmount), transactionEvent.getAmount())
                .set(field(FraudDetected::getCurrency), transactionEvent.getCurrency())
                .set(field(FraudDetected::getIncidentDescription), "Potential fraudulent/suspicious transaction")
                .set(field(FraudDetected::getIncidentTimestamp), transactionEvent.getTimestamp())
                .set(field(FraudDetected::getTimestamp), generateCurrentTimestamp())
                .create();
    }

    private String generateCurrentTimestamp() {
        LocalDateTime currentTimestamp = LocalDateTime.now();
        String pattern = "yyyy-MM-dd'T'HH:mm:ss";
        return currentTimestamp.format(DateTimeFormatter.ofPattern(pattern));
    }


    private MessageReceiver.MessageHandler buildBankingTransactionsEventHandler(PersistentMessageReceiver bankingTransactionsEventReceiver) {
        return (inboundMessage -> {
            try {
                final String inboundTopic = inboundMessage.getDestinationName();
                log.info("Processing message on incoming topic :{} with payload:{}", inboundTopic, inboundMessage.getPayloadAsString());
                boolean eventProcessed = performFraudCheck(inboundMessage.getPayloadAsString());
                if (eventProcessed) {
                    bankingTransactionsEventReceiver.ack(inboundMessage);
                }
            } catch (RuntimeException runtimeException) {
                log.error("Runtime exception encountered while processing incoming event payload :{} on topic:{}. Error is :", inboundMessage.getPayloadAsString(), inboundMessage.getDestinationName(), runtimeException);
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

    public void publishFraudDetectedEvent(final FraudDetected fraudDetected) {
        try {
            String fraudDetectedJson = objectMapper.writeValueAsString(fraudDetected);
            final OutboundMessage message = messageBuilder.build(fraudDetectedJson);
            final Map<String, Object> params = new HashMap<>();
            params.put("accountID", fraudDetected.getAccountNum());
            params.put("transactionID", fraudDetected.getTransactionNum());
            params.put("amount", fraudDetected.getAmount());
            String topicString = StringSubstitutor.replace(configProperties.getFraudDetectedEventTopicString(), params, "{", "}");
            persistentMessagePublisher.publish(message, Topic.of(topicString));
            log.info("Published Fraud Detected event :{} on topic : {}", fraudDetectedJson, topicString);
        } catch (final RuntimeException runtimeException) {
            log.error("Error encountered while publishing event, exception :", runtimeException);
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Error encountered while converting Fraud Detected to JSON string, exception :", jsonProcessingException);
        }
    }
}
