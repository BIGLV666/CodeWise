package org.example.serviceai.util;

import org.example.serviceai.entry.Model;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetAPiKeyTest {

    private final GetAPiKey getAPiKey = new GetAPiKey();

    @Test
    void singleKeyIsAlwaysReturned() {
        Model model = new Model();
        model.setApiKeys(List.of("sk-only"));

        for (int i = 0; i < 50; i++) {
            assertEquals("sk-only", getAPiKey.getApikey(model));
        }
    }

    @Test
    void allKeysAreReachable() {
        Model model = new Model();
        model.setApiKeys(List.of("sk-a", "sk-b", "sk-c"));

        Set<String> used = IntStream.range(0, 2000)
                .mapToObj(i -> getAPiKey.getApikey(model))
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("sk-a", "sk-b", "sk-c"), used);
    }

    @Test
    void blankKeysAreIgnored() {
        Model model = new Model();
        model.setApiKeys(new ArrayList<>(java.util.Arrays.asList(null, "  ", "sk-real")));

        assertEquals("sk-real", getAPiKey.getApikey(model));
    }

    @Test
    void missingKeyPoolFailsWithBusinessErrorInsteadOfRawException() {
        Model nullPool = new Model();
        BizException noPool = assertThrows(BizException.class, () -> getAPiKey.getApikey(nullPool));
        assertEquals(AiErrorCode.PROVIDER_KEY_INVALID.getCode(), noPool.getCode());

        Model emptyPool = new Model();
        emptyPool.setApiKeys(List.of());
        assertThrows(BizException.class, () -> getAPiKey.getApikey(emptyPool));

        Model blankPool = new Model();
        blankPool.setApiKeys(List.of("", "   "));
        assertThrows(BizException.class, () -> getAPiKey.getApikey(blankPool));
    }

    @Test
    void concurrentCallsNeverFailOrEscapeThePool() throws Exception {
        Model model = new Model();
        model.setApiKeys(List.of("sk-a", "sk-b", "sk-c"));
        Set<String> pool = Set.of("sk-a", "sk-b", "sk-c");

        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < 16; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 5000; i++) {
                    try {
                        assertTrue(pool.contains(getAPiKey.getApikey(model)));
                    } catch (Throwable failure) {
                        failures.add(failure);
                        return;
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertTrue(failures.isEmpty(), () -> "并发取 key 出现异常: " + failures);
    }
}
