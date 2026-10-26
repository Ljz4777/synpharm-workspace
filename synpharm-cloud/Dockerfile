# =============================================================================
# SynPharm 微服务镜像 - 多阶段构建（全部服务共用一份）
#
# 构建上下文是「多模块根目录」，不是单个模块目录；用 MODULE 选择要打包的服务：
#   docker build -t synpharm/gateway:1.0.0  --build-arg MODULE=synpharm-gateway  synpharm-cloud
#   docker build -t synpharm/auth:1.0.0     --build-arg MODULE=synpharm-auth     --build-arg SERVER_PORT=8081 synpharm-cloud
#   docker build -t synpharm/predict:1.0.0  --build-arg MODULE=synpharm-predict  --build-arg SERVER_PORT=8082 synpharm-cloud
#   docker build -t synpharm/core:1.0.0     --build-arg MODULE=synpharm-core     --build-arg SERVER_PORT=8083 synpharm-cloud
#
# 阶段一（build）：Maven 编译打包（父工程 + synpharm-common + 目标模块）
# 阶段二（runtime）：精简 JRE + 非 root 运行
#
# ⚠ 必须把**所有**模块的 pom.xml 都复制进来，哪怕只打包其中一个：
#   Maven 读父 POM 的 <modules> 时会逐个校验目录存在，缺一个就报
#   "Child module ... does not exist"，整个构建失败。
# =============================================================================

# ---------- 阶段一：构建 ----------
ARG MODULE=synpharm-gateway

FROM maven:3.9.6-eclipse-temurin-17 AS build

ARG MODULE
WORKDIR /build

# 使用阿里云 Maven 镜像加速依赖下载（国内网络）
COPY maven-settings.xml /root/.m2/settings.xml

# 先只复制 POM，利用 Docker 层缓存避免每次重新下载依赖
COPY pom.xml .
COPY synpharm-common/pom.xml synpharm-common/
COPY synpharm-gateway/pom.xml synpharm-gateway/
COPY synpharm-auth/pom.xml synpharm-auth/
COPY synpharm-predict/pom.xml synpharm-predict/
COPY synpharm-core/pom.xml synpharm-core/
RUN mvn -B -q dependency:go-offline -pl synpharm-common,${MODULE}

# 只复制需要的源码；-pl 限定 reactor，未复制源码的模块不会参与构建
COPY synpharm-common/src synpharm-common/src
COPY ${MODULE}/src ${MODULE}/src
RUN mvn -B clean package -DskipTests -pl synpharm-common,${MODULE}

# ---------- 阶段二：运行 ----------
# 刻意钉 -jammy（22.04）而不是浮动的 :17-jre：后者会飘到最新 Ubuntu 发行版，
# 而 archive.ubuntu.com 在国内网络下经常超时（实测 resolute 的索引直接 Ign 掉，
# apt-get 以 100 退出，基础镜像的改动就此变成构建失败）。
FROM eclipse-temurin:17-jre-jammy

ARG MODULE
# 每个服务的端口在构建时固定下来：与 application.yml 的 ${SERVER_PORT:...} 对齐
ARG SERVER_PORT=8080
ENV SERVER_PORT=${SERVER_PORT}

# 非 root 用户 + 健康检查工具 curl
RUN useradd --system --uid 10001 --create-home appuser \
    && apt-get -o Acquire::Retries=5 update \
    && apt-get -o Acquire::Retries=5 install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 各模块 pom 的 <finalName> 就是模块名，jar 名可推导
COPY --from=build /build/${MODULE}/target/${MODULE}.jar app.jar

USER appuser

EXPOSE ${SERVER_PORT}

# 企业级健康检查：依赖 spring-boot-starter-actuator
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS "http://localhost:${SERVER_PORT}/actuator/health" || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
