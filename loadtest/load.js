// 부하 테스트: 목표 요청률(req/s)까지 서서히 올리고, 그 수준을 유지하고, 내린다.
// 요청의 약 20%는 단축 URL 생성(POST), 약 80%는 리다이렉트(GET)다. GET이 가는 코드는 setup()에서 미리 만든 것과 실행 중에 만든 것이다.
//
// 기본값(최대 50 req/s, 올리기 2분 + 유지 5분 + 내리기 30초)의 근거는 README의 "기본값을 이렇게 잡은 이유"에 있다. 줄이면 이렇다.
// 노드(vCPU 2개)를 dev·prod·ArgoCD·모니터링이 나눠 쓰므로 노드를 포화시키지 않아야 하고, prod HPA의 CPU 목표(요청 100m의 70%)가 낮아서 50 req/s만으로도 앱 파드가 늘어날 것이다.
// 로컬에서 잰 값(50 req/s에서 앱이 코어의 10~20%)에서 나온 추정이고 EC2에서는 재지 않았다. 그래서 처음에는 PEAK_RPS=10으로 시작해 올려 나간다.
//
// 요청률로 부하를 정하는 이유(ramping-arrival-rate): VU 수로 정하는 방식은 서버가 느려지면 VU가 응답을 기다리느라 요청률이 같이 떨어져서, 정작 느려졌을 때의 지연이 실제보다 좋게 보인다.
// 목표 요청률을 고정하면 서버가 느려져도 같은 속도로 요청이 들어와서 느려짐이 그대로 드러나고, 못 따라가면 dropped_iterations로 보인다.
import { check } from 'k6';
import exec from 'k6/execution';
import { BASE_URL, codeOf, createShortUrl, getRedirect } from './lib.js';

// ---- 설정 (환경 변수) ----
// 숫자 환경 변수가 오타(PEAK_RPS=abc)나 범위 밖이면 k6가 이상한 오류를 내며 죽기 전에 이유를 알려 주고 멈춘다.
function envNumber(name, fallback, { min, max = Infinity, integer = false }) {
  const raw = __ENV[name];
  const value = raw === undefined || raw === '' ? fallback : Number(raw);
  if (!Number.isFinite(value) || value < min || value > max || (integer && !Number.isInteger(value))) {
    const bound = max === Infinity ? `${min} 이상` : `${min} 이상 ${max} 이하`;
    throw new Error(`${name}=${raw}: ${bound}${integer ? '의 정수' : '의 숫자'}여야 한다`);
  }
  return value;
}

// 최대 요청률. 반복(iteration) 하나가 요청 하나이므로 초당 반복 수가 곧 초당 요청 수다.
const PEAK_RPS = envNumber('PEAK_RPS', 50, { min: 1, integer: true });
// 구간 길이. k6 기간 표기(30s, 2m, 1h)를 그대로 쓴다.
const RAMP_UP = __ENV.RAMP_UP || '2m';
const HOLD = __ENV.HOLD || '5m';
const RAMP_DOWN = __ENV.RAMP_DOWN || '30s';
// 요청 중 생성(POST)이 차지하는 비율. 나머지는 리다이렉트(GET)다.
const POST_RATIO = envNumber('POST_RATIO', 0.2, { min: 0, max: 1 });
// k6가 쓸 VU의 상한. 서버가 느려져 응답을 못 받고 쌓일 때 동시 요청이 이 수를 넘지 않게 막는 안전장치다.
// 이 수까지 다 쓰고도 목표 요청률을 못 채우면 그만큼이 dropped_iterations로 센다.
const MAX_VUS = envNumber('MAX_VUS', 100, { min: 1, integer: true });
// 요약 JSON을 쓸 경로. 컨테이너 안의 경로라서 이 경로의 디렉터리를 맥의 디렉터리에 마운트해 줘야 파일이 남는다(README).
const SUMMARY_PATH = __ENV.SUMMARY_PATH || '/results/summary.json';

// setup()이 미리 만들어 두는 코드 수. 실행 초기부터 리다이렉트 대상이 있게 하는 용도라서 많을 필요가 없다.
const SEED_COUNT = 30;
// VU 하나가 실행 중에 만든 코드를 기억해 두는 개수의 상한 (오래 돌려도 메모리가 계속 늘지 않게 한다)
const CREATED_LIMIT = 500;

