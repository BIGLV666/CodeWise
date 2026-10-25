package org.example.serviceapi.dto.question;

public record UpdateGroupQuestionDto(
        //修改用dto，该字段必填
      Long questionId,
                //所属小组id
                 String title,
                 String description,
                 String inputDesc,
                 String outputDesc,
                 String sampleInput,
                 String sampleOutput,
                 String hint,//提示

                 String tags,//标签

                 Integer timeLimit,
                 Integer memoryLimit
) {

}
