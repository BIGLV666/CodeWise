package org.example.servicequestion.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionConfig {
    @TableId(type = IdType.AUTO)
    private Long functionConfigId;
    private Long questionId;
    private String className;
    private String methodName;
    private String parameterConfig;
    private String outputType;
    private LocalDateTime createTime;

}
