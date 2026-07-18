package org.example.serviceai.service;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.AIServiceManager.AIServiceManager;
import org.example.serviceai.intifer.CallAi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class AIService {

    @Autowired
    private AIServiceManager serviceManager;

    public String callAi(String prompt) {
        CallAi first = serviceManager.getAvailableService();
        if (first == null) {
            throw new IllegalStateException("No healthy AI provider available");
        }

        long start = System.currentTimeMillis();
        try {
            log.info("Calling AI provider {}", first.getModelName());
            String result = first.callAi(prompt);
            serviceManager.recordSuccess(first);
            log.info("AI provider {} succeeded in {}ms",
                    first.getModelName(), elapsed(start));
            return result;
        } catch (Exception firstFailure) {
            logFailure("AI provider " + first.getModelName(), start, firstFailure);
            serviceManager.recordFailure(first);
            return retry(prompt, first, firstFailure);
        }
    }

    private String retry(String prompt, CallAi first, Exception firstFailure) {
        serviceManager.switchToNext();
        CallAi next = serviceManager.getAvailableService();
        if (next == null || next == first) {
            throw combinedFailure(first, firstFailure, null, null);
        }

        long start = System.currentTimeMillis();
        try {
            log.info("Retrying AI with provider {}", next.getModelName());
            String result = next.callAi(prompt);
            serviceManager.recordSuccess(next);
            log.info("AI provider {} retry succeeded in {}ms",
                    next.getModelName(), elapsed(start));
            return result;
        } catch (Exception retryFailure) {
            logFailure("AI provider " + next.getModelName() + " retry", start, retryFailure);
            serviceManager.recordFailure(next);
            throw combinedFailure(first, firstFailure, next, retryFailure);
        }
    }

    public void streamAi(String prompt, Consumer<String> onChunk) {
        CallAi first = serviceManager.getAvailableService();
        if (first == null) {
            throw new IllegalStateException("No healthy AI provider available");
        }

        long start = System.currentTimeMillis();
        AtomicBoolean emitted = new AtomicBoolean(false);
        try {
            log.info("Streaming AI with provider {}", first.getModelName());
            first.streamAi(prompt, chunk -> {
                emitted.set(true);
                onChunk.accept(chunk);
            });
            serviceManager.recordSuccess(first);
            log.info("Streaming AI provider {} succeeded in {}ms",
                    first.getModelName(), elapsed(start));
        } catch (Exception firstFailure) {
            logFailure("Streaming AI provider " + first.getModelName(), start, firstFailure);
            serviceManager.recordFailure(first);

            // Once a provider has emitted text, do not append another model's
            // answer to the already visible partial response.
            if (emitted.get()) {
                throw combinedFailure(first, firstFailure, null, null);
            }

            serviceManager.switchToNext();
            CallAi next = serviceManager.getAvailableService();
            if (next == null || next == first) {
                throw combinedFailure(first, firstFailure, null, null);
            }

            long retryStart = System.currentTimeMillis();
            try {
                log.info("Retrying streaming AI with provider {}", next.getModelName());
                next.streamAi(prompt, onChunk);
                serviceManager.recordSuccess(next);
                log.info("Streaming AI provider {} retry succeeded in {}ms",
                        next.getModelName(), elapsed(retryStart));
            } catch (Exception retryFailure) {
                logFailure("Streaming AI provider " + next.getModelName() + " retry",
                        retryStart, retryFailure);
                serviceManager.recordFailure(next);
                throw combinedFailure(first, firstFailure, next, retryFailure);
            }
        }
    }

    private void logFailure(String provider, long start, Throwable failure) {
        log.error("{} failed after {}ms, timeout={}, rootCause={}",
                provider, elapsed(start), isTimeout(failure), rootCause(failure), failure);
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String className = current.getClass().getName().toLowerCase();
            if (className.contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String rootCause(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null
                ? "unknown"
                : current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    private RuntimeException combinedFailure(
            CallAi first,
            Exception firstFailure,
            CallAi second,
            Exception secondFailure
    ) {
        String message = "AI providers failed: first=" + first.getModelName()
                + ", firstCause=" + rootCause(firstFailure);
        if (second != null && secondFailure != null) {
            message += ", retry=" + second.getModelName()
                    + ", retryCause=" + rootCause(secondFailure);
        }
        RuntimeException result = new RuntimeException(message,
                secondFailure == null ? firstFailure : secondFailure);
        if (secondFailure != null) {
            result.addSuppressed(firstFailure);
        }
        return result;
    }
}
