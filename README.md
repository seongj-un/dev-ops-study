# shortener

데브옵스 공부용 URL 단축 API. 앱 기능은 최소로 두고 CI/CD·Kubernetes·GitOps·관측성을 실습하는 배포 대상이다.

## 실행

```bash
docker compose up --build
```

앱 `http://localhost:8080`, PostgreSQL, Redis가 함께 뜬다. 끄기: `docker compose down` (데이터까지 지우려면 `-v`).

## API

```bash
# 단축 URL 만들기 → 201
curl -i -X POST localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://example.com"}'

# curl은 URL의 {code}를 글로빙 패턴으로 해석하므로, 코드를 변수에 담아 쓴다
CODE=aB3xY9z  # 위 응답의 "code" 값

# 리다이렉트 → 302 Location: https://example.com
curl -i localhost:8080/$CODE

# 조회수 확인
curl localhost:8080/api/v1/urls/$CODE
```

## 운영 엔드포인트

| 경로 | 용도 |
|---|---|
| `/actuator/health/liveness` | 프로세스가 살아 있는지 (죽었으면 재시작 대상) |
| `/actuator/health/readiness` | 트래픽을 받을 준비가 됐는지 (DB 연결 포함, Redis 제외) |
| `/actuator/health` | 전체 상태 (DB, Redis 등 구성요소별). Redis가 죽으면 503을 돌려주므로 프로브로 쓰면 안 된다. 프로브에는 위의 liveness/readiness를 쓴다 |
| `/actuator/info` | 빌드 버전 |
| `/actuator/prometheus` | Prometheus 메트릭 |

## 설정 (환경 변수)

