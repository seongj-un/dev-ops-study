// 스모크 테스트: VU 하나가 30초 동안 API의 모든 경로를 한 바퀴씩 돌면서 상태 코드와 응답 내용을 확인한다.
// 부하를 거는 게 아니라 "이 환경이 지금 살아 있고 응답이 맞는가"를 보는 용도다. 반복 사이에 1초씩 쉬므로 초당 요청은 5건쯤이다.
// 부하 테스트(load.js)를 돌리기 전에 먼저 돌려서, 대상 주소가 틀렸거나 배포가 깨졌을 때 부하를 걸기 전에 알아챈다.
import { check, sleep } from 'k6';
import http from 'k6/http';
import { codeOf, createShortUrl, getRedirect, getStats, jsonField } from './lib.js';

export const options = {
  vus: 1,
  duration: '30s',
  thresholds: {
    // 스모크는 하나라도 틀리면 실패다. 일부러 부르는 404·400은 아래에서 "기대한 상태"로 표시해서 http_req_failed에 세지 않는다.
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    // 지연은 느슨하게만 본다(방금 뜬 JVM의 첫 요청, 가정 회선의 변동). 지연의 합격선은 load.js의 임계값이다.
    http_req_duration: ['p(95)<1000'],
  },
};

export default function () {
  // 반복마다 다른 원본 URL을 쓴다. example.com은 예약된 도메인이고, 앱은 이 주소로 요청을 보내지 않고 저장만 한다.
  const target = `https://example.com/k6/smoke/${__VU}-${__ITER}`;

  // 1) 생성: 201과 함께 단축 코드가 온다
  const created = createShortUrl(target);
  const code = codeOf(created);
  check(created, {
    'create: status 201': (r) => r.status === 201,
    'create: body has code': () => code !== null,
  });
  if (code === null) {
    // 코드가 없으면 뒤 단계를 이어 갈 수 없다. 이미 실패로 세어졌으니 쉬었다가 다음 반복에서 다시 시도한다.
    sleep(1);
    return;
  }

  // 2) 리다이렉트: 302와 함께 Location이 방금 넣은 원본 URL이어야 한다
  const redirect = getRedirect(code);
  check(redirect, {
    'redirect: status 302': (r) => r.status === 302,
    'redirect: Location is the original url': (r) => r.headers['Location'] === target,
  });

  // 3) 조회: 방금 한 번 따라갔으니 조회수는 1이 된다. 앱은 조회수를 리다이렉트 응답과 따로(ClickRecorder의 스레드에서) 쓰므로
  //    응답 직후에는 아직 0일 수 있다. 0이면 0.1초 간격으로 최대 10번 더 묻는다 (보통은 첫 조회에서 이미 1이다).
  let stats = getStats(code);
  for (let retry = 0; retry < 10 && stats.status === 200 && jsonField(stats, 'clickCount') === 0; retry++) {
    sleep(0.1);
    stats = getStats(code);
  }
  check(stats, {
    'stats: status 200': (r) => r.status === 200,
    'stats: clickCount is 1': (r) => jsonField(r, 'clickCount') === 1,
  });

  // 4) 없는 코드: 404. 코드는 영문·숫자(Base62)로만 만든다. 다른 문자가 섞이면 리다이렉트 매핑에 걸리지 않아서, 앱의 "없는 코드" 응답이 아니라 라우팅 실패의 404를 보게 된다.
  const notFound = getRedirect('k6NotFound0', { type: 'notfound' }, { responseCallback: http.expectedStatuses(404) });
  check(notFound, { 'unknown code: status 404': (r) => r.status === 404 });

  // 5) 잘못된 URL: 400
  const invalid = createShortUrl('not-a-url', { type: 'invalid' }, { responseCallback: http.expectedStatuses(400) });
  check(invalid, { 'invalid url: status 400': (r) => r.status === 400 });

  sleep(1);
}
