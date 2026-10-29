package com.yourorg.payops.client.bank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

@Component
public class FakeBankClient {
    private static final Logger log = LoggerFactory.getLogger(FakeBankClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    @Autowired
    public FakeBankClient(@Value("${fakebank.url:http://localhost:8081}") String baseUrl,
                          @Value("${fakebank.timeout-ms:5000}") long timeoutMs) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Math.min(timeoutMs, 2000));
        factory.setReadTimeout((int) timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    // Constructor for testing with mock RestTemplate
    public FakeBankClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /**
     * Calls fake-bank authorization endpoint.
     * Throws BankTimeoutException if the bank call times out (5-second timeout).
     */
    public BankAuthResponse authorize(BankAuthRequest request) {
        String url = baseUrl + "/api/bank/payments";
        try {
            log.info("Sending authorization request to fake bank: requestId={}, paymentRef={}", request.getRequestId(), request.getPaymentReference());
            ResponseEntity<BankAuthResponse> response = restTemplate.postForEntity(url, request, BankAuthResponse.class);
            return response.getBody();
        } catch (ResourceAccessException e) {
            log.warn("Fake-bank call timed out or failed to connect for requestId={}: {}", request.getRequestId(), e.getMessage());
            throw new BankTimeoutException("Bank authorization timed out: " + e.getMessage(), e);
        } catch (HttpStatusCodeException e) {
            log.info("Fake-bank returned HTTP status {} for requestId={}", e.getStatusCode(), request.getRequestId());
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST || e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                try {
                    BankAuthResponse errBody = e.getResponseBodyAs(BankAuthResponse.class);
                    if (errBody != null) {
                        return errBody;
                    }
                } catch (Exception ignored) {}
            }
            throw e;
        } catch (RestClientException e) {
            log.error("Fake-bank call encountered error for requestId={}: {}", request.getRequestId(), e.getMessage());
            throw new BankTimeoutException("Bank authorization error: " + e.getMessage(), e);
        }
    }

    /**
     * Status lookup by stable bank request ID for recovery.
     * Returns Optional.empty() if bank returns 404 Not Found.
     * Rethrows RestClientException (including ResourceAccessException) for network/timeout errors
     * so recovery callers can distinguish between missing records and transient network failures.
     */
    public Optional<BankAuthResponse> getStatus(String requestId) {
        String url = baseUrl + "/api/bank/payments/" + requestId;
        try {
            ResponseEntity<BankAuthResponse> response = restTemplate.getForEntity(url, BankAuthResponse.class);
            return Optional.ofNullable(response.getBody());
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public static class BankTimeoutException extends RuntimeException {
        public BankTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
