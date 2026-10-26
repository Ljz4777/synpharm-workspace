package com.synpharm.design.support;

import com.synpharm.design.application.JobAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 任务进度回报消费者（计算层 → 设计服务的反向通道）。
 *
 * <p>队列 {@code design.job.status} 不在《03-实现级详细设计》§7 的队列表里，是本次补齐的：
 * 计算层是独立服务、不能直连本服务的库，而规范要求「DB 为权威」，所以回报只能走 MQ。
 *
 * <p>消息体（Map）：
 * <pre>
 * { "jobNo": "J260101120001A7F3",
 *   "status": "RUNNING|SUCCEEDED|PARTIAL|FAILED",
 *   "totalCount": 100, "doneCount": 42,
 *   "result": {...},                // 轻量结果摘要，大结果落业务表
 *   "errorCode": "TOOL_UNAVAILABLE", "errorMsg": "逐项失败原因（PARTIAL 必填）" }
 * </pre>
 *
 * <p>处理失败会抛异常 → 3 次指数退避 → {@code design.dlq}（规范 §7）。
 * <b>不做吞异常</b>：回报丢了会让任务永远停在 PENDING。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobStatusListener {

    private final JobAppService jobAppService;

    @RabbitListener(queues = RabbitMqConfig.QUEUE_JOB_STATUS)
    public void onJobStatus(Map<String, Object> message) {
        String jobNo = stringOf(message.get("jobNo"));
        String status = stringOf(message.get("status"));
        if (!StringUtils.hasText(jobNo) || !StringUtils.hasText(status)) {
            throw new IllegalArgumentException("回报消息缺少 jobNo 或 status: " + message);
        }
        jobAppService.report(jobNo, status,
                intOf(message.get("totalCount")),
                intOf(message.get("doneCount")),
                mapOf(message.get("result")),
                stringOf(message.get("errorCode")),
                stringOf(message.get("errorMsg")));
    }

    private String stringOf(Object raw) {
        return raw == null ? null : raw.toString();
    }

    private Integer intOf(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : Integer.valueOf(text);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : null;
    }
}
