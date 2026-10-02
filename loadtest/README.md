# 부하 테스트 (k6)

URL 단축 API에 트래픽을 만드는 [k6](https://grafana.com/docs/k6/) 스크립트다. 맥에서 Docker로 돌리고, 로컬 compose·dev·prod 어디에든 같은 스크립트로 요청을 보낸다. 부하 중에 Grafana·HPA·SLO 지표가 어떻게 움직이는지 보거나, 배포·장애 훈련 중에 트래픽을 만들 때 쓴다.

| 파일 | 하는 일 |
|---|---|
| `smoke.js` | VU 1개가 30초 동안 API의 모든 경로를 돌며 상태 코드와 응답을 확인한다: 생성 201, 리다이렉트 302와 `Location`, 조회수, 없는 코드 404, 잘못된 URL 400. 초당 요청이 5건쯤이라 실제 환경에 돌려도 부담이 없다. |
| `load.js` | 목표 요청률(req/s)까지 올렸다가 유지하고 내리는 부하 테스트. 요청의 20%는 생성(POST), 80%는 리다이렉트(GET)다. 임계값을 어기면 실패(종료 코드 99)하고, 요약을 JSON 파일로 남긴다. |
| `lib.js` | 두 스크립트가 같이 쓰는 도우미 (대상 주소, 생성·리다이렉트·조회 호출) |
| `results/` | `load.js`의 요약 JSON이 쌓이는 곳. 디렉터리만 Git에 있고 결과 파일은 올라가지 않는다 |

## 준비

Docker만 있으면 된다. k6는 태그(`2.3.0`)만이 아니라 digest로 고정해서 쓴다. 태그는 다른 이미지를 가리키도록 바뀔 수 있지만 digest는 이미지 내용의 해시라서 언제 돌려도 같은 k6가 나온다. 이 digest는 amd64·arm64를 묶은 인덱스의 것이라 맥(arm64)에서도 같은 값이다. 아래 명령은 모두 이 저장소의 루트에서 실행한다.

```bash
K6_IMAGE=grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34
```

## 실행

대상은 `BASE_URL`로 정한다 (스모크와 부하 공통). 빠뜨리면 로컬 compose(`docker compose up`으로 띄운 앱. 컨테이너 안에서 본 맥의 8080 포트라서 `http://host.docker.internal:8080`)로 간다. 기본값을 dev·prod로 두지 않은 이유는, 환경 변수를 빠뜨린 실행이 실제 환경에 부하를 주지 않게 하려는 것이다.

| 대상 | `BASE_URL` |
|---|---|
| 로컬 compose | 생략 |
| dev | `http://dev.dev-ops-study.duckdns.org` |
| prod | `http://dev-ops-study.duckdns.org` |

**스모크.** 부하를 걸기 전에 주소가 맞고 대상이 살아 있는지 먼저 본다.

```bash
docker run --rm \
  -e BASE_URL=http://dev.dev-ops-study.duckdns.org \
  -v "$PWD/loadtest:/scripts:ro" \
  $K6_IMAGE run /scripts/smoke.js
```

**부하 테스트.** 처음에는 dev에서 낮게 시작한다 (최대 10 req/s, 유지 1분). dev에서 괜찮으면 같은 값으로 prod에서 한 번 더 보고, 그다음에 기본값으로 올린다.

```bash
docker run --rm \
  -e BASE_URL=http://dev.dev-ops-study.duckdns.org \
  -e PEAK_RPS=10 -e HOLD=1m \
  -v "$PWD/loadtest:/scripts:ro" -v "$PWD/loadtest/results:/results" \
  $K6_IMAGE run /scripts/load.js
```

기본값(최대 50 req/s, 올리기 2분 + 유지 5분 + 내리기 30초, 모두 7분 30초)으로 prod에 건다. 요약 파일 이름에 시각을 붙여서 이전 결과를 덮어쓰지 않게 했다.

```bash
docker run --rm \
  -e BASE_URL=http://dev-ops-study.duckdns.org \
  -e SUMMARY_PATH=/results/prod-$(date +%Y%m%d-%H%M).json \
  -v "$PWD/loadtest:/scripts:ro" -v "$PWD/loadtest/results:/results" \
  $K6_IMAGE run /scripts/load.js
```

`load.js`의 환경 변수 (`docker run -e 이름=값`):

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `BASE_URL` | `http://host.docker.internal:8080` | 대상 주소 |
| `PEAK_RPS` | `50` | 최대 요청률(req/s). 1 이상의 정수 |
| `RAMP_UP` | `2m` | 0에서 최대까지 올리는 시간 (`30s`, `2m`처럼 k6 기간 표기) |
| `HOLD` | `5m` | 최대를 유지하는 시간 |
| `RAMP_DOWN` | `30s` | 0까지 내리는 시간 |
| `POST_RATIO` | `0.2` | 요청 중 생성(POST)의 비율. 나머지는 리다이렉트(GET) |
| `MAX_VUS` | `100` | k6가 동시에 쓸 VU(동시 요청)의 상한. 서버가 느려져 요청이 쌓여도 이 수를 넘기지 않는다. 미리 띄워 두는 VU는 최대 요청률만큼(최소 20개)이다. k6 컨테이너는 VU 100개에서도 메모리를 약 50MiB 쓴다 |
| `SUMMARY_PATH` | `/results/summary.json` | 요약 JSON을 쓸 컨테이너 안의 경로. `/results`가 맥의 `loadtest/results`에 마운트되어 있어야 파일이 남는다. 마운트를 빠뜨리면 `failed to handle the end-of-test summary` 오류만 나오고 파일은 없다 |

`load.js`는 시작할 때 `setup()`에서 코드 30개를 미리 만들고, GET은 그 시드 코드와 "그 VU가 실행 중에 직접 만든 코드"를 섞어서 조회한다. VU끼리는 메모리를 공유하지 않으므로 다른 VU가 만든 코드는 모르고, VU마다 최근 500개만 기억한다. 시드 생성이 일부 실패해도 성공한 것이 하나라도 있으면 그것으로 진행하고(실패 수는 경고로 남긴다), 하나도 없을 때만 멈춘다. 조회마다 `Location`이 처음 넣은 URL과 같은지도 확인한다. 반복 하나가 요청 하나라서 `PEAK_RPS`는 곧 초당 요청 수다.

## 기본값을 이렇게 잡은 이유

- **최대 50 req/s.** 대상 노드는 vCPU 2개짜리 한 대이고 dev, prod, ArgoCD, 모니터링이 나눠 쓴다. 노드를 포화시키지 않으면서 앱 CPU는 눈에 띄게 올리는 수준으로 잡았다. 로컬(Apple 실리콘의 Docker)에서 50 req/s를 걸었더니 앱 컨테이너가 코어의 10~20%(요청당 2~4ms)를 썼다. JIT가 데워지는 처음 1분은 약 20%, 몇 분 뒤에는 약 10%였다. EC2의 vCPU는 이보다 느리다. prod의 HPA는 파드들의 평균 CPU가 요청(100m)의 70%(70m)를 넘으면 늘리는데 허용 오차 10%가 있어서 실제로는 약 77m를 넘어야 움직인다. 로컬 값으로 보면 파드 하나가 이 선을 넘으므로 파드가 늘 것으로 예상하지만, 최소 파드가 2개이면 50 req/s에서도 2개에 머물 수 있다(예상일 뿐 재지 않았다). 이 값들은 EC2에서 재지 않은 추정이라서, 첫 실행은 `PEAK_RPS=10`으로 하고 `kubectl top`으로 보면서 올린다. 이 노드에서는 더 올려도 얻는 게 적고 모니터링과 ArgoCD만 압박한다.
- **요청률로 조절(ramping-arrival-rate).** VU 수로 조절하면 서버가 느려질 때 VU가 응답을 기다리느라 요청률도 같이 떨어져서, 느려졌을 때의 지연이 실제보다 좋게 보인다. 요청률을 고정하면 느려짐이 그대로 드러나고, 못 따라가면 `dropped_iterations`로 보인다.
- **올리기 2분 + 유지 5분.** HPA가 CPU를 보고 파드를 늘리고 새 파드의 JVM이 뜨기까지(startupProbe 최대 120초) 걸리는 시간을 지켜볼 수 있고, 유지 구간이 SLO 규칙의 가장 짧은 창(5분)을 채운다.

## 결과 읽는 법

끝나면 터미널에 요약이 나온다 (로컬에서 `PEAK_RPS=50 RAMP_UP=10s HOLD=60s RAMP_DOWN=5s`로 돌린 예).

```
k6 부하 테스트 결과: http://host.docker.internal:8080
  실행 1m15s, 목표 최대 50 req/s, 요청 3410건 (setup 30건 포함, 전체 평균 45.4 req/s)
  GET  리다이렉트  p50 4.0ms  p95 13.9ms  p99 79.2ms  max 386.7ms  (2699건)
  POST 생성        p50 4.1ms  p95 12.1ms  p99 76.3ms  max 192.2ms  (681건)
  실패율 0.00%  checks 통과 100.00%  dropped_iterations 0 (0.00%)
임계값
  ✓ checks rate>0.99
  ✓ dropped_iterations rate<0.5
  ✓ http_req_duration{type:create} p(95)<500
  ✓ http_req_duration{type:redirect} p(95)<300
  ✓ http_req_failed rate<0.01
요약 JSON: /results/summary.json
```

- **GET 리다이렉트·POST 생성**: 요청 종류별 응답 시간이다. 요청을 보낸 뒤 응답을 다 받을 때까지이고 연결을 맺는 시간은 뺀다. p95는 "요청 100개 중 95개가 이 시간 안에 끝났다"는 뜻이다. 합격선은 리다이렉트 300ms(앱의 지연 SLO와 같은 경계), 생성 500ms(DB에 쓰고 커밋까지 하므로 여유를 뒀다).
- **실패율**: 상태 코드가 400 이상이거나 응답이 없는(연결 실패, 10초 타임아웃) 요청의 비율. 합격선은 1% 미만이다.
- **checks 통과**: 응답 내용 검사의 통과율. 생성은 201과 `code`, 리다이렉트는 302와 `Location`이 맞는지다.
- **dropped_iterations**: 계획한 요청률을 못 맞춰 보내지 못한 요청 수다(괄호는 계획한 요청 중 비율). 일시적인 지연 한 번에도 몇 건은 빠질 수 있어서, 임계값은 초당 평균이 최대 요청률의 1%(기본 0.5건/s) 미만이다. 이를 넘으면 계획한 부하가 걸리지 않은 실행이다. 서버가 느려져 응답을 기다리는 요청이 `MAX_VUS`까지 쌓였거나, 맥·회선이 못 따라간 것이다.
- **전체 평균 req/s**: 올리기·내리기 구간과 setup까지 합친 평균이라 목표 최대보다 낮은 게 정상이다.

종료 코드: `0` 모든 임계값 통과, `99` 임계값 위반, `108` setup 실패(대상에 닿지 않거나 코드를 못 만들었다. 부하는 걸지 않고, 요약 파일도 쓰지 않아서 직전 결과가 지워지지 않는다), `107` 환경 변수 값 오류, `105` Ctrl+C로 중단(그때까지의 요약과 파일은 나온다).

진행 표시줄의 마지막 줄이 `✗ [97%]`처럼 보일 수 있다. 마지막 구간에서 요청률이 0으로 내려가며 예정된 요청을 다 보내고 끝 시각보다 조금 일찍 끝나서 k6가 그렇게 표시하는 것이고, 실패가 아니다. 합격 여부는 임계값 줄과 종료 코드로 본다.

요약 JSON은 k6의 요약 데이터에 `run`(실행 조건과 시작·끝 시각, UTC)을 붙인 것이다. 지연은 밀리초다. `setup_data.runId`는 이 실행이 만든 URL에 들어가는 표식이다.

```bash
jq '{run, redirect_p95: .metrics["http_req_duration{type:redirect}"].values["p(95)"],
     create_p95: .metrics["http_req_duration{type:create}"].values["p(95)"],
     failed: .metrics.http_req_failed.values.rate, dropped: .metrics.dropped_iterations.values.count}' \
  loadtest/results/summary.json
```

k6의 지연은 맥에서 잰 값이라 인터넷 왕복이 들어 있다. 서버 안의 지연은 `run.startedAt`~`finishedAt` 시간대의 Grafana Shortener 대시보드로 본다. 둘의 차이가 네트워크와 Traefik이다.

## 주의

- **prod의 HPA는 CPU로 파드를 늘린다.** 이 부하에서는 파드가 늘고, 파드마다 DB 커넥션 10개와 노드 메모리를 더 쓴다. 부하를 거는 동안 다른 터미널에서 지켜본다: `kubectl -n shortener-prod get hpa -w` (CPU 사용률과 REPLICAS). 부하를 멈춰도 파드는 바로 줄지 않는다 (HPA 기본 설정이라 5분쯤 걸린다). 파드 수의 상한은 설정 저장소 `environments/prod/values.yaml`의 `autoscaling`이다. 파드 재시작이나 지연 급등이 보이면 Ctrl+C로 멈춘다.
- **SLO 알림.** 지연이나 5xx 비율이 올라가면 SLO 번 레이트 알림이 Discord로 갈 수 있다 (dev, prod 모두). 부하 때문에 울린 것이라면 의도한 결과다.
- **가정 회선이 한계다.** 부하는 맥과 집 회선에서 나가므로 서버보다 내 쪽이 먼저 한계에 닿을 수 있다 (업스트림 대역폭, 공유기의 연결 처리, Wi-Fi 품질). 요청이 작아서(요청당 보내는 쪽 약 0.1KB, 받는 쪽 약 0.2KB, k6 측정) 50 req/s에서는 문제가 안 되지만, `PEAK_RPS`를 크게 올리면 `dropped_iterations`가 늘거나 지연이 서버 탓처럼 늘어난다. 서버 쪽 지표와 비교해서 원인을 가린다. 가능하면 유선으로 돌린다.
- **데이터가 남는다.** 생성 요청은 실제 행을 만든다 (삭제 API는 없다). 기본값으로 한 번 돌리면 약 3천8백 행이다. 원본 URL이 모두 `https://example.com/k6/`로 시작해서 필요하면 지울 수 있다 (Redis 캐시는 24시간 뒤 사라진다).

  ```bash
  # prod
  kubectl -n shortener-prod exec shortener-prod-postgresql-0 -c postgresql -- \
    psql -U shortener -d shortener -c "DELETE FROM short_url WHERE original_url LIKE 'https://example.com/k6/%'"
  # dev
  kubectl -n shortener-dev exec shortener-dev-postgresql-0 -c postgresql -- \
    psql -U shortener -d shortener -c "DELETE FROM short_url WHERE original_url LIKE 'https://example.com/k6/%'"
  ```

  (파드 이름은 차트의 이름 규칙에서 유추한 것이다. 안 맞으면 `kubectl -n <네임스페이스> get pods`로 확인한다.)

- **인스턴스가 꺼져 있으면** (이 프로젝트는 클러스터 작업이 없을 때 EC2를 멈춰 둔다) `smoke.js`는 요청이 모두 실패해서 종료 코드 99로 끝나고, `load.js`는 setup에서 약 10초 뒤 멈춘다 (종료 코드 108).

## k6 이미지 digest 올리기

digest는 이 README에만 있어서 Dependabot이 갱신해 주지 않는다. 올릴 때는 새 태그의 인덱스 digest를 구해(`docker buildx imagetools inspect grafana/k6:<새 버전>`의 맨 위 `Digest:`) 위 `K6_IMAGE`의 태그와 digest를 함께 바꾸고, 아래 `inspect`로 스크립트가 새 버전에서 읽히는지 확인한다. k6는 메이저 버전에서 요약 형식이 바뀐 적이 있어서(`handleSummary`의 `data`), 올린 뒤 짧게 한 번 돌려 요약 파일을 확인한다.

## 스크립트를 고쳤을 때

실제 환경에 요청을 보내지 않고 검증한다.

```bash
# 문법과 옵션만 확인한다 (스크립트를 읽고 옵션을 풀어 출력할 뿐 요청은 없다)
docker run --rm -v "$PWD/loadtest:/scripts:ro" $K6_IMAGE inspect /scripts/load.js

# 로컬 compose 앱에 짧게 돌려 본다. 무겁다: 앱 이미지를 빌드하고(Gradle), 앱·PostgreSQL·Redis를 띄운다.
# compose에는 healthcheck가 앱에 없어서 up -d가 앱이 준비되기 전에 돌아온다(--wait도 소용없다).
# 그때 k6를 돌리면 setup에서 종료 코드 108로 멈추므로, 앱이 뜰 때까지 기다린다. compose는 8080만 열고 actuator도 그 포트에 있다.
docker compose up -d --build
until curl -sf localhost:8080/actuator/health >/dev/null; do sleep 2; done
docker run --rm -e PEAK_RPS=10 -e RAMP_UP=5s -e HOLD=10s -e RAMP_DOWN=5s \
  -v "$PWD/loadtest:/scripts:ro" -v "$PWD/loadtest/results:/results" \
  $K6_IMAGE run /scripts/load.js
docker compose down
```

이 로컬 순서는 이미지를 빌드해서 무겁다. 작성 당시에는 실행해 보지 않았다(같은 앱 이미지를 담은 별도 스택으로 검증했다).
