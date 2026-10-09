# 개발 가이드

## 실행 환경

JDK 25와 Gradle 9.3.0 Wrapper인 `./gradlew`를 사용한다. REST Docs는 4.0.1, ktlint Gradle 플러그인은 14.2.0, ktlint 엔진은 1.8.0, detekt는 2.0.0-alpha.6으로 고정한다. detekt는 정식 릴리스 전 버전이므로 업그레이드 시 사용자 정의 규칙과 타입 분석도 다시 검증한다. 기본 설정은 H2 파일 DB, 가상 스레드, Flyway, 정상 종료를 활성화한다. 테스트는 독립된 H2 인메모리 DB를 사용한다.

애플리케이션 실행·테스트·빌드·detekt에는 **JDK 25와 저장소의 Gradle Wrapper만 필요하다.** Python은 별도 배포 도구에서만 사용한다.

```sh
./gradlew bootRun
./gradlew test
./gradlew build
```

기본 H2 데이터는 `data/`에 저장된다. 로컬 DB를 초기화하려면 애플리케이션을 종료한 후 **보존할 데이터가 없는지 확인하고** 이 디렉터리를 삭제한다. 저장소에 DB 파일이나 비밀번호를 커밋하지 않는다.

기본 프로필에서는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로 연결을 재정의할 수 있다. 표준 Spring 환경변수 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`도 사용할 수 있다. 기본 드라이버는 H2이므로 다른 DB로 전환할 때는 `DB_DRIVER`도 바꾸거나 PostgreSQL용 `prod` 프로필을 사용한다. 배포 환경은 `prod` 프로필과 표준 Spring 환경변수를 사용한다.

## 예제 API

`Todo`는 UUID 식별자, 제목, 완료 여부, 생성 시각을 가진다. 제목은 공백일 수 없으며 최대 200자다.

- `POST /api/todos`: `{"title":"첫 기능 만들기"}`를 받아 `201 Created`와 `Location`을 반환한다.
- `GET /api/todos/{id}`: 항목을 조회한다. 없으면 `404`를 반환한다.
- `GET /api/todos`: 목록을 배열로 반환한다.
- `PATCH /api/todos/{id}/complete`: 완료 처리한다. 여러 번 요청해도 완료 상태를 유지한다.

정상 응답 필드는 `id`, `title`, `completed`, `createdAt`이다. 잘못된 요청은 `400`, 없는 항목은 `404`를 ProblemDetail 형식으로 반환한다. 정확한 요청·응답 예제는 테스트로 생성한 `/docs/index.html`에서 확인한다.

인증·인가와 사용자별 데이터 분리는 이 예제에 포함하지 않는다. 실제 사용자 데이터를 취급하기 전에 서비스의 접근 제어를 구현한다.

## 기능 추가 순서

1. `domain`에 비즈니스 규칙을 작성한다. 이 계층에는 Spring, Exposed, HTTP 의존성을 넣지 않는다.
2. `application/port/input`에 유스케이스 계약, `application/port/output`에 필요한 외부 의존성 계약을 추가한다. 포트도 Spring·Exposed에 의존하지 않는다.
3. `application/service`에서 유스케이스를 구현한다. DB는 출력 포트로 호출하고 업무 단위 트랜잭션은 Spring `@Transactional`로 선언한다. Spring 의존성은 `org.springframework.transaction.annotation`의 `Transactional`과 선언 옵션 타입 `Propagation`·`Isolation` 세 타입만 허용한다.
4. `adapter/inbound/<group>`에서 진입점과 입력 검증을, `adapter/outbound/<group>`에서 외부 기능을 구현한다. HTTP 예제는 `web`, Exposed 예제는 `persistence` 그룹을 사용한다. 기능 경로와 포트 역할 경로는 [패키지 경계](code-quality.md#하위-패키지와-기능-경계)에 맞춘다.
5. `config`에서 구현체를 연결한다. 새 입력 유스케이스와 출력 어댑터의 계약을 테스트한다.

Exposed의 행, 테이블, 트랜잭션 타입을 도메인이나 포트로 반환하지 않는다. 도메인 모델로 변환한 뒤 어댑터 밖으로 전달한다.

운영 코드의 주입은 생성자 `private val`로 한다. 함수 크기·인자·중첩·이름·금지 문법과 좁은 예외는 [코드 품질 규칙](code-quality.md)을 따른다. 제한을 맞추기 위해 의미 없는 함수나 인자 묶음 객체를 만들지 않는다.

가상 스레드는 JDBC의 블로킹 작업을 처리하는 실행 방식이다. DB 연결 풀을 무한히 늘리지 않으며, 트래픽에 맞는 연결 풀 크기와 쿼리 성능은 별도로 조정해야 한다.

## 트랜잭션

`exposed-spring-boot4-starter:1.5.0`의 자동 설정이 Spring 7용 Exposed `SpringTransactionManager`를 `springTransactionManager` 빈으로 등록한다. 애플리케이션의 `Database`와 트랜잭션 매니저를 별도로 중복 구성하지 않는다. 저장소는 `transaction {}` 없이 Exposed DSL을 사용해 유스케이스의 트랜잭션에 참여한다. [Exposed Spring 통합](https://www.jetbrains.com/help/exposed/spring-boot-integration.html)

`TodoService`의 기본값은 `@Transactional(readOnly = true)`다. `create`와 `complete`는 메서드의 `@Transactional`로 쓰기를 허용하며, `complete`의 조회와 저장은 같은 트랜잭션에서 실행한다. 기본 전파 방식 `REQUIRED`는 이미 열린 트랜잭션이 있으면 참여한다.

- **관리되는 빈을 호출한다.** `config`의 `@Bean`으로 조립된 유스케이스를 주입받아 호출한다. 직접 만든 객체나 같은 객체 내부 호출은 프록시를 통과하지 않아 새 트랜잭션 경계를 만들지 않는다. [Spring 프록시 동작](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- **읽기 전용은 접근 제어가 아니다.** `readOnly`는 DB·드라이버에 전달하는 힌트다. 데이터 변경을 막는 보안 장치로 사용하지 않는다.
- **롤백 조건을 명시한다.** 기본 정책은 `RuntimeException`과 `Error`를 롤백한다. SQL 오류가 아닌 checked 예외도 롤백해야 한다면 `rollbackFor`로 계약을 명시하고 테스트한다. Kotlin이 checked 예외 선언을 강제하지 않아도 이 구분은 유지된다. [Spring 롤백 규칙](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)
- **같은 스레드에서 완료한다.** JDBC 트랜잭션 안에서 비동기 실행이나 코루틴 디스패처 전환으로 작업을 넘기지 않는다. 가상 스레드를 사용해도 Spring의 JDBC 트랜잭션은 실행 스레드에 연결된다.

여러 변경이 하나의 업무라면 유스케이스의 트랜잭션 경계 안에 두고 실제 DB의 커밋·중간 실패 시 전체 롤백을 통합 테스트한다. 테스트 자체의 자동 롤백으로 결과를 가리지 않고 새 트랜잭션에서 저장 상태를 확인한다. 유스케이스에 트랜잭션 매니저나 `TransactionTemplate`을 주입하거나 별도 데코레이터를 추가할 필요는 없다.

## 스키마 변경

`src/main/resources/db/migration`에 `V2__describe_change.sql`처럼 다음 버전의 Flyway SQL을 추가한다. 이미 배포된 버전의 파일을 수정하면 체크섬 검증에 실패하므로 새 마이그레이션으로 변경한다.

스키마는 Flyway만 관리한다. Exposed 스타터의 자동 DDL 생성을 끄는 `spring.exposed.generate-ddl=false`를 유지한다.

기본 통합 테스트는 H2에서 실행한다. PostgreSQL 전용 SQL, 인덱스, 타입, 잠금 동작을 추가하면 PostgreSQL에서도 해당 변경을 검증한다. H2 테스트 통과만으로 PostgreSQL 호환성이 보장되지는 않는다.

## REST Docs 갱신

`TodoApiDocumentationTest`는 실제 애플리케이션, Flyway, H2와 MockMvc를 사용한다. API 계약을 바꿀 때 요청·응답 검증과 REST Docs 필드 설명을 함께 갱신한다.

```sh
./gradlew :test --tests '*TodoApiDocumentationTest'
./gradlew build
java -jar build/libs/application.jar
```

`src/docs/asciidoc/index.adoc`가 스니펫을 조합한다. `build/generated-snippets`와 생성 HTML은 빌드 결과물이므로 직접 수정하지 않는다. `build` 결과 JAR 실행 후 `http://localhost:8080/docs/index.html`에서 확인한다.

