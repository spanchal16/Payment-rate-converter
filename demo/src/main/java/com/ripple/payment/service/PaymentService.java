package com.ripple.payment.service;

import com.ripple.payment.dto.PaymentRequestDto;
import com.ripple.payment.entity.Payment;
import com.ripple.payment.exception.PaymentException;
import com.ripple.payment.repository.PaymentRepository;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Service
public class PaymentService {

    private final PaymentRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final String FX_SERVICE_URL = "http://localhost:4000/twirp/payments.v1.FXService/GetQuote";

    public PaymentService(PaymentRepository repository) {
        this.repository = repository;
    }

    public Payment createPayment(PaymentRequestDto request) {

        if (request.getSourceCurrency().equals(request.getDestinationCurrency())) {
            throw new PaymentException("Source and destination currency must be different");
        }

        Payment payment = new Payment();
        payment.setSender(request.getSender());
        payment.setReceiver(request.getReceiver());
        payment.setAmount(request.getAmount());
        payment.setSourceCurrency(request.getSourceCurrency());
        payment.setDestinationCurrency(request.getDestinationCurrency());
        payment.setStatus("PENDING");
        payment.setCreatedAt(Instant.now());
        payment = repository.save(payment);

        try {
            BigDecimal rate = getFxRate(request.getSourceCurrency(), request.getDestinationCurrency());
            BigDecimal amount = request.getAmount();
            BigDecimal payout = amount.multiply(rate);
            payout = payout.setScale(4, RoundingMode.HALF_UP);

            payment.setExchangeRate(rate);
            payment.setPayoutAmount(payout);
            payment.setStatus("SUCCEEDED");

        } catch (Exception e) {
            payment.setStatus("FAILED");
            payment.setErrorMessage(e.getMessage());
        }

        return repository.save(payment);
    }
    private BigDecimal getFxRate(String sourceCurrency, String destinationCurrency) {

        // Build request body
        Map<String, String> body = new HashMap<>();
        body.put("source_currency", sourceCurrency);
        body.put("target_currency", destinationCurrency);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> requestBody = new HttpEntity<>(body, headers);

        Exception lastError = null;
        for (int i = 1; i <= 3; i++) {
            try {
                ResponseEntity<Map> response = restTemplate.postForEntity(FX_SERVICE_URL, requestBody, Map.class);

                if (response.getBody() != null && response.getBody().get("exchange_rate") != null) {
                    Double rate = (Double) response.getBody().get("exchange_rate");
                    return BigDecimal.valueOf(rate);
                }
            } catch (Exception e) {
                lastError = e;
                // Wait 1 second before retrying
                try {
                    Thread.sleep(1000);
                }
                catch (InterruptedException ie) {

                }
            }
        }

        throw new PaymentException("FX Service unavailable: " + (lastError != null ? lastError.getMessage() : "Unknown error"));
    }
    public Payment getPayment(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new PaymentException("Payment not found: " + id));
    }
    public List<Payment> getAllPayments() {
        return repository.findAll();
    }
}