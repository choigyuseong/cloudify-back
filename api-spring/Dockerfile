# syntax=docker/dockerfile:1

# --- Build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 캐시 최적화: 래퍼/설정 먼저 복사
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
RUN chmod +x gradlew

# (선택) 의존성 프리캐시용 더미 실행
RUN ./gradlew --version

# 소스 복사 후 빌드 (속도 위해 테스트 스킵: -x test)
COPY src ./src
RUN ./gradlew clean bootJar -x test --no-daemon

# --- Runtime stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app

# 기본 JVM/타임존 (compose에서 덮어쓰기 가능)
ENV TZ=Asia/Seoul \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# 보안상 비루트 유저
RUN addgroup --system spring && adduser --system --ingroup spring spring

# 빌드 산출물 복사
COPY --from=build /workspace/build/libs/*.jar /app/app.jar

USER spring
EXPOSE 8080

# Spring Boot 실행
ENTRYPOINT ["sh","-c","java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
