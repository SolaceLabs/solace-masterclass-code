package com.solace.acme.store.inventoryfraudcheckservice.config;

import lombok.Builder;
import lombok.Data;


@Data
@Builder
public class SolaceConnectionParameters {
    private String hostUrl;
    private String vpnName;
    private String userName;
    private String password;
}
