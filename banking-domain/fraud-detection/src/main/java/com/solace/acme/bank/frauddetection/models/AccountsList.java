package com.solace.acme.bank.frauddetection.models;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Data
@Getter
@Setter
public class AccountsList {

    private static volatile AccountsList accountsListInstance;
    private Map<String, Account> accountsList;

    private AccountsList() {
        // private constructor to prevent instantiation
    }

    public static AccountsList getInstance() {
        if (accountsListInstance == null) {
            synchronized (AccountsList.class) {
                if (accountsListInstance == null) {
                    accountsListInstance = new AccountsList(); // Initialize on first access
                    accountsListInstance.accountsList = new HashMap<>();
                }
            }
        }
        return accountsListInstance;
    }
}
