package org.example.serviceai.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.dto.UserAiConfigDto;
import org.example.serviceai.entry.UserAiConfig;
import org.example.serviceai.mapper.UserAiConfigMapper;
import org.example.serviceai.vo.HomeUserConfigVo;
import org.example.serviceai.vo.UserAiConfigVo;
import org.example.servicecommon.until.UserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
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
            throw new SecurityException("请登录后操作");
        }
        UserAiConfig config = requireOwnedConfig(configId, userId);
        String selectedModel = normalizeModelName(modelName);
        if (config.getModelNames() == null || !config.getModelNames().contains(selectedModel)) {
            throw new IllegalArgumentException("所选模型不属于该配置");
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

            int responseLength = readStream(connection, onChunk);
            log.info("Custom AI stream completed, configId={}, model={}, responseLength={}",
                    configId, selectedModel, responseLength);
        } catch (AiProviderHttpException exception) {
            log.warn("Custom AI request rejected, configId={}, model={}, error={}",
                    configId, selectedModel, exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            log.error("Custom AI stream failed, configId={}, model={}",
                    configId, selectedModel, exception);
            throw new IllegalStateException("自定义 AI 服务调用失败: " + exception.getMessage(), exception);
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
                throw new IllegalStateException("自定义 AI 配置保存失败");
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("分组名称已存在", exception);
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
                throw new IllegalStateException("自定义 AI 配置更新失败");
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("分组名称已存在", exception);
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
            throw new IllegalArgumentException("配置不存在或无权删除");
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
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalArgumentException("API Key 无效或没有读取模型列表的权限");
            }
            if (response.statusCode() == 429) {
                throw new IllegalStateException("AI 服务请求过于频繁，请稍后重试");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("获取模型列表失败，HTTP " + response.statusCode());
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
                throw new IllegalStateException("AI 服务未返回可用模型");
            }
            return models.stream().distinct().limit(MAX_MODELS).toList();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("获取模型列表失败: " + exception.getMessage(), exception);
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
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
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
            throw new IllegalArgumentException("自定义 AI 配置 ID 不能为空");
        }
        UserAiConfig config = userAiConfigMapper.selectOne(
                new LambdaQueryWrapper<UserAiConfig>()
                        .eq(UserAiConfig::getUserAiConfigId, configId)
                        .eq(UserAiConfig::getUserId, userId)
                        .last("LIMIT 1")
        );
        if (config == null) {
            throw new SecurityException("配置不存在或无权访问");
        }
        return config;
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new SecurityException("请登录后操作");
        }
        return userId;
    }

    private void validateConfig(UserAiConfigDto dto, boolean requireApiKey) {
        if (dto == null) {
            throw new IllegalArgumentException("配置不能为空");
        }
        if (dto.getGroupName() == null || dto.getGroupName().isBlank()) {
            throw new IllegalArgumentException("分组名称不能为空");
        }
        if (dto.getGroupName().trim().length() > 64) {
            throw new IllegalArgumentException("分组名称不能超过 64 个字符");
        }
        normalizeModels(dto.getModelNames());
        normalizeBaseUrl(dto.getAiUrl());
        if (requireApiKey || (dto.getApiKey() != null && !dto.getApiKey().isBlank())) {
            validateApiKey(dto.getApiKey());
        }
    }

    private void validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        if (apiKey.trim().length() > MAX_API_KEY_LENGTH) {
            throw new IllegalArgumentException("API Key 长度超过限制");
        }
    }

    private List<String> normalizeModels(List<String> modelNames) {
        if (modelNames == null || modelNames.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个模型");
        }
        List<String> models = modelNames.stream()
                .filter(model -> model != null && !model.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (models.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个模型");
        }
        if (models.size() > MAX_MODELS) {
            throw new IllegalArgumentException("模型数量不能超过 " + MAX_MODELS);
        }
        return models;
    }

    private String normalizeModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        return modelName.trim();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("API 地址不能为空");
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("自定义 AI 地址必须使用 HTTPS");
            }
            if (uri.getUserInfo() != null || uri.getHost() == null) {
                throw new IllegalArgumentException("API 地址格式不正确");
            }
            InetAddress address = InetAddress.getByName(uri.getHost());
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("不允许访问本机或内网 AI 地址");
            }
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
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("API 地址无法解析", exception);
        }
    }

    private String chatEndpoint(String baseUrl) {
        return normalizeBaseUrl(baseUrl) + "/chat/completions";
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
