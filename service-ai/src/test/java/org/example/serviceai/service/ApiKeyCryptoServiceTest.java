package org.example.serviceai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ApiKeyCryptoServiceTest {

    @Test
    void encryptsAndDecryptsApiKey() {
        ApiKeyCryptoService service = new ApiKeyCryptoService(
                "wcfKdHJkghwFn7PKkc5BH96mw39FJnZ121Camt+jppY="
        );

        String encrypted = service.encrypt("sk-user-secret");

        assertNotEquals("sk-user-secret", encrypted);
        assertEquals("sk-user-secret", service.decrypt(encrypted));
    }
}
