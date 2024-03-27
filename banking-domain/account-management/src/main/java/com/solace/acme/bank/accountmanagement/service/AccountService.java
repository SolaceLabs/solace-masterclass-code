package com.solace.acme.bank.accountmanagement.service;

import com.solace.acme.bank.accountmanagement.config.SolaceConnectionParameters;
import com.solace.acme.bank.accountmanagement.models.Account;
import com.solace.acme.bank.accountmanagement.models.AccountAction;
import com.solace.acme.bank.accountmanagement.models.AccountsList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AccountService {

    private SolaceEventPublisher solaceEventPublisher;

    @Autowired
    public void setSolaceEventPublisher(SolaceEventPublisher solaceEventPublisher) {
        this.solaceEventPublisher = solaceEventPublisher;
    }

    public boolean connectToBroker(final SolaceConnectionParameters solaceConnectionParameters) {
        return solaceEventPublisher.connectToBroker(solaceConnectionParameters);
    }


    public void processAccountApplicationRequest() {
        final String newAccountNumber = generateAccountNumber();
        final Account newAccount = Account.builder().accountNumber(newAccountNumber).currentStatus(Account.Status.APPLIED).comment("New account application under processing").build();
        final AccountAction newAccountAppliedAction = createAccountAppliedEventPayload(newAccountNumber);
        solaceEventPublisher.publishAccountAppliedEvent(newAccountAppliedAction);
        AccountsList.getInstance().getAccountsList().put(newAccountNumber, newAccount);
        scheduleAccountOpenedEvent(newAccountNumber);
    }

    private void scheduleAccountOpenedEvent(final String accountNumber) {
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.schedule(() -> processAccountOpening(accountNumber), 15, TimeUnit.SECONDS);
        executorService.shutdown();
    }

    public void processAccountResumedRequest(final String accountNumber) {
        final AccountAction accountResumedAction = createAccountResumedEventPayload(accountNumber);
        solaceEventPublisher.publishAccountResumedEvent(accountResumedAction);
        Account account = AccountsList.getInstance().getAccountsList().get(accountNumber);
        account.setCurrentStatus(Account.Status.RESUMED);
        account.setComment("Account resumed after suspension");
        AccountsList.getInstance().getAccountsList().put(accountNumber, account);
    }

    private AccountAction createAccountResumedEventPayload(final String accountNumber) {
        return AccountAction.builder()
                .accountNum(accountNumber)
                .accountAction(Account.Status.RESUMED.toString())
                .timestamp(generateCurrentTimestamp())
                .build();
    }

    public void processAccountOpening(final String accountNumber) {
        final AccountAction accountOpenedAction = createAccountOpenedEventPayload(accountNumber);
        solaceEventPublisher.publishAccountOpenedEvent(accountOpenedAction);
        Account account = AccountsList.getInstance().getAccountsList().get(accountNumber);
        account.setCurrentStatus(Account.Status.OPENED);
        account.setComment("Account operational");
        AccountsList.getInstance().getAccountsList().put(accountNumber, account);
    }

    private AccountAction createAccountAppliedEventPayload(final String newAccountNumber) {
        return AccountAction.builder()
                .accountNum(newAccountNumber)
                .accountAction(Account.Status.APPLIED.toString())
                .timestamp(generateCurrentTimestamp())
                .build();
    }

    private AccountAction createAccountOpenedEventPayload(final String accountNumber) {
        return AccountAction.builder()
                .accountNum(accountNumber)
                .accountAction(Account.Status.OPENED.toString())
                .timestamp(generateCurrentTimestamp())
                .build();
    }

    private String generateAccountNumber() {
        Random random = new Random();
        long accountNumber = (long) (Math.pow(10, 9) * (1 + random.nextInt(9)) + random.nextInt((int) Math.pow(10, 9)));
        return String.valueOf(accountNumber);
    }


    private String generateCurrentTimestamp() {
        LocalDateTime currentTimestamp = LocalDateTime.now();
        String pattern = "yyyy-MM-dd'T'HH:mm:ss";
        return currentTimestamp.format(DateTimeFormatter.ofPattern(pattern));
    }
}
