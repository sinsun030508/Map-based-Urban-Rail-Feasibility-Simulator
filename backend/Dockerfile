# 빌드와 실행을 나눈다 — 실행 이미지에 maven·소스가 안 들어가 작고, 공격면도 줄어든다
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# pom 만 먼저 복사한다 — 소스만 고치면 의존성 내려받기 층을 다시 쓴다
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
ENV TZ=Asia/Seoul
# 자료 경로 기본값이 `../data/...` 라 작업 폴더를 /app/backend 로 두고 자료를 /app/data 에 마운트한다
WORKDIR /app/backend
COPY --from=build /src/target/railfeas-0.1.0.jar app.jar
# root 로 돌리지 않는다
RUN useradd --system --no-create-home railfeas
USER railfeas
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar", "--server.port=9090"]
