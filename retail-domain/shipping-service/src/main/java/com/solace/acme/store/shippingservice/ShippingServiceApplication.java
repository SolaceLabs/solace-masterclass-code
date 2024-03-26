package com.solace.acme.store.shippingservice;

import com.solace.acme.store.shippingservice.config.SolaceConnectionParameters;
import com.solace.acme.store.shippingservice.service.SolaceEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Slf4j
public class ShippingServiceApplication  implements CommandLineRunner {
    @Autowired
    private SolaceEventHandler solaceEventHandler;
    public static void main(String[] args) {
        log.info("Starting the Acme Store Shipping-service application");
        SpringApplication.run(ShippingServiceApplication.class, args);
    }
    @Override
    public void run(String... args) throws Exception {
        // Parse command-line arguments using Apache Commons CLI
        Options options = new Options();
        options.addOption("h", "host", true, "Solace broker host");
        options.addOption("v", "vpnName", true, "Solace VPN name");
        options.addOption("u", "userName", true, "Solace username");
        options.addOption("p", "password", true, "Solace password");

        CommandLineParser parser = new DefaultParser();
        CommandLine line = parser.parse(options, args);

        final String host = line.getOptionValue("h");
        final String vpnName = line.getOptionValue("v");
        final String userName = line.getOptionValue("u");
        final String password = line.getOptionValue("p");

        log.info("host:{}", host);
        log.info("vpnName:{}", vpnName);
        log.info("userName:{}", userName);
        log.info("password:{}", password);

        boolean isBrokerConnected = solaceEventHandler.connectAndConfigureConsumers(SolaceConnectionParameters.builder().hostUrl(host).password(password).userName(userName).vpnName(vpnName).build());
        if (isBrokerConnected) log.info("The Acme Store Shipping-service application is successfully started");
        waitForShutdown();
    }

    private void waitForShutdown() {
        synchronized (this) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Error occurred while waiting for shutdown", e);
            }
        }
    }
}
