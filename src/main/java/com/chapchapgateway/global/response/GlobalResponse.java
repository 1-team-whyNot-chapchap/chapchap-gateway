package com.chapchapgateway.global.response;


import com.chapchapgateway.global.response.constant.CustomResponseCode;
import org.springframework.http.ResponseEntity;

public record GlobalResponse<T> (
    String code,
    String message,
    T data
) {
    public static <T> GlobalResponse<T> from(CustomResponseCode customResponseCode, T data){
        return new GlobalResponse<>(customResponseCode.getCode(), customResponseCode.name(),data);
    }

    public static GlobalResponse<Void> from(CustomResponseCode customResponseCode){
        return new GlobalResponse<Void>(customResponseCode.getCode(), customResponseCode.name(), null);
    }

    // public static <T> GlobalResponse<T> success (T data) {
    //     return GlobalResponse.<T>from(CustomResponseCode.SCG_SUCCESS, data);
    //
    // }
    
    public static <T> ResponseEntity<GlobalResponse<T>> success(T data) {
        return ResponseEntity.ok(GlobalResponse.<T>from(CustomResponseCode.SCG_SUCCESS, data));
    }

    // public static GlobalResponse<Void> success() {
    //     return GlobalResponse.<Void>from(CustomResponseCode.SCG_SUCCESS, null);
    // }

    public static ResponseEntity<GlobalResponse<Void>> success() {
        return ResponseEntity.ok(GlobalResponse.from(CustomResponseCode.SCG_SUCCESS, null));
    }
}
