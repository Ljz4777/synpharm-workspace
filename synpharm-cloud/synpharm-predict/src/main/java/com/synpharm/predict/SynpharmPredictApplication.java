package com.synpharm.predict;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 预测服务（阶段 4）。
 *
 * <p><b>当前只是框架</b>：`synpharm_predict` schema 与独立账号已就绪，但业务代码与数据表**尚未迁入** —— 
 * 预测域与 design / asset / platform 不同，它在单体里**已有实现且正在对外服务**，
 * 因此搬迁顺序必须是「**先搬代码、再搬数据**」：只要表还留在 `synpharm`，
 * 单体就还能继续服务；反过来先搬表会让单体立刻读写不到。
 *
 * @author SynPharm Team
 */
@SpringBootApplication(scanBasePackages = "com.synpharm")
@MapperScan("com.synpharm.predict.infrastructure.persistence")
public class SynpharmPredictApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynpharmPredictApplication.class, args);
    }
}
