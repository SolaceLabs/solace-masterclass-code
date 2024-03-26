package com.solace.acme.bank.corebanking.service;

import com.solace.acme.bank.corebanking.models.Account;
import com.solace.acme.bank.corebanking.models.AccountsList;
import com.solace.acme.bank.corebanking.models.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.instancio.Instancio;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.instancio.Select.field;

@Service
@Slf4j
public class TransactionEventScheduler {

    @Autowired
    private SolaceEventHandler solaceEventHandler;

    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    public void simulateTransactionsForAccounts() {
        AccountsList.getInstance().getAccountsList().entrySet().stream()
                .filter(entry -> {
                    Account.Status status = entry.getValue().getCurrentStatus();
                    return status == Account.Status.OPENED ||
                            status == Account.Status.ACTIVE ||
                            status == Account.Status.RESUMED;
                })
                .forEach(entry -> {
                    Account account = entry.getValue();
                    log.info("Processing account :{} with status:{} ", account.getAccountNumber(), account.getCurrentStatus());

                    final Transaction transactionForAccount = generateRandomTransactionForAccount(account.getAccountNumber());
                    solaceEventHandler.publishTransactionEvent(transactionForAccount);
                });
    }

    private Transaction generateRandomTransactionForAccount(final String accountNumber) {
        return Instancio.of(Transaction.class)
                .set(field(Transaction::getAccountNum), accountNumber)
                .generate(field(Transaction::getTransactionNum), gen -> gen.ints())
                .generate(field(Transaction::getTransactionType), gen -> gen.oneOf("DEPOSIT", "TRANSFER", "WITHDRAWAL"))
                .set(field(Transaction::getAmount), Math.round((Math.random() * 100) * 100.0) / 100.0)
                .set(field(Transaction::getCurrency), "Euro")
                .set(field(Transaction::getTimestamp), generateCurrentTimestamp())
                .create();
    }

    private String generateCurrentTimestamp() {
        LocalDateTime currentTimestamp = LocalDateTime.now();
        String pattern = "yyyy-MM-dd'T'HH:mm:ss";
        return currentTimestamp.format(DateTimeFormatter.ofPattern(pattern));
    }
}