| 변수 | 기본값 |
|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `shortener` |
| `DB_USERNAME` | `shortener` |
| `DB_PASSWORD` | 없음 (필수) |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |
| `SHORTENER_BASE_URL` | `http://localhost:8080` |
| `SHORTENER_CACHE_TTL` | `24h` |
| `SHORTENER_FAULT_ERROR_RATE` | `0` (꺼짐). 훈련용이다: [장애 주입](#장애-주입-훈련용) |

`DB_PASSWORD`는 개발용 기본값을 두지 않았다. 빠뜨리면 앱이 시작 단계에서 인증 오류로 죽는다 (compose는 항상 넘겨 주고, Testcontainers를 쓰는 테스트와 `bootTestRun`은 접속 정보를 직접 넣는다).
시작할 때 DB에 연결하지 못하면 앱이 최대 약 53초 동안 다시 시도한다(`spring.flyway.connect-retries`). DB가 그 안에 뜨면 앱은 죽지 않고 붙는다. 더 늦게 뜨면(예: 새 클러스터에서 PostgreSQL 이미지를 느리게 받을 때) 앱이 죽고 Kubernetes가 컨테이너를 재시작한다. 비밀번호가 틀리거나 빠진 경우에도 약 53초가 지난 뒤에야 죽는다.

Kubernetes에서는 `MANAGEMENT_SERVER_PORT=8081`을 주입해 위의 운영 엔드포인트(`/actuator/**`)를 앱 포트(8080)와 다른 8081 포트로 옮긴다.

## 장애 주입 (훈련용)

`SHORTENER_FAULT_ERROR_RATE`(프로퍼티 `shortener.fault.error-rate`)에 0.0~1.0 값을 주면, 앱 포트로 들어온 요청 중 그 비율만큼은 컨트롤러에 닿지 않고 HTTP 500(`application/problem+json`)을 받는다. 기본값은 `0`(꺼짐)이고, 범위를 벗어난 값(음수, 1 초과)이나 숫자가 아닌 값, 빈 문자열이면 앱이 시작 단계에서 죽는다. 배포 환경에서는 설정 저장소 차트의 `fault.errorRate`가 이 변수가 되는데, 변수가 빈 문자열이면 기본값 `0`이 쓰이지 않고 앱이 죽어 파드가 CrashLoopBackOff에 빠진다. 그래서 차트는 `""`를 절대 넘기면 안 된다(끄려면 `"0"`).

카나리 자동 롤백과 SLO 알림 훈련에서 "나쁜 버전"을 흉내 내는 용도다: 설정 저장소에서 dev의 `fault.errorRate`를 올리는 PR이 곧 나쁜 버전의 배포다. **평소 운영(특히 prod)에서는 켜지 않는다.** 로컬에서는 `SHORTENER_FAULT_ERROR_RATE=0.5 ./gradlew bootTestRun`으로 켜 볼 수 있다.

- 관리 포트(Kubernetes에서는 8081)의 `/actuator/**`는 대상이 아니다. startup·liveness·readiness 프로브가 모두 이 포트를 보는데, startup·liveness가 실패하면 컨테이너가 재시작되고 readiness가 실패하면 파드가 Service 엔드포인트에서 빠져 트래픽이 끊긴다. 어느 쪽이든 카나리 분석이 봐야 할 5xx가 보이지 않는다. 관리 포트를 따로 두지 않아도(로컬) `/actuator` 경로는 건너뛴다.
- 주입한 500은 일반 5xx처럼 `http_server_requests_seconds_count{status="500"}`에 잡힌다. 컨트롤러에 닿기 전에 끊어서 `uri` 레이블은 경로 패턴 대신 `UNKNOWN`이고, SLO 규칙과 대시보드는 `uri!~"/actuator.*"`로만 거르므로 이것도 센다.
- 장애 한 건마다 카운터 `shortener_fault_injected_total`이 오르고 WARN 로그 한 줄(`fault injected`, `method`·`path` 필드)이 남는다. 켜진 채로 앱이 뜰 때도 WARN 로그 한 줄(`fault injection enabled`)을 남긴다.

## 로컬 개발

```bash
./gradlew bootTestRun   # compose 없이 앱만 실행. Testcontainers가 PostgreSQL·Redis를 띄워 연결해 준다 (Docker 필요)
```

앱은 `http://localhost:8080`에서 뜨고, 접속 정보는 Testcontainers가 직접 넣어 주므로 `DB_*`·`REDIS_*` 환경 변수는 필요 없다. 종료(Ctrl+C)하면 컨테이너도 함께 정리된다.

## 테스트

```bash
./gradlew test   # Docker 필요 (Testcontainers가 PostgreSQL·Redis를 띄운다)
```

## 배포 (GitOps)

Kubernetes 배포 정의(Helm 차트, 환경별 값, Argo CD 설정, 로컬 k3d 클러스터)는 이 저장소가 아니라 설정 저장소 [seongj-un/dev-ops-study-config](https://github.com/seongj-un/dev-ops-study-config)에 있다. 클러스터를 만들고 Argo CD를 설치하고 차트를 검증하는 방법도 그 저장소의 README를 본다. 예전에 이 저장소에 있던 Helm 차트와 k3d 설정은 그쪽으로 옮겼다.

배포는 CI가 클러스터를 건드리지 않고 Git에 "원하는 상태"만 기록하는 방식(GitOps)으로 흘러간다.

1. main에 머지하면 CI(`.github/workflows/ci.yml`)가 테스트, 이미지 빌드·취약점 스캔을 돌리고 GHCR에 이미지를 올린다. 이미지 태그는 커밋 SHA 전체 40자다.
2. 같은 CI의 `deploy-dev` 잡이 설정 저장소의 `environments/dev/values.yaml`에서 `image.tag`를 그 SHA로 바꿔 커밋한다.
3. 클러스터 안의 Argo CD가 설정 저장소의 변경을 읽어 dev 환경에 반영한다. CI는 클러스터에 접속하지 않는다.
4. prod는 사람이 설정 저장소에서 `environments/prod/values.yaml`의 `image.tag`를 dev에서 확인한 SHA로 바꾸는 PR을 올려 승격한다.

`deploy-dev` 잡은 설정 저장소에 쓰기 권한으로 등록한 배포 키(SSH)의 비공개 키를 이 저장소의 Actions 시크릿 `CONFIG_REPO_DEPLOY_KEY`에서 읽는다. 배포 키는 설정 저장소 하나에만 통한다. 이 시크릿이 없으면 `deploy-dev` 잡은 실패한다.
