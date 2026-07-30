package org.example.serviceai.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

final class AiHttpSupport {

    private static final int MAX_ERROR_BODY_LENGTH = 1000;

    private AiHttpSupport() {
    }

    static BufferedReader responseReader(
            HttpURLConnection connection,
            String provider
    ) throws IOException {
        int statusCode = connection.getResponseCode();
        if (statusCode >= 200 && statusCode < 300) {
            return new BufferedReader(new InputStreamReader(
                    connection.getInputStream(),
                    StandardCharsets.UTF_8
            ));
        }

        throw new AiProviderHttpException(
                provider,
                statusCode,
                readErrorBody(connection.getErrorStream()),
                connection.getHeaderField("Retry-After")
        );
    }

    private static String readErrorBody(InputStream errorStream) throws IOException {
        if (errorStream == null) {
            return "";
        }
        try (errorStream) {
            String body = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            return body.substring(0, Math.min(body.length(), MAX_ERROR_BODY_LENGTH));
        }
    }
}
