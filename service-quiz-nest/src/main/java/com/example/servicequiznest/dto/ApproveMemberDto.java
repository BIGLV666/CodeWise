package com.example.servicequiznest.dto;

import lombok.Data;

/**
 * 审批小组成员申请请求体。
 *
 * <p>{@code approved} 必须显式传 true/false：用包装类型而不是 boolean，
 * 避免请求体漏字段时被默认成 false 而误拒绝申请人。</p>
 */
@Data
public class ApproveMemberDto {

    /** true=通过，false=拒绝，不能为空。 */
    private Boolean approved;

    /** 审批留言，可空；为空时使用默认欢迎/拒绝文案。 */
    private String context;
}
