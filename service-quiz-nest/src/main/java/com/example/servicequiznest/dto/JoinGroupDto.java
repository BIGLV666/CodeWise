package com.example.servicequiznest.dto;

import lombok.Data;

/**
 * 加入小组请求体。
 *
 * <p>仅 INVITE 小组需要 {@code token}；FREE/APPROVAL 小组可以不带请求体。</p>
 */
@Data
public class JoinGroupDto {

    /** 邀请码（6 位数字），仅邀请制小组需要。 */
    private String token;
}
