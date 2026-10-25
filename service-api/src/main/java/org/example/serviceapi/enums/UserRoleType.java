package org.example.serviceapi.enums;

import lombok.Getter;

/**
 * 用户身份枚举类
 * <ul>
 *     <li>ROLE_ROOT(0):root用户</li>
 *     <li>ROLE_ADMIN(2):普通管理</li>
 *     <li>NORMAL(1)：普通用户</li>
 * </ul>
 */
@Getter
public enum UserRoleType {

    ROLE_ADMIN  (2),
    ROLE_ROOT(0) ,
    NORMAL(1);
    private   final int value;
    UserRoleType(int value){
        this.value = value;
    }

}