새 엔드포인트는 성공 응답뿐 아니라 요구사항에 필요한 검증 오류와 없는 리소스 응답도 테스트한다. 문서에서 소개하는 필드가 실제 API 응답과 일치해야 한다.

## 코드 품질과 검증

```sh
./gradlew ktlintFormat
./gradlew ktlintCheck
./gradlew detektMain detektTest
./gradlew :quality-rules:test
./gradlew :test --tests '*TodoServiceTest'
./gradlew build
git diff
```

빌드 설정은 `org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask`에 자동 포맷 의존성을 지정한다. 컴파일·테스트·빌드 전에 수정 가능한 스타일 위반을 수정하고 남은 위반을 검사한다. 검증 실행도 작업 트리의 파일을 바꿀 수 있다.

`test`, `check`, `build`는 타입 분석을 사용하는 `detektMain`, `detektTest`를 포함하며 `./gradlew detekt`도 두 태스크를 실행한다. `:quality-rules:test`는 사용자 정의 규칙의 허용·위반 fixture를 검사하고 전체 `build`에도 포함된다. detekt 보고서는 `build/reports/detekt/`에서 확인한다. CI는 빌드 후 `git diff --exit-code`로 포맷 변경까지 검사하므로 로컬 자동 수정 결과를 커밋해야 한다.

