package com.synpharm.design.support;

import com.synpharm.design.application.JobAppService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 任务回报消费者测试。
 *
 * <p>回报通道是计算层与设计库之间唯一的桥，映射错了不会报错、只会让任务停在错误的进度上，
 * 所以字段映射要逐个断言（尤其 {@code totalCount} 常以字符串形式过来，必须能转成 Integer）。
 *
 * <p>另一半重点是**不许吞异常**：缺 {@code jobNo}/{@code status} 时若静默忽略，任务会永远停在
 * PENDING 而没有任何报错；抛出去才能走 3 次退避 → {@code design.dlq}。
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobStatusListenerTest {

    private static final String JOB_NO = "J261014153045A7F3";

    @Mock private JobAppService jobAppService;

    @InjectMocks private JobStatusListener listener;

    private static Map<String, Object> message(Object jobNo, Object status) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jobNo", jobNo);
        message.put("status", status);
        return message;
    }

    @Test
    @DisplayName("回报：字段逐个映射，totalCount 以字符串传入也能转成 Integer")
    void onJobStatusMapsAllFields() {
        Map<String, Object> message = message(JOB_NO, "RUNNING");
        message.put("totalCount", "100");
        message.put("doneCount", 42);
        message.put("result", Map.of("realness", "REAL"));
        message.put("errorCode", "TOOL_UNAVAILABLE");
        message.put("errorMsg", "第 3 项失败");

        listener.onJobStatus(message);

        // report 的第 3 个形参是 Integer：这里能匹配上 100，就证明字符串 "100" 真的被转成了 Integer
        verify(jobAppService).report(eq(JOB_NO), eq("RUNNING"), eq(100), eq(42),
                eq(Map.of("realness", "REAL")), eq("TOOL_UNAVAILABLE"), eq("第 3 项失败"));
    }

    @Test
    @DisplayName("回报：只带 jobNo/status 时，计数与结果一律传 null（不臆造 0）")
    void onJobStatusPassesNullsForOptionalFields() {
        listener.onJobStatus(message(JOB_NO, "SUCCEEDED"));

        verify(jobAppService).report(eq(JOB_NO), eq("SUCCEEDED"), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("回报：计数为空白字符串 -> null；带空格的数字 -> 去掉空白后转换")
    void onJobStatusNormalisesStringCounts() {
        Map<String, Object> blank = message(JOB_NO, "RUNNING");
        blank.put("totalCount", "   ");
        blank.put("doneCount", " 7 ");
        blank.put("result", "not-a-map");

        listener.onJobStatus(blank);

        verify(jobAppService).report(eq(JOB_NO), eq("RUNNING"), isNull(), eq(7), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("回报：result 不是 Map -> 传 null，而不是抛 ClassCastException")
    void onJobStatusTreatsNonMapResultAsNull() {
        Map<String, Object> message = message(JOB_NO, "SUCCEEDED");
        message.put("result", "plain text");

        listener.onJobStatus(message);

        verify(jobAppService).report(eq(JOB_NO), eq("SUCCEEDED"), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("回报：缺 jobNo -> IllegalArgumentException，且不调用 report")
    void onJobStatusRejectsMissingJobNo() {
        assertThatThrownBy(() -> listener.onJobStatus(message(null, "RUNNING")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jobNo");
        verify(jobAppService, never()).report(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("回报：jobNo 为空白 -> IllegalArgumentException")
    void onJobStatusRejectsBlankJobNo() {
        assertThatThrownBy(() -> listener.onJobStatus(message("   ", "RUNNING")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jobNo");
        verify(jobAppService, never()).report(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("回报：缺 status -> IllegalArgumentException（否则任务永远 PENDING）")
    void onJobStatusRejectsMissingStatus() {
        assertThatThrownBy(() -> listener.onJobStatus(message(JOB_NO, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
        verify(jobAppService, never()).report(anyString(), anyString(), any(), any(), any(), any(), any());
    }
}
