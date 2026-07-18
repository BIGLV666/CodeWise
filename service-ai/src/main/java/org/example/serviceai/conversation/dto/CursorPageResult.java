package org.example.serviceai.conversation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class CursorPageResult<T> {
    private List<T> items;
    private Long nextCursor;
    private boolean hasMore;
}