export const options = {
  scenarios: {
    load: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      // 미리 띄워 두는 VU 수. 응답이 잠깐 1초쯤 느려져도(동시 요청 = 요청률 × 느려진 시간) 받아 낼 만큼, 곧 최대 요청률만큼 둔다.
      // 모자란 순간에 시작하지 못한 반복은 dropped_iterations로 센다. 로컬에서 20개로 돌렸더니 일시적인 지연 몇 번에 7분 30초 동안 0.1%가 이렇게 빠졌다.
      // VU 하나는 메모리를 1MB도 쓰지 않는다(VU 100개에 약 50MiB).
      preAllocatedVUs: Math.min(MAX_VUS, Math.max(20, PEAK_RPS)),
      maxVUs: MAX_VUS,
      stages: [
        { target: PEAK_RPS, duration: RAMP_UP },
        { target: PEAK_RPS, duration: HOLD },
        { target: 0, duration: RAMP_DOWN },
      ],
    },
  },
  thresholds: {
    // 요청의 99% 이상이 성공해야 한다 (SLO의 가용성 목표 99.5%보다 느슨하다: 올리는 구간과 새 파드가 뜨는 동안의 오류를 감안한다)
    http_req_failed: ['rate<0.01'],
    // 리다이렉트의 p95가 300ms 안이어야 한다. 300ms는 앱의 지연 SLO와 같은 경계다.
    'http_req_duration{type:redirect}': ['p(95)<300'],
    // 생성도 따로 본다. DB에 쓰고 커밋까지 하는 경로라 리다이렉트보다 여유를 둔다.
    'http_req_duration{type:create}': ['p(95)<500'],
    // 응답 내용(상태 코드, Location)이 맞는지
    checks: ['rate>0.99'],
    // 목표 요청률을 못 채우고 건너뛴 반복이 최대 요청률의 1%(초당 평균)를 넘으면 이 실행은 계획한 부하를 걸지 못한 것이다.
    // 0건을 요구하면 일시적인 지연 한 번에도 실패해서(로컬 실행에서 겪었다) 정상 실행까지 실패로 보인다.
    dropped_iterations: [`rate<${PEAK_RPS / 100}`],
  },
  // 요약(터미널과 JSON)에 담을 지연 통계
  summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
};

export function setup() {
  // 이 실행의 표식. 원본 URL에 넣어서, 나중에 DB에서 이 실행이 만든 행을 찾아 지울 수 있게 한다.
  const runId = Date.now().toString(36);
  const codes = [];
  for (let i = 0; i < SEED_COUNT; i++) {
    const url = `https://example.com/k6/${runId}/seed-${i}`;
    // type을 setup으로 바꿔서 실행 중의 생성(type:create) 통계에 섞이지 않게 한다
    const res = createShortUrl(url, { type: 'setup' });
    const code = codeOf(res);
    if (code === null) {
      // 시드조차 못 만드는 대상에는 부하를 걸지 않고 여기서 멈춘다
      exec.test.abort(
        `setup 실패: POST ${BASE_URL}/api/v1/urls 가 ${res.status === 0 ? `응답 없음(${res.error})` : `상태 ${res.status}`}. ` +
          'BASE_URL이 맞는지, 대상이 떠 있는지 smoke.js로 먼저 확인한다.',
      );
    }
    codes.push({ code, url });
  }
  return { runId, codes };
}

// VU마다 따로 갖는 상태: 이 VU가 실행 중에 만든 {code, url}. 이후 GET 대상이 된다.
const created = [];

function pickTarget(seeds) {
  const i = Math.floor(Math.random() * (seeds.length + created.length));
  return i < seeds.length ? seeds[i] : created[i - seeds.length];
}

export default function (data) {
  if (Math.random() < POST_RATIO) {
    const url = `https://example.com/k6/${data.runId}/${__VU}-${__ITER}`;
    const res = createShortUrl(url);
    const code = codeOf(res);
    check(res, { 'create: status 201 with code': () => code !== null });
    if (code !== null) {
      if (created.length >= CREATED_LIMIT) created.shift();
      created.push({ code, url });
    }
  } else {
    const target = pickTarget(data.codes);
    const res = getRedirect(target.code);
    check(res, {
      'redirect: status 302': (r) => r.status === 302,
      // 캐시(Redis)나 DB가 엉뚱한 URL을 돌려주지 않는지까지 본다
      'redirect: Location is the original url': (r) => r.headers['Location'] === target.url,
    });
  }
  // 반복 끝에 sleep을 넣지 않는다: 요청 속도는 executor가 정한다
}

