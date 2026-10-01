# syntax=docker/dockerfile:1
# 위 지시어는 파서 지시어라서 반드시 파일의 첫 줄이어야 한다. 엔진(BuildKit)에 내장된 해석기 대신 docker/dockerfile:1 이미지(최신 1.x)로 이 파일을 해석하게 해서,
# RUN --mount 같은 문법을 Docker 엔진 버전과 상관없이 같은 방식으로 해석한다.

# 1단계용 멀티스테이지 Dockerfile.
# 빌드 스테이지(JDK + Gradle)에서 jar를 만들어 풀어 두고, 실행 스테이지(JRE)에는 그 결과물만 복사한다.
# 그래서 최종 이미지에는 JDK, Gradle, 소스 코드가 없고, root가 아닌 UID 10001로 실행된다.

# ---------- 1) 빌드 스테이지 ----------
# 베이스 이미지는 "태그@digest"로 고정한다. 태그는 가리키는 이미지가 바뀔 수 있다: 같은 태그(25.0.4.1_1-jdk-noble)를 OS 패키지 보안 패치 등을 넣어 다시 빌드해 덮어쓰기 때문이다.
# digest는 이미지 내용의 해시라서 바뀌지 않는다. 그래서 언제 어디서 빌드해도 같은 베이스가 나오고, 누가 태그를 다른 이미지로 바꿔치기해도 빌드에 영향이 없다.
# digest가 있으면 Docker는 태그를 무시하고 digest로만 이미지를 찾는다. 태그는 사람이 읽으라고 남긴 표시일 뿐이라 새 버전으로 옮길 때는 둘을 함께 바꿔야 한다(Dependabot이 PR로 함께 올려 준다).
# 이 digest는 아키텍처별 이미지가 아니라 멀티 아키텍처 이미지 인덱스의 것이라서, arm64(맥)와 amd64(CI 러너) 어디서나 같은 줄로 통한다.
#
# --platform=$BUILDPLATFORM: 이 스테이지를 이미지가 실행될 CPU(타깃 플랫폼)가 아니라, 빌드를 돌리는 머신(빌더)의 플랫폼에서 실행하라는 뜻이다.
# BUILDPLATFORM은 BuildKit이 채워 주는 값(맥은 linux/arm64, CI 러너는 linux/amd64)이고, 위 인덱스에서 그 플랫폼의 이미지가 골라진다.
# 이 스테이지가 만드는 jar는 CPU 종류와 무관한 바이트코드라서 amd64용과 arm64용이 똑같다. 그래서 Gradle 빌드는 빌더의 네이티브 CPU에서 한 번만 돌고,
# 플랫폼마다 따로 만드는 것은 아래 실행 스테이지(작은 JRE 이미지와 그 위의 얇은 레이어)뿐이다.
# 이 옵션이 없으면 이 스테이지도 타깃 플랫폼마다 한 번씩 돈다. 그러면 빌더와 CPU가 다른 타깃(amd64 러너에서 arm64 이미지를 만드는 경우)의 Gradle 빌드 전체가
# QEMU 에뮬레이션 위에서 돌아서, 같은 jar를 두 번 만들 뿐 아니라 몇 배나 느려진다.
# 다만 "jar가 플랫폼과 무관하다"는 전제는 의존성이 CPU별 네이티브 라이브러리를 담지 않을 때만 맞다(지금은 없다). 그런 의존성을 추가하면 이 스테이지를 다시 따져 봐야 한다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:f6366ccac38ceae180280ad7012d18a15e8031548a430dc2bae06631d9e88ed0 AS build

WORKDIR /app

# 빌드 스크립트와 Gradle 래퍼만 먼저 복사해서 의존성을 받는다. COPY는 복사할 파일의 내용이, RUN은 명령어와 그 앞 단계가 같으면 캐시를 재사용한다.
# src를 아직 복사하지 않았으므로 소스만 고친 빌드는 이 단계들을 캐시에서 재사용하고(빌드 로그에 CACHED로 나온다), build.gradle.kts를 고쳤을 때만 이 단계들이 다시 돈다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle

