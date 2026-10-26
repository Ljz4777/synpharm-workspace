package com.synpharm.design.support;

import com.synpharm.design.application.JobAppService;
import com.synpharm.design.domain.model.JobType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务投递。
 *
 * <p><b>一律事务提交后投递</b>（红线 R5：事务内只做本地写）。若在事务里投递，
 * 一旦事务回滚，计算层已经收到消息并开始干活，而库里没有任务行 —— 消息与事实不一致。
 *
 * <p>投递失败<strong>不会让请求失败</strong>（事务已提交，抛错只会给调用方一个误导的 500），
 * 而是把「未派发」记到任务行的 {@code errorCode/errorMsg} 上，让它在
 * {@code GET /jobs/{id}} 里显式可见。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final JobAppService jobAppService;

    /**
     * 提交后投递任务消息。
     *
     * @param jobId   任务主键
     * @param jobNo   任务编号
     * @param jobType 任务类型（决定路由键）
     * @param payload 任务类型相关的附加字段（见《03-实现级详细设计》§7 队列表的「消息体」列）
     */
    public void publishAfterCommit(Long jobId, String jobNo, JobType jobType, Map<String, Object> payload) {
        if (!jobType.isAsync()) {
            log.warn("任务类型 {} 未在 03 §7 定义队列，不投递（任务将停留在 PENDING）", jobType);
            return;
        }
        String routingKey = jobType.routingKey();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(jobId, jobNo, jobType, routingKey, payload);
                }
            });
        } else {
            dispatch(jobId, jobNo, jobType, routingKey, payload);
        }
    }

    private void dispatch(Long jobId, String jobNo, JobType jobType, String routingKey, Map<String, Object> payload) {
        // 规范 §7：消息必带 jobId
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("jobNo", jobNo);
        body.put("jobType", jobType.name());
        if (payload != null) {
            body.putAll(payload);
        }
        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, routingKey, body);
            log.info("任务 {} 已投递到 {}（{}）", jobNo, RabbitMqConfig.EXCHANGE, routingKey);
        } catch (RuntimeException e) {
            log.error("任务 {} 投递失败（队列 {}）：{}", jobNo, routingKey, e.getMessage());
            jobAppService.markDispatchFailure(jobId, e.getMessage());
        }
    }
}
