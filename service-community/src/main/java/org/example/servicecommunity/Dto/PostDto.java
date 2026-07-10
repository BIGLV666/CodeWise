package org.example.servicecommunity.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostDto {
    private String postTitle;
    private String postContent;
    private List<String>tags;
    private Long userId;
    private String userName;

}
