{{- /*
이 파일은 쿠버네티스 리소스를 만들지 않고, 다른 템플릿이 include로 가져다 쓰는 이름·레이블·값 계산 함수만 모아 둔다
(이름이 _로 시작하는 파일은 Helm이 매니페스트로 렌더링하지 않는다).
이 차트의 템플릿 파일 주석은 YAML의 # 주석이 아니라 Go 템플릿 주석(중괄호 두 개 + 슬래시·별표)으로 쓴다.
# 주석은 렌더링 결과(helm template 출력, 클러스터에 저장되는 릴리스 기록)에 그대로 남지만, 템플릿 주석은 남지 않는다.
*/ -}}

{{- /* 차트 이름. 레이블 값은 63자를 넘을 수 없다. */}}
{{- define "shortener.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- /*
모든 리소스 이름의 접두사. nameOverride·fullnameOverride가 없으면 릴리스 이름이 된다
(릴리스 이름에 차트 이름이 이미 들어 있으면 그대로, 아니면 <릴리스>-<차트>).
41자로 자르는 이유: 여기에 접미사를 붙인 이름들이 쿠버네티스의 길이 한계 안에 들어오게 하려는 것이다.
  - StatefulSet 이름(<접두사>-postgresql, 11자)은 52자 이하여야 한다. 컨트롤러가 파드에 붙이는 레이블 controller-revision-hash의 값이
    <StatefulSet 이름>-<해시 최대 10자>인데, 레이블 값은 63자를 넘을 수 없기 때문이다.
  - headless Service 이름(<접두사>-postgresql-headless, 20자)은 DNS 레이블 한계인 63자 이하여야 한다.
*/}}
{{- define "shortener.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 41 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 41 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 41 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- /* helm.sh/chart 레이블 값: 차트 이름과 버전. 레이블 값에는 +를 쓸 수 없어서 _로 바꾼다. */}}
{{- define "shortener.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- /*
셀렉터 레이블: Deployment·StatefulSet의 selector와 Service의 selector가 파드를 고르는 데 쓴다.
한 릴리스 안에 앱·PostgreSQL·Redis 파드가 함께 있으므로 component 레이블이 있어야 서로를 구분한다.
(없으면 앱 Service가 PostgreSQL·Redis 파드까지 골라서, 요청이 엉뚱한 파드로 간다.)
Deployment와 StatefulSet의 selector는 만든 뒤에 바꿀 수 없어서, 값이 바뀔 수 있는 레이블(버전, 차트 버전)은 여기에 넣지 않는다.
인자: dict "ctx" <최상위 컨텍스트 .> "component" <app|postgresql|redis|test>
*/}}
{{- define "shortener.selectorLabels" -}}
app.kubernetes.io/name: {{ include "shortener.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- /*
리소스에 붙이는 표준 레이블(app.kubernetes.io/*): 셀렉터 레이블에 차트 버전, 관리 도구, 소속 앱을 더한 것이다.
version은 넘겼을 때만 붙인다(앱에는 배포한 이미지 태그를 넘겨서, 지금 떠 있는 게 어느 커밋인지 kubectl get -L app.kubernetes.io/version으로 볼 수 있다).
인자: dict "ctx" <.> "component" <...> ["version" <값>]
*/}}
{{- define "shortener.labels" -}}
helm.sh/chart: {{ include "shortener.chart" .ctx }}
{{ include "shortener.selectorLabels" . }}
app.kubernetes.io/part-of: shortener
app.kubernetes.io/managed-by: {{ .ctx.Release.Service }}
{{- with .version }}
app.kubernetes.io/version: {{ . | quote }}
{{- end }}
{{- end }}

{{- /*
앱 이미지 참조(repository:tag). 태그가 비어 있으면 여기서 템플릿이 실패한다.
latest처럼 움직이는 태그나 빈 태그로 배포하면 "지금 뭐가 떠 있나"에 답할 수 없어서, 커밋 SHA를 반드시 받는다.
toString은 숫자로만 된 태그를 Helm이 정수로 읽는 경우(--set image.tag=1234567)에 %s가 깨지지 않게 하려는 것이다.
*/}}
{{- define "shortener.appImage" -}}
{{- $tag := required "image.tag가 비어 있다. 배포할 이미지의 커밋 SHA(40자)를 넣어야 한다: --set image.tag=<커밋 SHA 40자>" .Values.image.tag -}}
{{- printf "%s:%s" .Values.image.repository (toString $tag) -}}
{{- end }}

{{- /* DB 비밀번호가 든 Secret의 이름: 기존 Secret을 쓰면 그 이름, 아니면 차트가 만드는 Secret의 이름 */}}
{{- define "shortener.dbSecretName" -}}
{{- .Values.database.existingSecret | default (printf "%s-database" (include "shortener.fullname" .)) -}}
{{- end }}

{{- /* 앱이 접속할 DB 주소: 직접 지정한 값, 없으면 이 릴리스의 PostgreSQL Service. 내부 DB를 껐는데 주소도 없으면 실패한다. */}}
{{- define "shortener.dbHost" -}}
{{- if .Values.database.host -}}
{{- .Values.database.host -}}
{{- else if .Values.postgresql.enabled -}}
{{- printf "%s-postgresql" (include "shortener.fullname" .) -}}
{{- else -}}
{{- fail "postgresql.enabled=false이면 접속할 외부 DB의 주소(database.host)를 넣어야 한다" -}}
{{- end -}}
{{- end }}

{{- /* 앱이 접속할 Redis 주소: 직접 지정한 값, 없으면 이 릴리스의 Redis Service. 내부 Redis를 껐는데 주소도 없으면 실패한다. */}}
{{- define "shortener.redisHost" -}}
{{- if .Values.redis.host -}}
{{- .Values.redis.host -}}
{{- else if .Values.redis.enabled -}}
{{- printf "%s-redis" (include "shortener.fullname" .) -}}
{{- else -}}
{{- fail "redis.enabled=false이면 접속할 외부 Redis의 주소(redis.host)를 넣어야 한다" -}}
{{- end -}}
{{- end }}

{{- /*
앱의 비밀이 아닌 환경 변수(ConfigMap의 data). 비밀번호(DB_PASSWORD)는 Secret에서 따로 주입한다.
ConfigMap의 값은 문자열이어야 해서 숫자도 quote로 감싼다.
ConfigMap 템플릿과 Deployment의 체크섬 어노테이션이 이 한 곳을 함께 쓴다: 체크섬이 ConfigMap 전체가 아니라 이 데이터만 해시하므로
레이블(차트 버전 등)이 바뀌는 것만으로는 파드가 재시작되지 않는다.
*/}}
{{- define "shortener.configData" -}}
DB_HOST: {{ include "shortener.dbHost" . | quote }}
DB_PORT: {{ .Values.database.port | quote }}
DB_NAME: {{ .Values.database.name | quote }}
DB_USERNAME: {{ .Values.database.username | quote }}
REDIS_HOST: {{ include "shortener.redisHost" . | quote }}
REDIS_PORT: {{ .Values.redis.port | quote }}
SHORTENER_BASE_URL: {{ .Values.baseUrl | quote }}
SHORTENER_CACHE_TTL: {{ .Values.cacheTtl | quote }}
{{- end }}
