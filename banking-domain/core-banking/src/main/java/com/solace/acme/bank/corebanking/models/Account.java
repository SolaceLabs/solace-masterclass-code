package com.solace.acme.bank.corebanking.models;


import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Data
@Builder
public class Account {

    private String accountNumber;
    private Status currentStatus;

    public enum Status {
        APPLIED,
        OPENED,
        ACTIVE,
        SUSPENDED,
        RESUMED
    }

    private String comment;
}
