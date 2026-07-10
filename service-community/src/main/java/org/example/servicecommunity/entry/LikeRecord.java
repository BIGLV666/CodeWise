package org.example.servicecommunity.entry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LikeRecord {
    private Long likeRecordId;
    private Long userId;
    private Long postId;
    private String type;
}