# --mount=type=cache는 Gradle 홈(/root/.gradle)을 빌더가 따로 보관하는 캐시 디렉터리에 연결한다. 이 디렉터리는 이미지 레이어에 들어가지 않고 빌드가 끝나도 남는다.
# 레이어 캐시와는 다른 캐시다. 레이어 캐시는 "이 RUN을 통째로 건너뛸지"를 정하고, 캐시 마운트는 "건너뛰지 못하고 다시 돌 때 Gradle 배포판과 이미 받은 라이브러리를 재사용할지"를 정한다.
# 대신 여기서 받은 것은 이미지 레이어가 아니라 마운트에 쌓이므로, 캐시 디렉터리가 비어 있는 새 빌더(예: 새 CI 러너)에서는 처음부터 다시 받는다.
# 이 RUN이 미리 받아 두는 것: ./gradlew가 처음 실행될 때 내려받는 Gradle 배포판, 빌드 스크립트를 해석하는 데 필요한 플러그인, 그리고 dependencies 태스크(의존성 트리를 출력하는 태스크)가 읽는 모든 의존성의 메타데이터(POM).
# 라이브러리 jar 본체는 컴파일할 때(아래 bootJar) 받는다. 수천 줄짜리 트리 출력은 버린다.
# --no-daemon: Gradle 데몬은 다음 빌드에서 재사용하려고 살려 두는 백그라운드 JVM이다. 이 RUN이 끝나면 재사용할 일이 없으므로 빌드가 끝날 때 함께 종료하게 한다.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon > /dev/null

COPY src src

# GIT_COMMIT은 이 jar를 어느 커밋에서 빌드했는지 /actuator/info(build-info)에 남기려고 CI가 넘기는 빌드 인자다. 안 넘기면 local이다.
# ARG는 선언한 뒤의 모든 RUN에 환경 변수로 전달되고, 값이 바뀌면 그 뒤 RUN들은 변수를 쓰지 않더라도 캐시가 무효화된다.
# 그래서 위의 의존성 단계 앞이 아니라 bootJar 바로 앞에 선언한다. 앞에 두면 커밋이 바뀔 때마다 의존성 단계까지 다시 돈다.
# 값은 build 스테이지의 RUN에서만 환경 변수로 쓰이고 실행 스테이지로는 넘어가지 않는다(최종 이미지의 환경 변수에는 GIT_COMMIT이 없다).
ARG GIT_COMMIT=local
RUN --mount=type=cache,target=/root/.gradle \
    GIT_COMMIT="$GIT_COMMIT" ./gradlew bootJar --no-daemon

# Spring Boot fat jar(수십 MB)는 라이브러리와 우리 클래스가 한 파일에 섞여 있어서, 코드를 한 줄만 고쳐도 파일 전체가 바뀌고 이미지 레이어도 통째로 새로 만들어진다.
# jarmode tools의 extract --layers는 이 jar를 변경 빈도별 디렉터리로 풀어 준다:
#   dependencies(외부 라이브러리, 거의 안 바뀜), spring-boot-loader(Spring Boot 로더), snapshot-dependencies(-SNAPSHOT 버전 라이브러리), application(우리 코드, 자주 바뀜)
# 풀린 application/app.jar는 우리 클래스만 든 얇은 jar(수십 KB)이고, 매니페스트의 Main-Class와 Class-Path(lib/)로 라이브러리를 찾으므로 java -jar app.jar로 그대로 실행된다.
RUN java -Djarmode=tools -jar build/libs/app.jar extract --layers --destination build/extracted

# ---------- 2) 실행 스테이지 ----------
# 실행에는 JRE만 있으면 된다. JRE 이미지에는 javac, jar 같은 개발 도구가 없어서 JDK 이미지보다 작고 공격 표면도 작다. 고정 방식(태그@digest)은 위와 같다.
# 이 스테이지에는 --platform을 주지 않는다. 그래서 타깃 플랫폼마다 그 CPU용 JRE 이미지 위에 따로 만들어지고, 그것이 그 플랫폼의 최종 이미지가 된다.
# (위 빌드 스테이지가 만든 jar와 달리) JVM과 OS 라이브러리는 이미지가 실행될 CPU에 맞는 네이티브 바이너리여야 하기 때문이다.
FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:693fdaf83831eeeefd9709eae44c8b8706622652f972cf5903bd0e481bbf6ad3 AS runtime

