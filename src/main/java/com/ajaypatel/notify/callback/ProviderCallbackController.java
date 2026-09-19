package com.ajaypatel.notify.callback;

import com.ajaypatel.notify.callback.ProviderCallbackService.ReceiptStatus;
import com.ajaypatel.notify.callback.ProviderCallbackService.Result;
import com.ajaypatel.notify.security.SecurityProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/callbacks")
@Tag(name = "Provider callbacks", description = "Delivery receipts from vendors, authenticated with a shared secret")
public class ProviderCallbackController {
    public static final String SECRET_HEADER = "X-Callback-Secret";

    private final ProviderCallbackService service;
    private final SecurityProperties security;

    public ProviderCallbackController(ProviderCallbackService service, SecurityProperties security) {
        this.service = service;
        this.security = security;
    }

    public record ReceiptRequest(@NotBlank @Size(max = 128) String providerMessageId,
                                 @NotNull ReceiptStatus status,
                                 @Size(max = 1000) String reason) {}

    @PostMapping("/{provider}")
    @Operation(summary = "Vendor delivery receipt (idempotent)")
    public ResponseEntity<Map<String, Object>> receipt(@PathVariable String provider,
                                                       @RequestHeader(value = SECRET_HEADER, required = false) String secret,
                                                       @Valid @RequestBody ReceiptRequest req) {
        if (!constantTimeEquals(security.callbackSecret(), secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid callback secret"));
        }
        Result r = service.apply(provider, req.providerMessageId(), req.status(), req.reason());
        return ResponseEntity.ok(Map.of("result", r.name(), "providerMessageId", req.providerMessageId()));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
