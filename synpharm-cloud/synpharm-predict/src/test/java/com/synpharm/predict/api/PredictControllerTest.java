package com.synpharm.predict.api;

import com.synpharm.common.constant.InternalHeaders;
import com.synpharm.common.security.InternalRequestFilter;
import com.synpharm.common.security.SynpharmInternalProperties;
import com.synpharm.common.web.GlobalExceptionHandler;
import com.synpharm.predict.api.dto.PredictResultResponse;
import com.synpharm.predict.application.PredictService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 预测接口控制器测试。
 *
 * <p>阶段 4 起身份不再来自 Token，也不再用 Spring Security：网关验签后注入
 * {@code X-Internal-Token} + {@code X-User-Id}，由 {@link InternalRequestFilter}
 * 写入 {@code CurrentUser}。因此这里把**真实的过滤器**装进 MockMvc 链并带上这两个头，
 * 走的就是生产路径（迁移前的写法是直接往 SecurityContext 塞 Authentication，
 * 那条路径在本服务已不存在）。
 *
 * <p>断言口径也随之变化：错误响应不再一律 HTTP 200 + body 整数码，而是
 * **真实 HTTP 状态码** + 聚合码（HTTP×100 + 序号），见规范 §3。
 */
@ExtendWith(MockitoExtension.class)
class PredictControllerTest {

    private static final String INTERNAL_TOKEN = "test-internal-token";

    private static final Long USER_ID = 1L;

    @Mock
    private PredictService predictService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SynpharmInternalProperties properties = new SynpharmInternalProperties();
        properties.setToken(INTERNAL_TOKEN);

        PredictController controller = new PredictController(predictService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new InternalRequestFilter(properties))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    /** 模拟「已经过网关」：带上内部令牌与用户身份头 */
    private static MockHttpServletRequestBuilder viaGateway(MockHttpServletRequestBuilder builder) {
        return builder.header(InternalHeaders.INTERNAL_TOKEN, INTERNAL_TOKEN)
                .header(InternalHeaders.USER_ID, String.valueOf(USER_ID))
                .header(InternalHeaders.USER_ROLE, "USER");
    }

    @Test
    void 通用预测接口_成功() throws Exception {
        when(predictService.predict(any(), eq(USER_ID)))
                .thenReturn(PredictResultResponse.builder().algoType("DTI").targetId("T1").build());

        mockMvc.perform(viaGateway(post("/api/predict/general"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputType\":\"smiles\",\"algoType\":\"DTI\",\"inputValue\":\"CCO,SEQ\"}"))
                .andExpect(status().isOk())
                // 勘误 C-01：成功码从 200 改为 0
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.algoType").value("DTI"));
    }

    @Test
    void 通用预测接口_参数缺失_校验失败() throws Exception {
        mockMvc.perform(viaGateway(post("/api/predict/general"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                // 迁移后：真 HTTP 400 + 聚合码 40000（HTTP 400 × 100 + 序号 00）
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void 未经网关_身份头被忽略_返回401() throws Exception {
        // 只带 X-User-Id、不带内部令牌：过滤器必须忽略身份头 → 401
        mockMvc.perform(post("/api/predict/general")
                        .header(InternalHeaders.USER_ID, String.valueOf(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputType\":\"smiles\",\"algoType\":\"DTI\",\"inputValue\":\"CCO,SEQ\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void 旧DTI接口_兼容可用() throws Exception {
        when(predictService.predictDTI(any(), eq(USER_ID)))
                .thenReturn(PredictResultResponse.builder().algoType("DTI").build());

        mockMvc.perform(viaGateway(post("/api/predict/dti"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"smiles\":\"CCO\",\"targetId\":\"T1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(predictService).predictDTI(any(), eq(USER_ID));
    }
}