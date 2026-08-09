package com.chapchapgateway.global.response.constant;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum CustomResponseCode {
    SCG_SUCCESS(HttpStatus.OK, "00"),
    SCG_INVALID_TOKEN_ERROR(HttpStatus.UNAUTHORIZED,"E04"),
    SCG_NOT_FOUND_ERROR(HttpStatus.NOT_FOUND, "E50"),
    SCG_SYSTEM_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "E99"),
    ;

    private final HttpStatus httpStatus;
    private final String code;

    CustomResponseCode(HttpStatus httpStatus, String code)
    {
        this.httpStatus = httpStatus;
        this.code = code;
    }
}
