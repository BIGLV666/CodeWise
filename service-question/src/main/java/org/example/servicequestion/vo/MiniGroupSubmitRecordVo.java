package org.example.servicequestion.vo;

import java.time.LocalDateTime;

public record MiniGroupSubmitRecordVo (
        Long groupSubmitRecordId,
        Long submitRecordId,
        Long questionId,
        String questionTitle,
        String nickName,
        String avatarUrl,
        String status,
        LocalDateTime createTime
){

}
