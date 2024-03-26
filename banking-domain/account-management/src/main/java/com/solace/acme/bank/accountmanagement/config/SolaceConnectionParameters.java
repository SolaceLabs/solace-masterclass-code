package com.solace.acme.bank.accountmanagement.config;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;


@Data
public class SolaceConnectionParameters {

    @NotEmpty(message = "Host URL cannot be empty")
    private String hostUrl;
    @NotEmpty(message = "VPN name cannot be empty")
    private String vpnName;
    @NotEmpty(message = "Username cannot be empty")
    private String userName;
    @NotEmpty(message = "Password cannot be empty")
    private String password;
}
