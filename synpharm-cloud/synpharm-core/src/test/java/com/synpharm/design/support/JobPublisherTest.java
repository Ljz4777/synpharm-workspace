package com.synpharm.design.support;

import com.synpharm.design.application.JobAppService;
import com.synpharm.design.domain.model.JobType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 任务投递测试。
 *
 * <p>红线 R5：**事务内只做本地写**。若在事务里投递，事务回滚后计算层已经在干活，而库里
 * 没有任务行 —— 消息与事实不一致。所以这里的第一条断言是「事务活跃时
 * {@code convertAndSend} 一次都不能发生」，投递必须等到 {@code afterCommit}。
 *
 * <p>第二条：投递失败**不能让请求失败**（事务已提交，抛错只会给调用方一个误导的 500），
 * 必须降级成 {@code markDispatchFailure}，把「未派发」显式记到任务行上。
 *
 * <p>第三条：{@code STRUCTURE_FETCH}/{@code EXPORT} 在《03》§7 没有队列，
 * 既不投递也不报错（{@code routingKey()} 对它们是直接抛错的，绝不能被调用到）。
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobPublisherTest {

    private static final long JOB_ID = 9L;
    private static final String JOB_NO = "J261014153045A7F3";

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private JobAppService jobAppService;

    @InjectMocks private JobPublisher publisher;

    @AfterEach
    void tearDown() {
        // 事务同步是 ThreadLocal，用例之间必须清干净，否则「无事务」的用例会假命中
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("有事务同步：注册期不发送，afterCommit 才按路由键投到 synpharm.exchange，消息体带 jobId/jobNo/jobType 与附加字段")
    void publishDefersUntilAfterCommitWhenTransactionActive() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roundId", 3L);
        payload.put("candidateIds", List.of(11L, 12L));

        TransactionSynchronizationManager.initSynchronization();
        publisher.publishAfterCommit(JOB_ID, JOB_NO, JobType.GENERATION, payload);

        // 事务还没提交：一条消息都不许出去
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMqConfig.EXCHANGE), eq(JobType.GENERATION.routingKey()),
                bodyCaptor.capture());
        assertThat(RabbitMqConfig.EXCHANGE).isEqualTo("synpharm.exchange");
        assertThat(JobType.GENERATION.routingKey()).isEqualTo("design.generate");

        Map<?, ?> rawBody = (Map<?, ?>) bodyCaptor.getValue();
        assertThat(rawBody).hasSize(5);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) rawBody;
        assertThat(body)
                .containsEntry("jobId", JOB_ID)
                .containsEntry("jobNo", JOB_NO)
                .containsEntry("jobType", JobType.GENERATION.name())
                .containsEntry("roundId", 3L)
                .containsEntry("candidateIds", List.of(11L, 12L));
        verify(jobAppService, never()).markDispatchFailure(anyLong(), anyString());
    }

    @Test
    @DisplayName("无事务同步：立即发送，消息体不含附加字段时也照常投递")
    void publishSendsImmediatelyWithoutTransaction() {
        publisher.publishAfterCommit(JOB_ID, JOB_NO, JobType.EVALUATION, null);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMqConfig.EXCHANGE), eq("design.evaluate"),
                bodyCaptor.capture());
        Map<?, ?> rawBody = (Map<?, ?>) bodyCaptor.getValue();
        assertThat(rawBody).hasSize(3);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) rawBody;
        assertThat(body)
                .containsEntry("jobId", JOB_ID)
                .containsEntry("jobNo", JOB_NO)
                .containsEntry("jobType", JobType.EVALUATION.name());
    }

    @Test
    @DisplayName("投递抛异常：不向外抛，改为 markDispatchFailure(jobId, 原因)")
    void publishRegistersDispatchFailureInsteadOfThrowing() {
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).convertAndSend(eq(RabbitMqConfig.EXCHANGE), eq("design.pocket"), any(Object.class));

        assertThatCode(() -> publisher.publishAfterCommit(JOB_ID, JOB_NO, JobType.POCKET_DETECT, Map.of()))
                .doesNotThrowAnyException();

        verify(jobAppService).markDispatchFailure(JOB_ID, "broker down");
    }

    @Test
    @DisplayName("投递抛异常且处于事务同步中：afterCommit 里也要吞掉异常，只登记失败")
    void publishRegistersDispatchFailureInsideAfterCommit() {
        doThrow(new AmqpException("channel closed"))
                .when(rabbitTemplate).convertAndSend(eq(RabbitMqConfig.EXCHANGE), eq("design.report"), any(Object.class));
        TransactionSynchronizationManager.initSynchronization();
        publisher.publishAfterCommit(JOB_ID, JOB_NO, JobType.REPORT, Map.of());

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        assertThatCode(() -> synchronizations.forEach(TransactionSynchronization::afterCommit))
                .doesNotThrowAnyException();
        verify(jobAppService).markDispatchFailure(JOB_ID, "channel closed");
    }

    @Test
    @DisplayName("非异步类型（STRUCTURE_FETCH/EXPORT）：不发送、不注册同步、不登记失败、也不抛异常")
    void publishSkipsNonAsyncTypes() {
        TransactionSynchronizationManager.initSynchronization();

        assertThatCode(() -> {
            publisher.publishAfterCommit(JOB_ID, JOB_NO, JobType.STRUCTURE_FETCH, Map.of());
            publisher.publishAfterCommit(JOB_ID, JOB_NO, JobType.EXPORT, Map.of());
        }).doesNotThrowAnyException();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(jobAppService, never()).markDispatchFailure(anyLong(), anyString());
        // routingKey() 对这两类会直接抛错，所以「一条同步都没注册」也证明它根本没被调用
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

}
