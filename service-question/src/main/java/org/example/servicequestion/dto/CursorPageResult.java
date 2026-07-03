package org.example.servicequestion.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CursorPageResult<T> {
    private List<T> records;
    private Long nextCursor;  // 改为 nextCursor，更清晰
    private Boolean hasNext;  // 改为 hasNext
    private Long total;
}
