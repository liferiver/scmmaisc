# ===== 构建阶段：Maven 打包 =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先拷贝 pom.xml 单独拉取依赖，利用 Docker 层缓存加速迭代构建
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

# ===== 运行阶段：JRE =====
FROM eclipse-temurin:17-jre
WORKDIR /app

# 时区（@Scheduled 保留清理与日志时间戳使用 Asia/Shanghai）
ENV TZ=Asia/Shanghai
RUN apt-get update && apt-get install -y --no-install-recommends tzdata \
    && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo $TZ > /etc/timezone \
    && rm -rf /var/lib/apt/lists/*

# 数据库连接全部走环境变量（对应 application.yml 的 DB_URL/DB_USER/DB_PASSWORD）
ENV DB_URL='jdbc:mysql://localhost:3306/scmmaisc?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
    DB_USER=root \
    DB_PASSWORD=''

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