금지한 억제 문자열은 detekt의 `ForbiddenSuppress`로 검사하며, 허용한 예외의 위치와 사유는 리뷰한다. `quality-rules`는 분석 전에 로드할 detekt 규칙 JAR를 별도로 컴파일하는 빌드 전용 모듈이다. 실행 JAR에는 포함하지 않으며 ktlint와 자체 fixture로 검증한다. [검사 범위](code-quality.md#검사-도구와-실행)를 참고한다.

도메인·유스케이스 단위 테스트는 규칙과 호출 계약을, API 통합 테스트는 HTTP·저장·문서를, `HexagonalArchitectureTest`는 계층 간 의존 경계를 검사한다. 변경 범위에 맞는 테스트를 먼저 실행하고 마지막에 전체 `build`를 실행한다.

검사 실패를 baseline, 사유 없는 `@Suppress`, 경로 제외, `ignoreFailures`로 숨기지 않는다. 예외는 개별 선언·규칙·이유를 기록하고 리뷰한다. 검사 설정이나 사용자 정의 규칙을 변경하면 정상·위반·경계값 fixture도 함께 검증한다. GitHub의 실제 병합 차단은 [ruleset 활성화 절차](code-quality.md#github에서-병합-차단-활성화)를 따른다.

## 프로젝트 이름 변경

`settings.gradle.kts`의 프로젝트 이름과 `com.example.template` 패키지를 서비스 이름으로 바꿀 수 있다. 소스·테스트의 패키지 선언, 경로, 아키텍처 검사 기준, 애플리케이션 설정의 이름을 함께 확인한다.

컨테이너 빌드가 사용하는 JAR 이름은 `application.jar`다. 이름을 바꾸려면 Gradle, Dockerfile, `.dockerignore`의 허용 경로, `.github/workflows/ci.yml`의 아티팩트 업로드 경로를 함께 수정한다. 배포 식별자는 GitHub 저장소 메타데이터에서 생성하므로 Kotlin 패키지 이름에 의존하지 않는다.
