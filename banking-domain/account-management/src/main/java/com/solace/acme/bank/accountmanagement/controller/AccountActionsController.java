package com.solace.acme.bank.accountmanagement.controller;

import com.solace.acme.bank.accountmanagement.config.SolaceConnectionParameters;
import com.solace.acme.bank.accountmanagement.models.Account;
import com.solace.acme.bank.accountmanagement.models.AccountsList;
import com.solace.acme.bank.accountmanagement.service.AccountService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
@Slf4j
@SessionAttributes({"solaceConnectionParameters", "brokerConnected"})
public class AccountActionsController {

    private AccountService accountService;

    @Autowired
    public void setAccountService(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public String homePage(final Model model) {
        model.addAttribute("appName", "Acme Bank-Account Management");
        if (!model.containsAttribute("solaceConnectionParameters")) {
            model.addAttribute("solaceConnectionParameters", new SolaceConnectionParameters());
        }
        return "home";
    }

    @PostMapping(path = "/connectToBroker")
    public String connectToBroker(@Valid @ModelAttribute("solaceConnectionParameters") SolaceConnectionParameters solaceConnectionParameters, BindingResult bindingResult, final Model model) {
        if (!bindingResult.hasErrors()) {
            boolean brokerConnected = accountService.connectToBroker(solaceConnectionParameters);
            model.addAttribute("brokerConnected", brokerConnected);
        }
        return "home";
    }

    @PostMapping(path = "applyNewAccount")
    public String applyForNewAccount(final Model model) {
        accountService.processAccountApplicationRequest();
        model.addAttribute("accountsList", AccountsList.getInstance().getAccountsList());
        return "home";
    }

    @GetMapping(path = "updateAccountsList", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Account> updateAccountsList() {
        return AccountsList.getInstance().getAccountsList();
    }


}
