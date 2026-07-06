package org.example.servicereview.dto;

import lombok.Data;

import java.util.List;

@Data
public class ReceiveDto {
    private Long favoritesId;
    private String favoritesName;
    private String favoritesContent;
    private List<Long> questionIds;
    private String favoritesType;

}
