# 0단계용 단일 스테이지 Dockerfile.
# JDK 전체와 Gradle 캐시, 소스 코드가 최종 이미지에 그대로 남아 크고, root로 실행된다.
# 1단계(CI)에서 멀티스테이지(빌드용 JDK → 실행용 JRE)로 바꾸고 이미지 크기를 비교한다.
FROM eclipse-temurin:25-jdk

WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "build/libs/app.jar"]
