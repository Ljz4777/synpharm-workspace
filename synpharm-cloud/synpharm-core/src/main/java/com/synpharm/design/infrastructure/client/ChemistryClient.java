package com.synpharm.design.infrastructure.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * 计算层化学计算客户端（{@code /v1/evaluate/properties} 与 {@code /v1/chemistry/standardize}）。
 *
 * <p><b>失败一律抛异常，不返回空集合</b>。这是生成阶段最容易做错的地方：
 * S5 去重与 S6 粗筛如果拿不到真实指标却"继续执行"，产出的候选看起来完整，
 * 实际上一个约束都没被检查过 —— 比直接失败危险得多。
 *
 * <p>调用方（流水线）必须把异常翻译成 {@code run.status=FAILED} 并保留原因。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChemistryClient {

    private final WebClient fastApiWebClient;

    /** 化学计算比探测慢：500 个分子 × 14 指标约 1.5s，留足余量 */
    @Value("${fastapi.chemistry-timeout:60000}")
    private long chemistryTimeoutMs;

    /**
     * 批量理化/成药性计算。
     *
     * @param molecules 待算分子（ref 用于回传对应关系）
     * @param metrics   指标码；{@code null} 表示全部
     * @param includeDiversity 是否顺带算同批最大相似度
     * @throws ChemistryUnavailableException 引擎不可达 / 超时 / 返回体异常
     */
    public ChemistryDtos.PropertiesResponse properties(
            List<ChemistryDtos.MoleculeIn> molecules,
            List<String> metrics,
            boolean includeDiversity) {

        ChemistryDtos.PropertiesRequest request = new ChemistryDtos.PropertiesRequest();
        request.setMolecules(molecules);
        request.setMetrics(metrics);
        request.setIncludeDiversity(includeDiversity);

        ChemistryDtos.PropertiesResponse response = call("/v1/evaluate/properties", request,
                ChemistryDtos.PropertiesResponse.class);

        if (response == null || response.getResults() == null) {
            throw new ChemistryUnavailableException("理化计算返回体缺少 results");
        }
        log.debug("properties 完成：{} 个分子，status={}", response.getResults().size(), response.getStatus());
        return response;
    }

    /**
     * 规范化 + 可选聚类去重。
     *
     * @param dedupThreshold 为 {@code null} 时只规范化不聚类
     */
    public ChemistryDtos.StandardizeResponse standardize(
            List<ChemistryDtos.MoleculeIn> molecules,
            BigDecimal dedupThreshold) {

        ChemistryDtos.StandardizeRequest request = new ChemistryDtos.StandardizeRequest();
        request.setMolecules(molecules);
        request.setDedupThreshold(dedupThreshold);

        ChemistryDtos.StandardizeResponse response = call("/v1/chemistry/standardize", request,
                ChemistryDtos.StandardizeResponse.class);

        if (response == null || response.getResults() == null) {
            throw new ChemistryUnavailableException("规范化返回体缺少 results");
        }
        return response;
    }

    private <T> T call(String uri, Object body, Class<T> type) {
        try {
            return fastApiWebClient.post()
                    .uri(uri)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(type)
                    .timeout(Duration.ofMillis(chemistryTimeoutMs))
                    .block();
        } catch (WebClientResponseException e) {
            // 4xx 通常是请求本身有问题（如请求了不支持的指标），把计算层的原因带出来
            throw new ChemistryUnavailableException(
                    "计算层拒绝请求（HTTP " + e.getStatusCode().value() + "）：" + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new ChemistryUnavailableException("化学计算引擎不可达：" + e.getMessage(), e);
        }
    }

    /** 化学计算不可用（引擎没起 / 超时 / 参数被拒 / 返回体异常） */
    public static class ChemistryUnavailableException extends RuntimeException {
        public ChemistryUnavailableException(String message) {
            super(message);
        }

        public ChemistryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
