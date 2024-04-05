package com.solace.acme.bank.accountmanagement.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.acme.bank.accountmanagement.models.FraudConfirmed;
import com.solace.acme.bank.accountmanagement.models.FraudDetected;
import lombok.extern.slf4j.Slf4j;
import org.instancio.Instancio;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

import static org.instancio.Select.field;

@Service
@Slf4j
public class FraudService {

    ObjectMapper objectMapper = new ObjectMapper();
    private Random random = new Random();

    private SolaceEventPublisher solaceEventPublisher;
    private AccountService accountService;

    @Autowired
    public void setSolaceEventPublisher(SolaceEventPublisher solaceEventPublisher) {
        this.solaceEventPublisher = solaceEventPublisher;
    }

    @Autowired
    public void setAccountService(AccountService accountService) {
        this.accountService = accountService;
    }

    public FraudService(final SolaceEventPublisher solaceEventPublisher, final AccountService accountService) {
        this.accountService = accountService;
        this.solaceEventPublisher = solaceEventPublisher;
    }




    private void createAndPublishFraudConfirmedEvent(final FraudDetected fraudDetected) {
                final FraudConfirmed fraudConfirmed = createFraudConfirmedInstance(fraudDetected);
                solaceEventPublisher.publishFraudConfirmedEvent(fraudConfirmed);
              }

    private FraudConfirmed createFraudConfirmedInstance(final FraudDetected fraudDetected) {
              return Instancio.of(FraudConfirmed.class)
              .generate(field(FraudConfirmed::getDetectionNum), gen -> gen.ints())
              .set(field(FraudConfirmed::getTransactionNum), fraudDetected.getTransactionNum())
              .set(field(FraudConfirmed::getAccountNum), fraudDetected.getAccountNum())
              .set(field(FraudConfirmed::getTransactionType), fraudDetected.getTransactionType())
              .set(field(FraudConfirmed::getAmount), fraudDetected.getAmount())
              .set(field(FraudConfirmed::getCurrency), fraudDetected.getCurrency())
              .set(field(FraudConfirmed::getIncidentDescription), "Confirmed fraudulent transaction")
              .generate(field(FraudConfirmed::getFraudConfirmedBy), gen -> gen.oneOf("John Doe", "Jane Doe", "Comptroller"))
              .set(field(FraudConfirmed::getIncidentTimestamp), fraudDetected.getTimestamp())
              .set(field(FraudConfirmed::getTimestamp), generateCurrentTimestamp())
              .create();
              }
    private String generateCurrentTimestamp() {
        LocalDateTime currentTimestamp = LocalDateTime.now();
        String pattern = "yyyy-MM-dd'T'HH:mm:ss";
        return currentTimestamp.format(DateTimeFormatter.ofPattern(pattern));
    }
}
