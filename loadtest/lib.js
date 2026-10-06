// smoke.js와 load.js가 함께 쓰는 도우미: 대상 주소와 API 호출(생성, 리다이렉트, 조회).
import http from 'k6/http';

// 대상 주소. 기본값은 맥에서 docker compose로 띄운 앱(8080 포트)이다. 컨테이너 안의 k6에서 맥 호스트는 localhost가 아니라 host.docker.internal로 보인다.
// 기본값을 dev·prod 주소로 두지 않는 이유: 환경 변수를 빠뜨린 실행이 실제 환경에 부하를 주는 일이 없게 하려는 것이다. 실제 환경은 항상 BASE_URL로 직접 적는다.
export const BASE_URL = (__ENV.BASE_URL || 'http://host.docker.internal:8080').replace(/\/+$/, '');

// 요청 하나가 기다리는 최대 시간. 기본값은 60초라서, 서버가 멈추면 VU가 1분씩 묶여 요청이 한꺼번에 밀린다.
// 앱 쪽 타임아웃(DB 3초, Redis 0.5초)보다 넉넉하되 오래 기다리지 않는 값으로 줄인다. 시간이 지나면 실패(status 0)로 세어져 http_req_failed에 드러난다.
const REQUEST_TIMEOUT = '10s';

// 요청마다 붙이는 태그 두 가지 (호출하는 쪽이 넘기는 tags가 이 기본값을 덮어쓴다. 예: setup()의 생성은 type을 setup으로 바꿔 실행 중의 생성과 섞이지 않게 한다).
//  - name: 리다이렉트 URL은 코드마다 달라서, 이름을 하나로 묶지 않으면 URL 하나하나가 별도 시계열이 되어 k6의 메모리를 갉아먹는다.
//  - type: 요청 종류별로 지연을 따로 보려는 태그다. 임계값과 요약이 http_req_duration{type:redirect}처럼 이 태그로 나눈다.

/** POST /api/v1/urls: 단축 URL을 만든다. 응답을 그대로 돌려주고, 검사는 호출하는 쪽이 한다. */
export function createShortUrl(target, tags = {}, extraParams = {}) {
  return http.post(`${BASE_URL}/api/v1/urls`, JSON.stringify({ url: target }), {
    headers: { 'Content-Type': 'application/json' },
    timeout: REQUEST_TIMEOUT,
    tags: { name: 'POST /api/v1/urls', type: 'create', ...tags },
    ...extraParams,
  });
}

/** GET /<code>: 302 응답 자체를 받는다. redirects: 0이라 Location을 따라가지 않으므로 원본 URL(example.com 등)로는 요청이 가지 않는다. */
export function getRedirect(code, tags = {}, extraParams = {}) {
  return http.get(`${BASE_URL}/${code}`, {
    redirects: 0,
    timeout: REQUEST_TIMEOUT,
    tags: { name: 'GET /{code}', type: 'redirect', ...tags },
    ...extraParams,
  });
}

/** GET /api/v1/urls/<code>: 코드의 정보(조회수 포함)를 읽는다. */
export function getStats(code, tags = {}, extraParams = {}) {
  return http.get(`${BASE_URL}/api/v1/urls/${code}`, {
    timeout: REQUEST_TIMEOUT,
    tags: { name: 'GET /api/v1/urls/{code}', type: 'stats', ...tags },
    ...extraParams,
  });
}

/** 응답 본문(JSON)에서 필드 하나를 꺼낸다. 본문이 JSON이 아니면(프록시의 502 HTML 등) 예외 대신 undefined를 돌려줘서 check가 실패로만 세게 한다. */
export function jsonField(res, field) {
  try {
    return res.json(field);
  } catch (e) {
    return undefined;
  }
}

/** 생성 응답(201)에서 단축 코드를 꺼낸다. 201이 아니거나 code가 없으면 null. */
export function codeOf(res) {
  if (res.status !== 201) return null;
  const code = jsonField(res, 'code');
  return typeof code === 'string' && code !== '' ? code : null;
}
