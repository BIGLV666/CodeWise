package org.example.servicecommon.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.serviceapi.dto.event.EventEnvelope;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 统一消息信封编解码器。
 *
 * <p>生产端使用 {@link #wrap} 构造信封；消费端使用 {@link #unwrap} 做
 * 「信封 / 裸格式」双读——灰度期间旧格式消息（裸 Long、裸 uuid、裸 DTO JSON）
 * 仍按原方式解析，新格式消息自动解包 payload，保证两侧可独立发布。</p>
 *
 * <p>判定规则：消息体为 JSON 对象且同时含 {@code eventId} 与 {@code eventType}
 * 字段视为信封格式，其余一律按裸格式处理。</p>
 */
public final class EnvelopeCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private EnvelopeCodec() {
    }

    /**
     * 构造统一信封。
     *
     * @param eventType 事件类型，见 {@code EventTypes}
     * @param producer  生产者服务名
     * @param traceId   链路追踪 ID，可为 null（内部自动生成短 ID）
     * @param payload   业务载荷，约定只放 ID 引用等小字段
     * @return 已填充 eventId/occurredAt/schemaVersion 的信封
     */
    public static EventEnvelope wrap(String eventType, String producer, String traceId, Object payload) {
        return EventEnvelope.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .schemaVersion(EventEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(LocalDateTime.now().format(TIME_FORMATTER))
                .producer(producer)
                .traceId(traceId == null || traceId.isBlank()
                        ? UUID.randomUUID().toString().substring(0, 8)
                        : traceId)
                .payload(payload)
                .build();
    }

    /**
     * 判断消息体是否为统一信封格式。
     *
     * @param body UTF-8 消息体字符串
     * @return 是信封返回 true
     */
    public static boolean isEnvelope(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            return root.isObject() && root.has("eventId") && root.has("eventType");
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 将消息体解析为信封（不校验业务载荷类型）。
     *
     * @param body UTF-8 消息体字符串
     * @return 信封对象
     * @throws IllegalArgumentException 消息体不是信封格式或 JSON 非法
     */
    public static EventEnvelope readEnvelope(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!root.isObject() || !root.has("eventId") || !root.has("eventType")) {
                throw new IllegalArgumentException("消息体不是统一信封格式");
            }
            return MAPPER.treeToValue(root, EventEnvelope.class);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("信封解析失败: " + exception.getMessage(), exception);
        }
    }

    /**
     * 双读解析业务载荷：信封格式解包 payload，裸格式按目标类型直接反序列化。
     *
     * @param body        UTF-8 消息体字符串
     * @param payloadType 业务载荷目标类型
     * @param <T>         载荷类型
     * @return 业务载荷对象
     * @throws IllegalArgumentException 解析失败时抛出，由调用方决定重试或死信
     */
    public static <T> T unwrap(String body, Class<T> payloadType) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode payloadNode = root.isObject() && root.has("eventId") && root.has("eventType")
                    ? root.get("payload")
                    : root;
            return MAPPER.treeToValue(payloadNode, payloadType);
        } catch (Exception exception) {
            throw new IllegalArgumentException("消息载荷解析失败: " + exception.getMessage(), exception);
        }
    }

    /**
     * 将信封序列化为 JSON 字符串（Outbox 存储与直接发送共用）。
     *
     * @param envelope 信封对象
     * @return JSON 字符串
     */
    public static String serialize(EventEnvelope envelope) {
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (Exception exception) {
            throw new IllegalStateException("信封序列化失败", exception);
        }
    }
}
