package com.solace.acme.bank.corebanking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.acme.bank.corebanking.models.Account;
import com.solace.acme.bank.corebanking.models.AccountAction;
import com.solace.acme.bank.corebanking.models.AccountsList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AccountsEventProcessor {

    ObjectMapper objectMapper = new ObjectMapper();
}
