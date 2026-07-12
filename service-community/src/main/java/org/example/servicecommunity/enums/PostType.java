package org.example.servicecommunity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum PostType {
    POST("POST"),
    SOLUTION("SOLUTION"),
    COMMENT("COMMENT");

    @EnumValue
    @JsonValue
    private final String type;

    PostType(String type){
        this.type=type;
    }
}
