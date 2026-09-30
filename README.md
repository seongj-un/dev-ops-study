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

`DB_PASSWORD`는 개발용 기본값을 두지 않았다. 빠뜨리면 앱이 시작 단계에서 인증 오류로 죽는다 (compose는 항상 넘겨 주고, Testcontainers를 쓰는 테스트와 `bootTestRun`은 접속 정보를 직접 넣는다).

Kubernetes에서는 `MANAGEMENT_SERVER_PORT=8081`을 주입해 위의 운영 엔드포인트(`/actuator/**`)를 앱 포트(8080)와 다른 8081 포트로 옮긴다.

## 로컬 개발

```bash
./gradlew bootTestRun   # compose 없이 앱만 실행. Testcontainers가 PostgreSQL·Redis를 띄워 연결해 준다 (Docker 필요)
```

앱은 `http://localhost:8080`에서 뜨고, 접속 정보는 Testcontainers가 직접 넣어 주므로 `DB_*`·`REDIS_*` 환경 변수는 필요 없다. 종료(Ctrl+C)하면 컨테이너도 함께 정리된다.

## 테스트

```bash
./gradlew test   # Docker 필요 (Testcontainers가 PostgreSQL·Redis를 띄운다)
```
