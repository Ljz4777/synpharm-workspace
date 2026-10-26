package com.synpharm.design.support;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 设计域 MQ 拓扑。
 *
 * <p>沿用单体既有约定：统一 direct 交换机 {@code synpharm.exchange}（durable），
 * 每个域用独立队列 + 独立死信。队列与路由键<strong>逐字取自</strong>
 * 《03-实现级详细设计》§7 的队列表，不要另起名字 —— 计算层按那张表实现消费者。
 *
 * <p><b>方向说明</b>：{@code design.generate/evaluate/pocket/report/feedback} 是
 * <b>设计服务 → 计算层</b>的任务下发队列，本服务只投递、不消费（消费在计算层）。
 * 反向的<b>计算层 → 设计服务</b>回报通道在 §7 里没有定义，但计算层是独立服务、
 * 不能直连本服务的库，而规范又要求「DB 为权威」，因此回报只能走 MQ。
 * 本类补齐 {@code design.job.status} 队列并已登记进方案文档。
 *
 * @author SynPharm Team
 */
@Configuration
public class RabbitMqConfig {

    /** 与单体共用的交换机 */
    public static final String EXCHANGE = "synpharm.exchange";

    // ---- 设计服务 → 计算层（规范 §7 队列表）---------------------------------
    public static final String QUEUE_GENERATE = "design.generate";
    public static final String QUEUE_EVALUATE = "design.evaluate";
    public static final String QUEUE_POCKET = "design.pocket";
    public static final String QUEUE_REPORT = "design.report";
    public static final String QUEUE_FEEDBACK = "design.feedback";

    // ---- 计算层 → 设计服务（本类补齐的反向回报通道）-------------------------
    public static final String QUEUE_JOB_STATUS = "design.job.status";

    public static final String DLQ = "design.dlq";

    @Bean
    public DirectExchange synpharmExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue designGenerateQueue() {
        return taskQueue(QUEUE_GENERATE);
    }

    @Bean
    public Queue designEvaluateQueue() {
        return taskQueue(QUEUE_EVALUATE);
    }

    @Bean
    public Queue designPocketQueue() {
        return taskQueue(QUEUE_POCKET);
    }

    @Bean
    public Queue designReportQueue() {
        return taskQueue(QUEUE_REPORT);
    }

    @Bean
    public Queue designFeedbackQueue() {
        return taskQueue(QUEUE_FEEDBACK);
    }

    /** 回报队列也配死信：回报消息处理失败同样需要兜底，不能悄悄丢 */
    @Bean
    public Queue designJobStatusQueue() {
        return taskQueue(QUEUE_JOB_STATUS);
    }

    /** 死信队列本身不再转投死信（否则失败消息会自己循环） */
    @Bean
    public Queue designDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding bindGenerate(Queue designGenerateQueue, DirectExchange synpharmExchange) {
        return BindingBuilder.bind(designGenerateQueue).to(synpharmExchange).with(QUEUE_GENERATE);
    }

    @Bean
    public Binding bindEvaluate(Queue designEvaluateQueue, DirectExchange synpharmExchange) {
        return BindingBuilder.bind(designEvaluateQueue).to(synpharmExchange).with(QUEUE_EVALUATE);
    }

    @Bean
    public Binding bindPocket(Queue designPocketQueue, DirectExchange synpharmExchange) {
        return BindingBuilder.bind(designPocketQueue).to(synpharmExchange).with(QUEUE_POCKET);
    }

    @Bean
    public Binding bindReport(Queue designReportQueue, DirectExchange synpharmExchange) {
        return BindingBuilder.bind(designReportQueue).to(synpharmExchange).with(QUEUE_REPORT);
    }

    @Bean
    public Binding bindFeedback(Queue designFeedbackQueue, DirectExchange synpharmExchange) {
        return BindingBuilder.bind(designFeedbackQueue).to(synpharmExchange).with(QUEUE_FEEDBACK);
    }

    @Bean
    public Binding bindJobStatus(Queue designJobStatusQueue, DirectExchange synpharmExchange) {
        return BindingBuilder.bind(designJobStatusQueue).to(synpharmExchange).with(QUEUE_JOB_STATUS);
    }

    @Bean
    public Binding bindDlq(Queue designDlq, DirectExchange synpharmExchange) {
        return BindingBuilder.bind(designDlq).to(synpharmExchange).with(DLQ);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }

    private Queue taskQueue(String name) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(DLQ)
                .build();
    }
}
