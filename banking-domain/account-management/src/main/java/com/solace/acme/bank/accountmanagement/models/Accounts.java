package com.solace.acme.bank.accountmanagement.models;


import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Data
public class Accounts {

    private String accountNumber;

    public enum Status {
        APPLIED,
        CREATED,
        ACTIVE,
        SUSPENDED
    }

    private String comment;
}
