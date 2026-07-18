package org.example.serviceapi.dto.judge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

import org.example.serviceapi.dto.question.TestMessage;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JudgeMqDto implements Serializable {
     private static final long serialVersionUID = 1L;
    private List<TestMessage>  testMessages;
    private JudgeResultDto judgeResultDto;
}
