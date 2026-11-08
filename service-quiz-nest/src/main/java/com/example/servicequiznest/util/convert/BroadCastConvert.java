package com.example.servicequiznest.util.convert;

import org.example.serviceapi.dto.quizNest.BroadCast;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public class BroadCastConvert {

    /** 站内信时间展示格式：消费端 EnvelopeCodec 未注册 JavaTimeModule，事件里只能用字符串。 */
    private static final DateTimeFormatter MESSAGE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static BroadCast ToBroadCast(
            Long groupId,
            String groupName,
            List<Long> userIds,
            Long createUserId,
            String createUserName,
            String context
    ) {
        return BroadCast.builder()
                .messageId(UUID.randomUUID().toString())
                .groupId(groupId)
                .groupName(groupName)
                .userIds(userIds)
                .createUserId(createUserId)
                .createUserName(createUserName)
                .context(context)
                .sendTime(LocalDateTime.now().format(MESSAGE_TIME_FORMATTER))
                .build();
    }
}
