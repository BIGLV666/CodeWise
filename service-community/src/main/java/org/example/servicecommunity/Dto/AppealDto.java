package org.example.servicecommunity.Dto;

import lombok.Data;
import org.example.servicecommunity.enums.PostType;

@Data

public class AppealDto {
    private Long appealId;
    private Long postId;
    private String  postType;
    private String reason;
}
