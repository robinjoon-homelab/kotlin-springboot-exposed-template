# Kotlin · Spring Boot · Exposed 템플릿

JDK 25, Kotlin 2.3.21, Spring Boot 4.1.1, Exposed 1.5.0을 사용하는 헥사고날 아키텍처 템플릿이다. 테스트로 REST Docs를 만들고 빌드 전에 ktlint로 스타일을 자동 수정하며 detekt·ArchUnit으로 코드 품질과 의존 경계를 검사한다.

## 로컬 실행

JDK 25를 설치하고 `JAVA_HOME`을 설정한다. Gradle은 저장소의 Wrapper를 사용한다.

```sh
java -version
./gradlew bootRun
```

기본 주소는 `http://localhost:8080`이다. H2 파일 DB를 `./data/application`에 자동 생성하고 Flyway로 스키마를 적용하므로 별도 DB나 Docker 없이 실행할 수 있다.

```sh
curl -i http://localhost:8080/api/todos \
  -H 'Content-Type: application/json' \
  -d '{"title":"첫 기능 만들기"}'

curl http://localhost:8080/api/todos
```

Todo는 단일 어그리게이트 CRUD BC다. **주문 BC에는 판매품목 Offer와 주문 Order라는 두 어그리게이트**가 있고, 재고 BC는 별도의 실물 보유량 모델을 갖는다. BC와 어그리게이트를 고르는 기준은 [경계 선정 절차](docs/ddd-architecture.md#새-기능을-분류하는-절차)에 있다.

```sh
curl -i http://localhost:8080/api/offers \
  -H 'Content-Type: application/json' \
  -d '{"sku":"BOOK-001"}'

curl -i http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"lines":[{"sku":"BOOK-001","quantity":3}]}'

curl -i http://localhost:8080/api/inventory/items \
  -H 'Content-Type: application/json' \
  -d '{"sku":"BOOK-001","onHand":5}'
```

주문은 SKU가 서로 다른 **1~10개 품목, 품목별 수량 1~100**을 받는다. 먼저 Offer를 등록해야 하며, Offer 조회 시점에 미등록·판매 중단 품목이 있으면 `409`를 반환한다. 생성 응답은 `201`, 상태는 `PLACED`다. `GET /api/orders/{id}`로 조회하고 `PATCH /api/orders/{id}/cancel`로 취소하며 반복 취소는 같은 상태를 반환한다.

Offer의 UUID로 `PATCH /api/offers/{id}/discontinue`를 호출하면 판매를 중단하지만 기존 주문은 바꾸지 않는다. 조회 직후 판매 중단이 커밋되는 경합에서는 이미 읽은 상태로 주문을 접수할 수 있다. **주문은 재고를 확인하거나 예약하지 않는다.** 재고의 등록·조회·입고는 독립적으로 동작한다. [업무 전제와 판정 시점](docs/ddd-architecture.md#불변식과-주문-시점의-정책)을 참고한다.

**예제 API에는 인증·인가가 없다. 공개 배포에서는 샘플 데이터만 사용하고 실제 서비스에는 접근 제어를 추가한다.**

## 테스트·코드 품질·API 문서

**JDK 25와 저장소의 Gradle Wrapper만 필요하다.** 빌드·테스트·코드 품질 검사는 Python 없이 실행한다.

```sh
./gradlew test
./gradlew build
java -jar build/libs/application.jar
```

- `test`와 `build` 전에 ktlint 자동 수정·검사와 타입 분석을 사용하는 `detektMain`, `detektTest`를 실행한다. 자동 수정된 소스는 `git diff`로 확인한다. 남은 위반은 빌드를 실패시키며 CI는 자동 수정이 발생해도 실패한다.
- 운영 함수 30줄, 일반 함수 인자 4개·생성자 6개, 제어 흐름 중첩 2단계·스코프 함수 1단계, 인지 복잡도 10을 기본으로 검사한다. 테스트·승인된 DSL 함수 80줄 등 정확한 예외와 검사 방법은 [코드 품질 규칙](docs/code-quality.md)을 따른다.
- API 통합 테스트는 실제 H2 DB와 Flyway를 사용하며 REST Docs 스니펫을 생성한다. 도메인·유스케이스·아키텍처 경계도 검사한다.
- `build`는 테스트 결과로 HTML 문서를 만들고 실행 JAR에 포함한다. JAR 실행 후 [API 문서](http://localhost:8080/docs/index.html)를 연다.
- 기본 실행과 테스트에는 Docker가 필요하지 않다. PostgreSQL 운영 설정은 [배포 가이드](docs/deployment.md)를 참고한다.

API 문서는 테스트에서 생성하는 요청·응답 필드와 예제를 사용한다. 문서 원문은 `src/docs/asciidoc/index.adoc`, 생성 스니펫은 `build/generated-snippets`, HTML은 `build/docs/asciidoc/index.html`에 있다. `bootRun`은 API 개발용이며 생성 문서 확인에는 위의 JAR 실행 명령을 사용한다.

## 프로젝트 구조

```text
src/main/kotlin/com/example/template/
├── domain/AggregateRoot.kt     # 공용 루트 표식
├── todo/                      # 할 일 BC: domain/todo + application / adapter
├── ordering/                  # 주문 BC: domain/order, domain/offer + application / adapter
├── inventory/                 # 재고 BC: domain/stock + application / adapter
└── config/                    # Spring 빈 조립
src/main/resources/db/migration/ # Flyway 마이그레이션
src/test/                      # 단위·통합·아키텍처 테스트
src/docs/asciidoc/              # REST Docs 원문
quality-rules/                 # 빌드 전용 detekt 확장 규칙
.github/workflows/             # PR 검사와 배포
```

의존 방향은 `adapter → application → domain`이다. 도메인과 포트는 Spring·Exposed·HTTP에 의존하지 않는다. 유스케이스 구현은 Spring의 `@Transactional`로 업무 단위 트랜잭션을 선언하고, Exposed Spring Boot 4 스타터가 이를 관리한다. 저장소 어댑터의 쿼리는 이 트랜잭션에 참여한다.

공통 검사는 계층 우선 구조와 `billing.orders.application...` 같은 기능 우선 구조를 지원한다. 포트의 역할 경로와 구현 경로를 맞추고, 서로 다른 어댑터 그룹은 격리한다. 이 템플릿에는 다른 기능의 도메인 참조와 어그리게이트·저장소 소유권을 제한하는 DDD 검사를 함께 적용한다. [하위 패키지 규약](docs/code-quality.md#하위-패키지와-기능-경계)을 따른다.

`Offer`와 `Order`는 같은 BC에서 독립적인 생명주기를 가진다. `OrderLine`은 Order가 함께 규칙을 지키고 저장하는 값 객체다. `orders`와 `order_lines` 두 테이블이 어그리게이트 하나를 저장하며, 별도 재고 BC의 `StockItem`은 실물 보유량을 관리한다. 각 BC는 자기 모델·저장소·테이블을 소유하며, BC 간 통신 방식은 정하지 않았다. [판단 기준과 새 기능 분류 사례](docs/ddd-architecture.md)를 따른다.

JDBC 기반 MVC에 가상 스레드를 활성화하고 정상 종료, 상태 확인, 운영용 PostgreSQL 프로필을 제공한다. 트랜잭션 선언과 테스트 방법은 [개발 가이드](docs/development.md#트랜잭션)를 참고한다.

`quality-rules`는 중첩 깊이, 변경 가능한 컬렉션 노출, 함수 80줄 상한을 검사하는 detekt 플러그인이다. detekt가 분석 전에 규칙 JAR를 읽을 수 있도록 별도 Gradle 모듈로 컴파일한다. 애플리케이션 계층을 나눈 모듈이 아니며 실행 JAR에는 포함하지 않는다. [검사 범위](docs/code-quality.md#검사-도구와-실행)를 참고한다.

## GitHub 템플릿으로 사용

1. [Use this template](https://github.com/robinjoon-homelab/kotlin-springboot-exposed-template/generate)을 열고 새 저장소를 만든다. 홈랩 조직에서 사용할 때는 **Owner를 `robinjoon-homelab`**으로 선택한다.
2. 생성한 저장소를 로컬에 복제한다. 원본은 이미 GitHub 템플릿이므로 별도로 템플릿 설정을 켤 필요가 없다.
3. 필요하면 패키지, 프로젝트 이름, Todo 예제를 서비스에 맞게 변경한다. 변경 후 `./gradlew build`를 실행한다.
4. `.github/CODEOWNERS`를 실제 담당자로 바꾸고, 병합 보호를 적용하려면 최초 푸시·CI 통과 후 `.github/rulesets/main-master.json`을 GitHub에 가져온다. 파일만으로 병합 차단이 적용되지 않으며, 이 정책은 승인 1개를 요구하므로 1인 저장소도 별도 승인자가 필요하다. [설정 절차](docs/code-quality.md#github에서-병합-차단-활성화)를 따른다.

이 공개 템플릿의 원본 저장소는 CI 검증만 실행하고 배포하지 않는다. 워크플로가 GitHub의 `is_template` 설정을 확인하므로, **생성한 서비스 저장소는 Template repository 설정을 켜지 않는다.**

PR 검사는 **PR 소스 저장소의 head 커밋**을 체크아웃해 실행한다. 서비스 저장소의 `main`·`master` 푸시 또는 해당 브랜치의 수동 실행은 검증 후 홈랩에 배포한다. **GitHub Variables로 앱 이름·레지스트리 주소·이미지 경로를 지정하고, 플랫폼 운영자가 SMS 허용 목록을 먼저 등록한다.** 지정한 워크로드가 없으면 최초 생성하며, 이미 있으면 기존 DB·도메인·환경변수를 보존하고 이미지 태그만 바꾼다. 설정과 사전조건은 [배포 가이드](docs/deployment.md)에 있다.

## 개발과 AI 에이전트

- [개발 가이드](docs/development.md): 기능 추가, API 문서 갱신, DB 마이그레이션, 검증 명령.
- [어그리게이트와 BC 경계](docs/ddd-architecture.md): 예제 경계, 트랜잭션 책임, 자동 검사의 범위와 한계.
- [코드 품질 규칙](docs/code-quality.md): 함수·이름·문법 제한, 예외 절차, 정적 분석과 GitHub 병합 차단.
- [배포 가이드](docs/deployment.md): PR 정책, SMS, 컨테이너, 하네스 연동.
- [AGENTS.md](AGENTS.md): AI 에이전트 공통 지침. Claude와 Copilot용 진입 문서도 포함한다.
