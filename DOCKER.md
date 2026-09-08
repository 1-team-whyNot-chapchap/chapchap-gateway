# Gateway Docker 실행

새 환경은 .env.example을 .env로 복사하고 수정합니다. 기존 .env는 덮어쓰지 말고 예시와 비교합니다.

- JWT_SECRET는 Auth와 동일한 Base64 키이며 디코딩 후 최소 32바이트여야 합니다. 공개 예시값을 실제 서비스에 사용하지 않습니다.
- CORS_ALLOW_ORIGIN은 실제 프론트 origin입니다. credentials 사용 시 * 대신 구체적인 origin을 지정합니다.
- AUTH_SERVICE_NAME 등 이름은 Swagger 문서 URL에도 사용됩니다. *_PREDICATE에는 실제 외부 서비스 경로를 넣습니다.
- Compose의 Auth·Customer URI는 공유 네트워크 DNS(auth-service:8081, customer-service:8084)로 연결됩니다.
- Subscription·Delivery URI는 host.docker.internal:8082/8083 예시입니다. 실제 실행 주소·포트에 맞춰 COMPOSE_*_SERVICE_URI를 수정합니다. 내부 서비스가 다른 Docker 네트워크에 있으면 공통 네트워크에 연결하거나 실제 접근 가능한 주소를 사용합니다.
- Auth·Customer를 호스트 Java로 실행한다면 COMPOSE_AUTH_SERVICE_URI/COMPOSE_CUSTOMER_SERVICE_URI도 host.docker.internal 주소로 바꿉니다.

공유 네트워크를 최초 한 번 만들고 세 앱 중 Gateway를 마지막으로 시작합니다.

```powershell
docker network create chapchap-network
docker compose config --quiet
docker compose up -d --build
```

이미 네트워크가 있으면 create는 생략합니다. 기본 접속 주소는 http://localhost:8080/docs, health는 /actuator/health입니다. 하위 서비스가 아직 실행되지 않으면 해당 API/Swagger 문서 조회는 실패합니다.

- 앱은 Java 21 비root 사용자로 실행됩니다. 컨테이너 포트 8080, 호스트 바인딩은 127.0.0.1입니다.
- application.yaml의 오류 상세 노출을 끄고 CORS preflight 경로 처리를 추가했습니다.
- 내부 API는 외부 라우트 패턴이 넓어져도 path segment internal을 가진 요청을 404로 차단합니다. /api/subscription/internal/**와 /internal/**도 포함됩니다. 서비스 간 호출은 Gateway를 거치지 않습니다.
- 기존 0바이트 gradle-wrapper.jar를 설정된 동일 버전 9.5.1로 재생성했습니다.
- 설정을 바꾼 후 Compose up -d로 컨테이너를 재생성합니다. 실제 TLS·외부 배포는 별도 구성입니다.
- .env는 이미지에 포함하지 않습니다. ENV_FILE로 env_file을 바꿀 때 --env-file에도 같은 경로를 지정합니다. config 출력에는 비밀값이 포함될 수 있으므로 --quiet를 권장합니다.

중지는 docker compose stop, 컨테이너 제거는 docker compose down입니다.