// ---- 요약 ----
// handleSummary를 정의하면 k6의 기본 터미널 요약이 나오지 않는다. 그래서 핵심만 직접 찍고, 전체 데이터는 JSON 파일로 쓴다.
// (기본 요약을 되살리는 textSummary는 jslib.k6.io에서 받아야 해서, 버전을 고정한 오프라인 실행과 맞지 않아 쓰지 않는다)
function metricValue(data, metric, key) {
  const m = data.metrics[metric];
  return m && m.values ? m.values[key] : undefined;
}

function ms(v) {
  return v === undefined ? '-' : `${v.toFixed(1)}ms`;
}

function latencyLine(data, label, metric) {
  const count = metricValue(data, metric, 'count');
  if (!count) return `  ${label}  요청 없음`;
  const p = (key) => ms(metricValue(data, metric, key));
  return `  ${label}  p50 ${p('med')}  p95 ${p('p(95)')}  p99 ${p('p(99)')}  max ${p('max')}  (${count}건)`;
}

function duration(msTotal) {
  const s = Math.round(msTotal / 1000);
  return `${Math.floor(s / 60)}m${String(s % 60).padStart(2, '0')}s`;
}

function textReport(data) {
  const lines = [];
  lines.push('');
  lines.push(`k6 부하 테스트 결과: ${BASE_URL}`);
  const reqs = metricValue(data, 'http_reqs', 'count');
  const rate = metricValue(data, 'http_reqs', 'rate');
  // 요청 수와 평균에는 setup()의 시드 생성도 들어 있다. 평균은 올리기·내리기 구간까지 합친 값이라 목표 최대보다 낮은 게 정상이다.
  lines.push(
    `  실행 ${duration(data.state.testRunDurationMs)}, 목표 최대 ${PEAK_RPS} req/s, 요청 ${reqs}건 (setup ${SEED_COUNT}건 포함, 전체 평균 ${rate === undefined ? '-' : rate.toFixed(1)} req/s)`,
  );
  lines.push(latencyLine(data, 'GET  리다이렉트', 'http_req_duration{type:redirect}'));
  lines.push(latencyLine(data, 'POST 생성      ', 'http_req_duration{type:create}'));
  const failed = metricValue(data, 'http_req_failed', 'rate');
  const checks = metricValue(data, 'checks', 'rate');
  const dropped = metricValue(data, 'dropped_iterations', 'count') || 0;
  const planned = dropped + (metricValue(data, 'iterations', 'count') || 0);
  const droppedText = planned > 0 ? `${dropped} (${((dropped / planned) * 100).toFixed(2)}%)` : `${dropped}`;
  lines.push(
    `  실패율 ${failed === undefined ? '-' : (failed * 100).toFixed(2)}%  checks 통과 ${checks === undefined ? '-' : (checks * 100).toFixed(2)}%  dropped_iterations ${droppedText}`,
  );
  lines.push('임계값');
  for (const name of Object.keys(data.metrics).sort()) {
    const thresholds = data.metrics[name].thresholds || {};
    for (const expr of Object.keys(thresholds)) {
      lines.push(`  ${thresholds[expr].ok ? '✓' : '✗'} ${name} ${expr}`);
    }
  }
  return lines;
}

export function handleSummary(data) {
  // setup()에서 멈춘 실행처럼 반복이 하나도 없으면 요약 파일을 쓰지 않는다. 쓰면 직전의 정상 결과가 빈 결과로 덮어쓰인다.
  if (!(metricValue(data, 'iterations', 'count') > 0)) {
    return { stdout: '\nk6 부하 테스트: 실행된 반복이 없어 요약을 만들지 않았다 (위의 오류를 본다)\n' };
  }
  const finishedAt = new Date();
  const run = {
    baseUrl: BASE_URL,
    peakRps: PEAK_RPS,
    rampUp: RAMP_UP,
    hold: HOLD,
    rampDown: RAMP_DOWN,
    postRatio: POST_RATIO,
    maxVUs: MAX_VUS,
    startedAt: new Date(finishedAt.getTime() - data.state.testRunDurationMs).toISOString(),
    finishedAt: finishedAt.toISOString(),
  };
  const lines = textReport(data);
  lines.push(`요약 JSON: ${SUMMARY_PATH}`);
  return {
    stdout: `${lines.join('\n')}\n`,
    // k6가 준 data 그대로에 실행 조건(run)만 앞에 붙인다. 파일만 봐도 어떤 조건의 결과인지 알 수 있다.
    [SUMMARY_PATH]: JSON.stringify({ run, ...data }, null, 2),
  };
}
