package com.example.servicequiznest.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CreateGroupQuestionDto 同时被 Jackson（@RequestBody）和 Lombok Builder 使用。
 * 只有包级全参构造器时 Jackson 无法实例化，接口会返回 4000003；这里锁住无参构造。
 */
class CreateGroupQuestionDtoDeserializeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void jacksonCanDeserializeRequestBody() throws Exception {
        CreateGroupQuestionDto dto = objectMapper.readValue(
                "{\"groupId\":88,\"title\":\"两数之和\",\"timeLimit\":1000}",
                CreateGroupQuestionDto.class);

        assertThat(dto.getGroupId()).isEqualTo(88L);
        assertThat(dto.getTitle()).isEqualTo("两数之和");
        assertThat(dto.getTimeLimit()).isEqualTo(1000);
    }

    @Test
    void builderStillWorks() {
        CreateGroupQuestionDto dto = CreateGroupQuestionDto.builder()
                .groupId(88L)
                .questionId(7L)
                .build();

        assertThat(dto.getGroupId()).isEqualTo(88L);
        assertThat(dto.getQuestionId()).isEqualTo(7L);
    }
}
