package com.voiceshopping.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles well-known probing paths so they don't trigger NoResourceFoundException.
 * Chrome DevTools (129+) probes /.well-known/appspecific/com.chrome.devtools.json
 * to detect workspace config — return 204 to silence it.
 */
@RestController
public class WellKnownController {

    @GetMapping("/.well-known/appspecific/com.chrome.devtools.json")
    public ResponseEntity<Void> chromeDevtools() {
        return ResponseEntity.noContent().build();
    }
}
