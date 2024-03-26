package com.solace.acme.bank.accountmanagement.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountAction {
    private String accountNum;
    private String accountAction;
    private String timestamp;
}