# 임시 조치: 베이스 이미지의 libssl3t64·openssl이 3.0.13-0ubuntu3.15라서, 이대로면 CI의 Trivy 게이트가 HIGH CVE-2026-84782(3.0.13-0ubuntu3.16에서 수정)로 실패한다.
# 위 digest의 이미지는 Ubuntu가 수정판을 내기 전에 빌드됐고 업스트림이 아직 다시 빌드하지 않아서, digest를 올려서는 고칠 수 없다. 그래서 이 두 패키지만 Ubuntu 보안 업데이트로 올린다(--only-upgrade는 이미 깔린 패키지만 올리고, apt 목록은 같은 RUN에서 지워야 레이어에 남지 않는다).
# 앱의 TLS는 JDK 내장 JSSE가 처리하고 네이티브 OpenSSL(netty-tcnative 등)은 로드하지 않는다(build.gradle.kts에 그런 의존성이 없다). 그래서 앱이 거치는 경로를 막으려는 것이 아니라 알려진 취약 패키지를 이미지에 싣지 않으려는 조치다.
# 베이스 digest가 3.0.13-0ubuntu3.16 이상을 담은 재빌드로 올라가면(Dependabot의 docker 업데이트 PR이 제안한다) 이 RUN과 주석을 지운다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade libssl3t64 openssl \
    && rm -rf /var/lib/apt/lists/*

# root가 아닌 전용 계정으로 실행해서, 앱이 뚫려도 컨테이너 안에서 root 권한을 얻지 못하게 한다.
# UID/GID를 숫자 10001로 고정하는 이유는 두 가지다.
#  1) Kubernetes의 runAsNonRoot: true는 Pod에 runAsUser가 없으면 이미지의 USER가 숫자일 때만 root가 아님을 검증할 수 있다. 이름(app)이면 kubelet이 "non-numeric user"라며 컨테이너 시작을 거부한다.
#  2) user namespace를 쓰지 않으면 컨테이너의 UID는 호스트에서도 같은 번호의 UID로 취급된다. 1000번대는 호스트의 일반 사용자와 겹치기 쉬워서 큰 번호를 쓴다.
# --system과 nologin 셸은 사람이 로그인하는 계정이 아니라 프로세스만 돌리는 서비스 계정을 만든다(홈 디렉터리와 비밀번호 만료 정보가 없다).
# 10001은 시스템 UID 범위(999 이하) 밖이라 useradd가 경고를 출력하지만, 계정은 정상적으로 만들어진다.
# 지금 실행 스테이지에서 타깃 플랫폼의 바이너리를 실제로 실행하는 단계는 둘뿐이다: 위의 RUN apt-get(openssl 업그레이드)과 이 RUN(sh, groupadd, useradd). 빌더와 CPU가 다른 타깃이면 이 두 단계만 QEMU 에뮬레이션 위에서 돈다.
# 아래 WORKDIR, COPY, USER, EXPOSE, ENTRYPOINT는 디렉터리를 만들거나 파일을 옮기거나 이미지 설정을 적을 뿐 프로세스를 실행하지 않아서 에뮬레이션이 필요 없다.
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid 10001 --shell /usr/sbin/nologin app

WORKDIR /app

# 변경 빈도가 낮은 것부터 높은 것 순서로, COPY마다 별도 레이어로 쌓는다. 어떤 레이어가 바뀌면 그 뒤 단계의 캐시가 모두 무효화되므로, 자주 바뀌는 것을 위에 둬야 아래 레이어를 재사용할 수 있다.
# 그래서 코드만 고친 빌드는 맨 위 application 레이어(수십 KB)만 새로 만들고, 수십 MB인 dependencies 레이어는 캐시에서 그대로 가져온다. 레이어가 그대로라 레지스트리 푸시와 노드의 이미지 풀에서도 바뀐 레이어만 오간다.
# (COPY의 캐시는 파일 내용이 같으면 jar를 다시 풀어 디렉터리 수정 시각이 달라져도 유지된다. 다만 캐시 없이 처음부터 빌드하면 그 수정 시각이 레이어에 담겨서, 내용이 같아도 레이어 digest가 달라진다.)
# spring-boot-loader와 snapshot-dependencies는 지금 비어 있다(추출된 얇은 jar는 로더 없이 Main-Class를 바로 실행하고, -SNAPSHOT 의존성도 없다).
# 그래도 COPY를 남겨 두면 그런 의존성이 생겼을 때 Dockerfile을 고치지 않아도 자기 레이어로 분리된다.
# --chown을 쓰지 않아 파일은 root 소유로 남는다. 실행 계정(10001)은 읽기만 할 수 있어서, 앱이 뚫려도 자기 jar를 고쳐 쓸 수 없다.
COPY --from=build /app/build/extracted/dependencies/ ./
COPY --from=build /app/build/extracted/spring-boot-loader/ ./
COPY --from=build /app/build/extracted/snapshot-dependencies/ ./
COPY --from=build /app/build/extracted/application/ ./

# 숫자 UID:GID로 지정한다(위 이유). 여기서부터 ENTRYPOINT의 프로세스는 root가 아니다.
USER 10001:10001

# EXPOSE는 "이 컨테이너는 8080을 쓴다"는 문서일 뿐 포트를 열지는 않는다(실제로 여는 건 docker run -p, compose ports, Kubernetes Service 몫이다).
# 리눅스 커널 기본값은 1024 미만(특권) 포트를 root만 열 수 있게 한다. Docker는 컨테이너 안에서 이 제한을 풀어 두지만(ip_unprivileged_port_start=0) 런타임마다 달라서,
# 비root로 실행하는 앱은 어디서나 뜨도록 8080처럼 높은 포트를 쓴다.
EXPOSE 8080

# exec 형식(JSON 배열)이라 java가 PID 1로 직접 뜬다. docker stop과 Kubernetes는 컨테이너의 PID 1에 SIGTERM을 보낸다.
# 셸 형식(ENTRYPOINT java -jar ...)은 /bin/sh -c로 감싸져 셸이 PID 1이 되고 java는 그 자식이 되는데, 셸은 SIGTERM을 자식에게 전달하지 않는다.
# 그러면 java는 종료 신호를 못 받고 유예 시간이 지난 뒤 SIGKILL로 죽어서 graceful shutdown을 할 수 없다. JVM은 SIGTERM 핸들러를 직접 등록하므로 PID 1이어도 신호를 받는다.
# -XX:MaxRAMPercentage=75.0: JVM은 컨테이너의 메모리 한도(cgroup)를 인식하지만, 힙 최대 크기의 기본값이 그 한도의 25%라서 앱 전용 컨테이너에서는 메모리 대부분이 놀게 된다.
# 75%로 올리고 나머지 25%는 힙 밖 메모리(메타스페이스, 스레드 스택, 다이렉트 버퍼, 코드 캐시)에 남겨 둔다. 한도가 없으면 호스트 메모리가 기준이 되므로 실행할 때 메모리 한도를 꼭 지정한다.
# 이 옵션을 JAVA_TOOL_OPTIONS 환경 변수로 주면 JVM이 시작할 때마다 "Picked up JAVA_TOOL_OPTIONS" 줄을 출력하므로, 명령행에 직접 적는다.
# 베이스 이미지의 ENTRYPOINT(/__cacert_entrypoint.sh)는 이 줄로 대체된다. 그 스크립트는 USE_SYSTEM_CA_CERTS를 켰을 때만 시스템 CA 인증서를 JVM 트러스트스토어에 합쳐 주고 아니면 명령을 그대로 실행할 뿐이라,
# 지금은 잃는 것이 없다. 사설 CA 인증서가 필요해지면 그 기능을 따로 챙겨야 한다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
