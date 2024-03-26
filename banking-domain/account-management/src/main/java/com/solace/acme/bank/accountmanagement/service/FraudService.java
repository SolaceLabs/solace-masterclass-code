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
    private String generateCurrentTimestamp() {
        LocalDateTime currentTimestamp = LocalDateTime.now();
        String pattern = "yyyy-MM-dd'T'HH:mm:ss";
        return currentTimestamp.format(DateTimeFormatter.ofPattern(pattern));
    }
}
