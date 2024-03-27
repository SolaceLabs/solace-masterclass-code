package com.solace.acme.store.orderservice.controller;

import com.solace.acme.store.orderservice.config.SolaceConnectionParameters;
import com.solace.acme.store.orderservice.model.Order;
import com.solace.acme.store.orderservice.model.OrderCache;
import com.solace.acme.store.orderservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
@Slf4j
@SessionAttributes({"solaceConnectionParameters", "brokerConnected"})
public class OrderController {

    private final OrderService orderService;

    public OrderController(final OrderService orderService) {
        this.orderService = orderService;
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
            boolean brokerConnected = orderService.connectToBroker(solaceConnectionParameters);
            model.addAttribute("brokerConnected", brokerConnected);
        }
        return "home";
    }

    @PostMapping(path = "/createNewBasket")
    public String createNewBasket(final Model model) {
        orderService.createBasket();
        model.addAttribute("orderMap", OrderCache.getInstance().getOrderMap());
        return "home";
    }

    @GetMapping(path = "updateOrderList", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Order> updateAccountsList() {
        return OrderCache.getInstance().getOrderMap();
    }

}
