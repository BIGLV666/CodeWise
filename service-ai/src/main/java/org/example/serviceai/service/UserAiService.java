package org.example.serviceai.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.dto.UserAiConfigDto;
import org.example.serviceai.entry.UserAiConfig;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceai.mapper.UserAiConfigMapper;
import org.example.serviceai.vo.HomeUserConfigVo;
import org.example.serviceai.vo.UserAiConfigVo;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.servicecommon.until.UserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.Inet6Address;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
@Slf4j
public class UserAiService {

    private static final int MAX_MODELS = 100;
    private static final int MAX_API_KEY_LENGTH = 4096;

    private final UserAiConfigMapper userAiConfigMapper;
    private final ApiKeyCryptoService apiKeyCryptoService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public UserAiService(
            UserAiConfigMapper userAiConfigMapper,
            ApiKeyCryptoService apiKeyCryptoService,
            ObjectMapper objectMapper
    ) {
        this.userAiConfigMapper = userAiConfigMapper;
        this.apiKeyCryptoService = apiKeyCryptoService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void streamAi(
            String prompt,
            Consumer<String> onChunk,
            Long userId,
            Long configId,
            String modelName
    ) {
        if (userId == null) {
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        UserAiConfig config = requireOwnedConfig(configId, userId);
        String selectedModel = normalizeModelName(modelName);
        if (config.getModelNames() == null || !config.getModelNames().contains(selectedModel)) {
            throw new BizException(AiErrorCode.MODEL_NOT_IN_CONFIG);
        }

        HttpURLConnection connection = null;
        try {
            String endpoint = chatEndpoint(config.getAiUrl());
            connection = openConnection(endpoint, apiKeyCryptoService.decrypt(config.getApiKey()));
            JSONObject body = requestBody(prompt, selectedModel);
            log.info("Custom AI request, configId={}, model={}, promptLength={}",
                    configId, selectedModel, prompt == null ? 0 : prompt.length());

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            rejectRedirect(endpoint, connection);
            int responseLength = readStream(connection, onChunk);
            log.info("Custom AI stream completed, configId={}, model={}, responseLength={}",
                    configId, selectedModel, responseLength);
        } catch (AiProviderHttpException exception) {
            log.warn("Custom AI request rejected, configId={}, model={}, error={}",
                    configId, selectedModel, exception.getDiagnostic());
            throw exception;
        } catch (BizException exception) {
            // 已是分类后的业务异常：原样上抛，避免被兜底分支重包成通用失败
            throw exception;
        } catch (Exception exception) {
            log.error("Custom AI stream failed, configId={}, model={}",
                    configId, selectedModel, exception);
            throw new BizException(AiErrorCode.MODEL_UNRESPONSIVE, exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public UserAiConfigVo getConfig(Long configId) {
        return toDetailVo(requireOwnedConfig(configId, requireUserId()));
    }

    public UserAiConfigVo createConfig(UserAiConfigDto dto) {
        validateConfig(dto, true);
        Long userId = requireUserId();
        LocalDateTime now = LocalDateTime.now();
        UserAiConfig config = UserAiConfig.builder()
                .userId(userId)
                .groupName(dto.getGroupName().trim())
                .modelNames(normalizeModels(dto.getModelNames()))
                .aiUrl(normalizeBaseUrl(dto.getAiUrl()))
                .apiKey(apiKeyCryptoService.encrypt(dto.getApiKey().trim()))
                .createTime(now)
                .updateTime(now)
                .build();
        try {
            if (userAiConfigMapper.insert(config) != 1) {
                throw new BizException(CommonErrorCode.DATA_OPERATION_FAILED);
            }
        } catch (DuplicateKeyException exception) {
            throw new BizException(AiErrorCode.GROUP_NAME_DUPLICATED, exception);
        }
        return toDetailVo(config);
    }

    public UserAiConfigVo updateConfig(Long configId, UserAiConfigDto dto) {
        validateConfig(dto, false);
        Long userId = requireUserId();
        UserAiConfig config = requireOwnedConfig(configId, userId);
        config.setGroupName(dto.getGroupName().trim());
        config.setModelNames(normalizeModels(dto.getModelNames()));
        config.setAiUrl(normalizeBaseUrl(dto.getAiUrl()));
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) {
            validateApiKey(dto.getApiKey());
            config.setApiKey(apiKeyCryptoService.encrypt(dto.getApiKey().trim()));
        }
        config.setUpdateTime(LocalDateTime.now());
        try {
            if (userAiConfigMapper.updateById(config) != 1) {
                throw new BizException(CommonErrorCode.DATA_OPERATION_FAILED);
            }
        } catch (DuplicateKeyException exception) {
            throw new BizException(AiErrorCode.GROUP_NAME_DUPLICATED, exception);
        }
        return toDetailVo(config);
    }

    public void deleteConfig(Long configId) {
        Long userId = requireUserId();
        int deleted = userAiConfigMapper.delete(
                new LambdaQueryWrapper<UserAiConfig>()
                        .eq(UserAiConfig::getUserAiConfigId, configId)
                        .eq(UserAiConfig::getUserId, userId)
        );
        if (deleted != 1) {
            throw new BizException(AiErrorCode.CONFIG_NOT_ACCESSIBLE);
        }
    }

    public List<HomeUserConfigVo> getAllConfigs() {
        Long userId = requireUserId();
        return userAiConfigMapper.selectList(
                        new LambdaQueryWrapper<UserAiConfig>()
                                .eq(UserAiConfig::getUserId, userId)
                                .orderByDesc(UserAiConfig::getUpdateTime)
                )
                .stream()
                .map(HomeUserConfigVo::new)
                .toList();
    }

    public List<String> fetchModels(String baseUrl, String apiKey) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        validateApiKey(apiKey);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizedBaseUrl + "/models"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                validateRedirect(normalizedBaseUrl + "/models",
                        response.headers().firstValue("Location").orElse(null));
                throw new BizException(AiErrorCode.AI_URL_INVALID);
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new BizException(AiErrorCode.PROVIDER_KEY_INVALID);
            }
            if (response.statusCode() == 429) {
                throw new BizException(AiErrorCode.PROVIDER_QUOTA_EXCEEDED);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(AiErrorCode.FETCH_MODELS_FAILED);
            }

            JsonNode data = objectMapper.readTree(response.body()).path("data");
            List<String> models = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode model : data) {
                    String modelId = model.path("id").asText().trim();
                    if (!modelId.isEmpty()) {
                        models.add(modelId);
                    }
                }
            }
            if (models.isEmpty()) {
                throw new BizException(AiErrorCode.NO_AVAILABLE_MODEL);
            }
            return models.stream().distinct().limit(MAX_MODELS).toList();
        } catch (BizException exception) {
            // 已分类的业务异常原样透出，避免被下面的兜底分支重包
            throw exception;
        } catch (Exception exception) {
            throw new BizException(AiErrorCode.FETCH_MODELS_FAILED, exception);
        }
    }

    private JSONObject requestBody(String prompt, String modelName) {
        JSONObject body = new JSONObject();
        body.put("model", modelName);
        body.put("temperature", 0.7);
        body.put("max_tokens", 1024);
        body.put("stream", true);

        JSONArray messages = new JSONArray();
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        body.put("messages", messages);
        return body;
    }

    private HttpURLConnection openConnection(String endpoint, String apiKey) throws Exception {
        validateUrl(endpoint);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setDoOutput(true);
        connection.setConnectTimeout(120000);
        connection.setReadTimeout(120000);
        return connection;
    }

    private int readStream(HttpURLConnection connection, Consumer<String> onChunk) throws Exception {
        int responseLength = 0;
        try (BufferedReader reader = AiHttpSupport.responseReader(connection, "自定义 AI")) {
            String line;
            while ((line = reader.readLine()) != null) {
                String content = parseContent(line);
                if (content != null) {
                    responseLength += content.length();
                    onChunk.accept(content);
                }
            }
        }
        return responseLength;
    }

    private void rejectRedirect(String sourceUrl, HttpURLConnection connection) {
        int statusCode;
        try {
            statusCode = connection.getResponseCode();
        } catch (Exception exception) {
            throw new BizException(AiErrorCode.PROVIDER_CALL_FAILED, exception);
        }
        if (statusCode >= 300 && statusCode < 400) {
            validateRedirect(sourceUrl, connection.getHeaderField("Location"));
            throw new BizException(AiErrorCode.AI_URL_INVALID);
        }
    }

    private String parseContent(String line) {
        if (!line.startsWith("data: ")) {
            return null;
        }
        String data = line.substring(6).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return null;
        }
        try {
            JSONObject chunk = new JSONObject(data);
            JSONArray choices = chunk.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
            return delta == null ? null : delta.getStr("content");
        } catch (Exception exception) {
            log.warn("Custom AI stream chunk parse failed: {}", exception.getMessage());
            return null;
        }
    }

    private UserAiConfig requireOwnedConfig(Long configId, Long userId) {
        if (configId == null) {
            throw new BizException(AiErrorCode.CONFIG_ID_REQUIRED);
        }
        UserAiConfig config = userAiConfigMapper.selectOne(
                new LambdaQueryWrapper<UserAiConfig>()
                        .eq(UserAiConfig::getUserAiConfigId, configId)
                        .eq(UserAiConfig::getUserId, userId)
                        .last("LIMIT 1")
        );
        if (config == null) {
            throw new BizException(AiErrorCode.CONFIG_NOT_ACCESSIBLE);
        }
        return config;
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        return userId;
    }

    private void validateConfig(UserAiConfigDto dto, boolean requireApiKey) {
        if (dto == null) {
            throw new BizException(AiErrorCode.CONFIG_CONTENT_REQUIRED);
        }
        if (dto.getGroupName() == null || dto.getGroupName().isBlank()) {
            throw new BizException(AiErrorCode.GROUP_NAME_REQUIRED);
        }
        if (dto.getGroupName().trim().length() > 64) {
            throw new BizException(AiErrorCode.GROUP_NAME_TOO_LONG, 64);
        }
        normalizeModels(dto.getModelNames());
        normalizeBaseUrl(dto.getAiUrl());
        if (requireApiKey || (dto.getApiKey() != null && !dto.getApiKey().isBlank())) {
            validateApiKey(dto.getApiKey());
        }
    }

    private void validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(AiErrorCode.API_KEY_REQUIRED);
        }
        if (apiKey.trim().length() > MAX_API_KEY_LENGTH) {
            throw new BizException(AiErrorCode.API_KEY_TOO_LONG);
        }
    }

    private List<String> normalizeModels(List<String> modelNames) {
        if (modelNames == null || modelNames.isEmpty()) {
            throw new BizException(AiErrorCode.MODEL_AT_LEAST_ONE);
        }
        List<String> models = modelNames.stream()
                .filter(model -> model != null && !model.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (models.isEmpty()) {
            throw new BizException(AiErrorCode.MODEL_AT_LEAST_ONE);
        }
        if (models.size() > MAX_MODELS) {
            throw new BizException(AiErrorCode.MODEL_TOO_MANY, MAX_MODELS);
        }
        return models;
    }

    private String normalizeModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new BizException(AiErrorCode.MODEL_NAME_REQUIRED);
        }
        return modelName.trim();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BizException(AiErrorCode.AI_URL_REQUIRED);
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new BizException(AiErrorCode.AI_URL_HTTPS_REQUIRED);
            }
            if (uri.getUserInfo() != null || uri.getHost() == null) {
                throw new BizException(AiErrorCode.AI_URL_INVALID);
            }
            if (uri.getQuery() != null || uri.getFragment() != null) {
                throw new BizException(AiErrorCode.AI_URL_NO_QUERY);
            }
            validateResolvedHost(uri.getHost());
            String normalized = uri.toString();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if (normalized.endsWith("/chat/completions")) {
                normalized = normalized.substring(
                        0,
                        normalized.length() - "/chat/completions".length()
                );
            }
            return normalized;
        } catch (BizException exception) {
            // 已分类的地址错误原样透出
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new BizException(AiErrorCode.AI_URL_INVALID, exception);
        } catch (Exception exception) {
            throw new BizException(AiErrorCode.AI_URL_INVALID, exception);
        }
    }

    private String chatEndpoint(String baseUrl) {
        return normalizeBaseUrl(baseUrl) + "/chat/completions";
    }

    private void validateUrl(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new BizException(AiErrorCode.AI_URL_INVALID, exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new BizException(AiErrorCode.AI_URL_INVALID);
        }
        validateResolvedHost(uri.getHost());
    }

    private void validateRedirect(String sourceUrl, String location) {
        if (location == null || location.isBlank()) {
            throw new BizException(AiErrorCode.AI_URL_INVALID);
        }
        URI source = URI.create(sourceUrl);
        URI target = source.resolve(location);
        validateUrl(target.toString());
    }

    private void validateResolvedHost(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new BizException(AiErrorCode.AI_URL_UNRESOLVED);
            }
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new BizException(AiErrorCode.AI_URL_INTERNAL_FORBIDDEN);
                }
            }
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(AiErrorCode.AI_URL_UNRESOLVED, exception);
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0
                    || first == 10
                    || first == 100 && second >= 64 && second <= 127
                    || first == 127
                    || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 0 && third == 0
                    || first == 192 && second == 0 && third == 2
                    || first == 192 && second == 88 && third == 99
                    || first == 192 && second == 168
                    || first == 198 && (second == 18 || second == 19 || second == 51)
                    || first >= 224;
        }
        return address instanceof Inet6Address
                && (bytes[0] & 0xfe) == 0xfc;
    }

    private UserAiConfigVo toDetailVo(UserAiConfig config) {
        return UserAiConfigVo.builder()
                .userAiConfigId(config.getUserAiConfigId())
                .groupName(config.getGroupName())
                .modelNames(config.getModelNames())
                .aiUrl(config.getAiUrl())
                .apiKey(maskApiKey(config.getApiKey()))
                .createTime(config.getCreateTime())
                .updateTime(config.getUpdateTime())
                .build();
    }

    private String maskApiKey(String encryptedApiKey) {
        String apiKey = apiKeyCryptoService.decrypt(encryptedApiKey);
        if (apiKey.length() <= 6) {
            return "*".repeat(apiKey.length());
        }
        return apiKey.substring(0, 3)
                + "*".repeat(apiKey.length() - 6)
                + apiKey.substring(apiKey.length() - 3);
    }
}
