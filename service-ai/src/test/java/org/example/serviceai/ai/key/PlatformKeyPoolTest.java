package org.example.serviceai.ai.key;

import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * key 池：单 key、全量可达、空/空白 key 过滤、空池报业务错误而非裸异常、并发下不越界。
 *
 * <p>行为与改造前的 {@code GetAPiKey} 完全一致，差别只在取用签名——池由端点名索引，
 * 不再要求调用方传整个配置对象。</p>
 */
class PlatformKeyPoolTest {

    private static final String ENDPOINT = "glm-4-flash";

    private final PlatformKeyPool keyPool = new PlatformKeyPool();

    @Test
    void singleKeyIsAlwaysReturned() {
        keyPool.register(ENDPOINT, List.of("sk-only"));

        for (int i = 0; i < 50; i++) {
            assertEquals("sk-only", keyPool.nextKey(ENDPOINT));
        }
    }

    @Test
    void allKeysAreReachable() {
        keyPool.register(ENDPOINT, List.of("sk-a", "sk-b", "sk-c"));

        Set<String> used = IntStream.range(0, 2000)
                .mapToObj(i -> keyPool.nextKey(ENDPOINT))
                .collect(Collectors.toSet());

        assertEquals(Set.of("sk-a", "sk-b", "sk-c"), used);
    }

    @Test
    void blankKeysAreIgnored() {
        keyPool.register(ENDPOINT, new ArrayList<>(Arrays.asList(null, "  ", "sk-real")));

        assertEquals("sk-real", keyPool.nextKey(ENDPOINT));
    }

    @Test
    void missingKeyPoolFailsWithBusinessErrorInsteadOfRawException() {
        BizException unregistered = assertThrows(
                BizException.class, () -> keyPool.nextKey("never-registered"));
        assertEquals(AiErrorCode.PROVIDER_KEY_INVALID.getCode(), unregistered.getCode());

        keyPool.register("null-pool", null);
        assertThrows(BizException.class, () -> keyPool.nextKey("null-pool"));

        keyPool.register("empty-pool", List.of());
        assertThrows(BizException.class, () -> keyPool.nextKey("empty-pool"));

        keyPool.register("blank-pool", List.of("", "   "));
        assertThrows(BizException.class, () -> keyPool.nextKey("blank-pool"));
    }

    @Test
    void reRegisteringAnEndpointReplacesItsPool() {
        keyPool.register(ENDPOINT, List.of("sk-old"));
        keyPool.register(ENDPOINT, List.of("sk-new"));

        assertEquals("sk-new", keyPool.nextKey(ENDPOINT));
    }

    @Test
    void concurrentCallsNeverFailOrEscapeThePool() throws Exception {
        keyPool.register(ENDPOINT, List.of("sk-a", "sk-b", "sk-c"));
        Set<String> pool = Set.of("sk-a", "sk-b", "sk-c");

        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < 16; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 5000; i++) {
                    try {
                        assertTrue(pool.contains(keyPool.nextKey(ENDPOINT)));
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
