package org.example.servicequestion.dto;

import lombok.Data;

@Data
public class GetCodeDto {
    private String code;
    private String questionTitle;
    private String language;
    private Long questionId;
    private String submitScene;
    /** 小组提交时必填；普通提交保持为空。 */
    private Long groupId;
}
