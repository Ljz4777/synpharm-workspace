package com.synpharm.design.support;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一信封与 HTTP 状态行测试。
 *
 * <p>重点锁死「未知路径不能落进兜底 500」——设计域有 79 个端点，
 * 若拼错 URL 一律 500，排障成本极高。
 *
 * @author SynPharm Team
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("业务异常：HTTP 状态行取错误码自身登记的状态，信封带 errorCode")
    void mapsBizExceptionToItsOwnHttpStatus() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleBiz(new BizException(ErrorCode.DESIGN_PROJECT_NOT_FOUND));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsEntry("code", 40440);
        assertThat(response.getBody()).containsEntry("errorCode", "DESIGN_PROJECT_NOT_FOUND");
    }

    @Test
    @DisplayName("业务异常带 details 时放进 data.details")
    void exposesBizDetails() {
        BizException e = new BizException(ErrorCode.CANDIDATE_DUPLICATED, null, Map.of("existingId", 7L));

        ResponseEntity<Map<String, Object>> response = handler.handleBiz(e);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("code", 40949);
        assertThat(response.getBody()).containsEntry("data", Map.of("details", Map.of("existingId", 7L)));
    }

    @Test
    @DisplayName("未知路径：404 而不是 500")
    void mapsUnknownPathToNotFound() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleNotFound(new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api/design/typo"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsEntry("code", 40400);
        assertThat(response.getBody()).containsEntry("errorCode", "RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("方法不匹配：405 而不是 500")
    void mapsUnsupportedMethodToMethodNotAllowed() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleMethodNotAllowed(new HttpRequestMethodNotSupportedException("Request method 'PUT' is not supported"));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody()).containsEntry("code", 40500);
        assertThat(response.getBody()).containsEntry("errorCode", "METHOD_NOT_ALLOWED");
    }

    @Test
    @DisplayName("未预期异常：500 且不泄漏内部细节")
    void hidesInternalDetails() {
        ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(new IllegalStateException("jdbc url leaked"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("code", 50000);
        assertThat(response.getBody().toString()).doesNotContain("jdbc url leaked");
    }

    /** 契约之外的枚举取值：曾经的表现为兜底 500 —— 调用方以为服务端崩了 */
    enum SampleKind { ALPHA, BETA }

    @Test
    @DisplayName("枚举取值非法：400 MALFORMED_REQUEST，并回传允许取值")
    void mapsInvalidEnumToBadRequest() {
        InvalidFormatException invalid = InvalidFormatException.from(
                null, "not one of the values accepted", "GAMMA", SampleKind.class);
        ResponseEntity<Map<String, Object>> response = handler.handleUnreadableBody(
                new HttpMessageNotReadableException("JSON parse error", invalid));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("code", 40001);
        assertThat(response.getBody()).containsEntry("errorCode", "MALFORMED_REQUEST");
        Object data = response.getBody().get("data");
        assertThat(data).isInstanceOf(Map.class);
        // 非法值与允许取值都要在响应里，调用方据此自救（不必翻文档）
        assertThat(String.valueOf(data)).contains("GAMMA").contains("ALPHA", "BETA");
    }

    @Test
    @DisplayName("请求体语法错误：同样是 400 而不是 500")
    void mapsUnreadableBodyToBadRequest() {
        ResponseEntity<Map<String, Object>> response = handler.handleUnreadableBody(
                new HttpMessageNotReadableException("Unexpected end-of-input"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("errorCode", "MALFORMED_REQUEST");
    }
}
