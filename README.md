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

## 로컬 Kubernetes (k3d)

k3d(k3s를 Docker 컨테이너 안에서 띄우는 도구)로 클러스터를 만들고, Helm 차트(`deploy/helm/shortener`)로 앱과 클러스터 안의 PostgreSQL·Redis를 함께 올린다. Docker, k3d, kubectl, Helm 4가 필요하다.
Docker VM 메모리가 작아서(2.84GiB) 리소스를 작게 잡았다 (앱 요청 384Mi·한도 512Mi, PostgreSQL 한도 256Mi, Redis 한도 128Mi). 클러스터가 떠 있는 동안에는 로컬 이미지 빌드를 피한다 (빌드가 메모리를 많이 써서 OOM 위험이 있다).

```bash
# 클러스터 만들기 (맥의 8090 포트 → Traefik 80)
k3d cluster create --config deploy/k3d/cluster.yaml

# 네임스페이스와 DB 비밀번호 Secret (비밀번호는 Git에 넣지 않고 클러스터에만 둔다)
kubectl create namespace shortener
kubectl -n shortener create secret generic shortener-db --from-literal=password="$(openssl rand -base64 24)"

# 설치. image.tag는 GHCR에 올라간 이미지의 태그, 곧 main 커밋 SHA 전체 40자다 (비워 두면 차트가 실패한다)
helm upgrade --install shortener deploy/helm/shortener -n shortener \
  --set image.tag=<커밋 SHA 40자> \
  --set database.existingSecret=shortener-db \
  --wait --timeout 5m
```

확인:

```bash
kubectl -n shortener get pods,svc,ingress,hpa
helm test shortener -n shortener   # readiness 엔드포인트를 Service의 management 포트(8081)로 호출한다
curl -i -X POST http://shortener.localhost:8090/api/v1/urls \
  -H 'Content-Type: application/json' -d '{"url": "https://example.com"}'
```

- 첫 배포에서 앱이 DB보다 먼저 뜨면 몇 번 재시작한 뒤 자리를 잡는다 (정상).
- actuator는 management 포트(8081)라 Ingress로는 닿지 않는다: `kubectl -n shortener port-forward svc/shortener 8081:8081` 후 `curl localhost:8081/actuator/health/readiness`.
- 새 버전은 같은 `helm upgrade --install` 명령에서 SHA만 바꿔 다시 실행한다. 롤링 업데이트라서 새 파드가 Ready가 된 뒤에야 옛 파드가 내려간다.
- 지우기: `helm uninstall shortener -n shortener`. PostgreSQL의 PVC(`data-shortener-postgresql-0`)는 남는다. 데이터까지 지우려면 `kubectl -n shortener delete pvc data-shortener-postgresql-0`.
  남은 PVC에는 처음 만들 때의 비밀번호가 들어 있어서, Secret을 바꿔 다시 설치하면 앱이 인증에 실패한다. 클러스터 전체는 `k3d cluster delete devops-study`.
- 쓰지 않을 때는 `k3d cluster stop devops-study`로 멈춰 메모리를 돌려준다 (클러스터 안의 데이터는 남는다). 다시 켤 때는 `k3d cluster start devops-study`.
- HPA의 최대 파드 수는 2다. 3개면 메모리 한도의 합(앱 512Mi × 3 + PostgreSQL 256Mi + Redis 128Mi)이 이 Docker VM(약 2.84GiB)의 여유를 넘어서, VM 전체가 메모리 부족에 빠질 수 있다. 더 늘리려면 Docker 메모리부터 늘린다.

주요 값 (`deploy/helm/shortener/values.yaml`):

| 값 | 설명 |
|---|---|
| `image.tag` | 필수. 이미지의 커밋 SHA 40자 (그 밖의 값이면 차트가 실패한다) |
| `database.existingSecret` / `database.password` | DB 비밀번호. 둘 중 하나는 필수 (미리 만든 Secret을 가리키는 `existingSecret`을 권장) |
| `baseUrl` | 응답의 `shortUrl` 앞에 붙는 공개 주소 (`SHORTENER_BASE_URL`) |
| `ingress.host` | Traefik이 이 릴리스로 라우팅할 호스트 이름 (기본 `shortener.localhost`) |
| `autoscaling.*` | HPA (기본 1~2개, CPU 사용률 목표 70%) |
| `postgresql.enabled` / `redis.enabled` | `false`이면 클러스터 안의 PostgreSQL·Redis를 만들지 않는다. 그때는 `database.host`·`redis.host`에 외부 주소를 넣는다 |

차트를 고칠 때는 클러스터 없이 정적으로 검증한다 (kubeconform은 Docker로 돌린다):

```bash
SHA=$(printf 'a%.0s' {1..40})   # 형식만 맞춘 가짜 SHA
helm lint deploy/helm/shortener --strict --set image.tag=$SHA --set database.password=x
helm template shortener deploy/helm/shortener --set image.tag=$SHA --set database.existingSecret=shortener-db \
  | docker run -i --rm ghcr.io/yannh/kubeconform:v0.8.0@sha256:faffaf43f95aa6425306e1ab8d6fcad72acb9049158f38e574c085ea1ec0f64e -strict -summary -kubernetes-version 1.35.0 -
```

`image.tag`가 없거나 커밋 SHA 형식이 아니면(`latest` 등), 또는 비밀번호가 없으면 `helm template`이 안내 메시지와 함께 실패한다 (`helm lint`는 필수 값이 빠져도 경고만 낸다).
