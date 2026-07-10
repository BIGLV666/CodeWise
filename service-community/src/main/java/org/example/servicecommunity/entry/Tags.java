package org.example.servicecommunity.entry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tags {
    private Long tagId;
    private String tagName;
    private Long postId;

}
