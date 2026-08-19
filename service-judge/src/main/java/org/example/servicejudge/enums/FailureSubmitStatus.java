package org.example.servicejudge.enums;

import lombok.Getter;

@Getter
public enum FailureSubmitStatus {
    PENDING(0),
    RETRYING(1),
    SUCCESS(2),
    FAILURE(3);
    private final Integer value;
    FailureSubmitStatus(Integer value) {
        this.value = value;
    }

}
