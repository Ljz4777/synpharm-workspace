package com.synpharm.common.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 数据库 JSON 列 ↔ Java 对象的转换。
 *
 * <p>各域的表里都有若干 {@code json} 列（{@code meta_json} / {@code detail_json} / {@code params} …），
 * 统一在这里处理，避免每个 AppService 各写一遍 try/catch 把 {@code JsonProcessingException}
 * 漏成 500。
 *
 * <p>写入时非法值不静默忽略：数据库会接受 {@code "{}"} 这类合法 JSON，但把编码失败
 * 当成 null 写进去会让排查变成猜谜，所以直接抛 {@link ErrorCode#INTERNAL_ERROR}。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    /** 序列化为 JSON 文本；{@code null} 原样返回 */
    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("JSON 序列化失败: {}", value.getClass().getName(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /** 反序列化为 Map；空串/null 返回 null */
    public Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.error("JSON 反序列化失败: {}", json, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /** 反序列化为指定类型；空串/null 返回 null */
    public <T> T read(String json, Class<T> type) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.error("JSON 反序列化失败: {}", json, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
