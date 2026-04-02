# Spring JPA 학습 프로젝트

Spring Boot + JPA를 활용한 쇼핑몰 도메인 실습 프로젝트입니다.  
엔티티 설계부터 API 최적화까지 JPA의 핵심 개념들을 단계별로 학습합니다.

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Framework | Spring Boot 3.3.5 |
| ORM | Spring Data JPA (Hibernate) |
| Database | H2 (In-memory) |
| View | Thymeleaf |
| Build | Gradle |
| Language | Java 17 |
| Utility | Lombok |

---

## 프로젝트 구조

```
src/main/java/jpabook/jpashop/
├── domain/              # JPA 엔티티 (도메인 모델)
│   ├── item/            # Item 상속 계층 (Book, Album, Movie)
│   ├── Address.java     # 임베디드 값 타입
│   ├── Member.java
│   ├── Order.java
│   ├── OrderItem.java
│   ├── Delivery.java
│   └── Category.java
├── repository/          # 데이터 접근 계층 (EntityManager 직접 사용)
│   └── order/query/     # 조회 전용 DTO 쿼리 리포지토리
├── service/             # 비즈니스 로직
├── api/                 # REST API 컨트롤러
├── controller/          # MVC 웹 컨트롤러 (Thymeleaf)
└── exception/           # 커스텀 예외
```

---

## 도메인 모델

```
Member 1 ──────────── N Order 1 ──── 1 Delivery
                            │
                            N
                        OrderItem N ──── 1 Item (Book/Album/Movie)

Category N ──────────── N Item (자기 참조 계층 구조)
```

---

## 학습 내용

### 1. 엔티티 매핑 기초

- `@Entity`, `@Id`, `@GeneratedValue`, `@Column`
- `@OneToMany`, `@ManyToOne`, `@OneToOne`, `@ManyToMany`
- `@Embedded` / `@Embeddable` — **값 타입** (`Address`)
- **컬렉션 필드 레벨 초기화** 권장 이유 (Hibernate 내부 프록시 보호)

```java
// Bad: 메서드에서 생성하면 Hibernate가 변경 추적을 놓칠 수 있음
// Good: 필드에서 초기화
private List<Order> orders = new ArrayList<>();
```

---

### 2. 연관관계 매핑

#### 양방향 연관관계 편의 메서드
양쪽을 동시에 세팅해야 객체 상태 일관성이 유지됩니다.

```java
// Order.java
public void setMember(Member member) {
    this.member = member;
    member.getOrders().add(this);
}
```

#### 지연 로딩 (LAZY) 기본 원칙
`@XToOne` 관계는 기본이 즉시 로딩(EAGER) → **모두 LAZY로 변경** 필요.

```java
@ManyToOne(fetch = FetchType.LAZY)
private Member member;
```

---

### 3. 상속 관계 매핑

**단일 테이블 전략** (`SINGLE_TABLE`) 사용:

```java
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "dtype")
public abstract class Item { ... }

@Entity
@DiscriminatorValue("B")
public class Book extends Item { ... }
```

| 전략 | 특징 |
|------|------|
| `SINGLE_TABLE` | 한 테이블에 모든 서브타입 저장, 조회 성능 우수 |
| `TABLE_PER_CLASS` | 서브타입마다 테이블, UNION 쿼리 |
| `JOINED` | 정규화된 테이블, 조인 필요 |

---

### 4. 영속성 전이 (Cascade)

Order 저장 한 번으로 연관 엔티티까지 자동 저장됩니다.

```java
@OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
private List<OrderItem> orderItems = new ArrayList<>();
```

> 생명주기가 완전히 종속될 때만 사용 (Order → OrderItem, Order → Delivery)

---

### 5. 값 타입 — `@Embeddable`

`Address`는 엔티티가 아닌 값 타입입니다.

```java
@Embeddable
@Getter // setter 없이 불변 객체로 설계
public class Address {
    private String city;
    private String street;
    private String zipcode;

    protected Address() {} // JPA 기본 생성자
    public Address(String city, String street, String zipcode) { ... }
}
```

> 불변 객체로 설계해야 공유 참조 버그를 방지할 수 있습니다.

---

### 6. 비즈니스 로직의 위치 (도메인 모델 패턴)

JPA를 쓸 때는 엔티티 자신이 비즈니스 로직을 갖는 것이 자연스럽습니다.

```java
// Item.java — 재고 관리 로직
public void removeStock(int quantity) {
    int restStock = this.stockQuantity - quantity;
    if (restStock < 0) throw new NotEnoughStockException("재고 부족");
    this.stockQuantity = restStock;
}

// Order.java — 주문 취소 로직
public void cancel() {
    if (delivery.getStatus() == DeliveryStatus.COMP)
        throw new IllegalStateException("이미 배송 완료된 상품은 취소 불가");
    this.setStatus(OrderStatus.CANCEL);
    for (OrderItem item : orderItems) item.cancel();
}
```

---

### 7. JPQL & 동적 쿼리

#### String 방식 (단순하지만 오류 위험)

```java
String jpql = "select o from Order o join o.member m";
if (StringUtils.hasText(condition.getMemberName()))
    jpql += " where m.name like :name";
```

#### Criteria API 방식 (타입 안전)

```java
CriteriaBuilder cb = em.getCriteriaBuilder();
CriteriaQuery<Order> cq = cb.createQuery(Order.class);
Root<Order> o = cq.from(Order.class);
List<Predicate> criteria = new ArrayList<>();
if (StringUtils.hasText(orderSearch.getMemberName())) {
    criteria.add(cb.like(m.get("name"), "%" + orderSearch.getMemberName() + "%"));
}
cq.where(cb.and(criteria.toArray(new Predicate[0])));
```

> 실무에서는 **QueryDSL** 사용을 권장합니다.

---

### 8. N+1 문제 해결 전략

#### 문제 상황
Order 10건 조회 시 → member 10번 + delivery 10번 = **총 21쿼리** 발생

```java
// N+1 문제 발생 코드
List<Order> orders = orderRepository.findAll(); // 쿼리 1번
orders.forEach(o -> o.getMember().getName());   // LAZY 로딩 N번
```

#### 해결 방법 비교

| 방법 | 쿼리 수 | 페이징 가능 | 설명 |
|------|---------|------------|------|
| fetch join (ToOne) | 1 | O | `join fetch o.member` |
| fetch join (컬렉션) | 1 | X | DISTINCT 필요, 페이징 불가 |
| batch_fetch_size | 1+1 | O | **추천**: ToOne은 fetch join, 컬렉션은 batch |
| DTO 직접 조회 (IN절) | 1+1 | O | 성능 최우선 시 사용 |

#### Fetch Join (ToOne 관계)
```java
// OrderRepository.java
"select o from Order o" +
" join fetch o.member m" +
" join fetch o.delivery d"
```

#### Batch Fetch Size (컬렉션 + 페이징)
```yaml
# application.yml
spring.jpa.properties.hibernate.default_batch_fetch_size: 100
```
컬렉션 조회 시 `WHERE id IN (?, ?, ...)` 방식으로 한 번에 로딩합니다.

---

### 9. API 설계 — 엔티티 직접 노출 vs DTO

#### Bad: 엔티티 직접 반환
```java
// 엔티티 변경 시 API 스펙이 바뀌는 문제 발생
@GetMapping("/api/v1/members")
public List<Member> membersV1() {
    return memberService.findMembers();
}
```

#### Good: DTO로 감싸기
```java
@GetMapping("/api/v2/members")
public Result membersV2() {
    List<MemberDto> collect = memberService.findMembers().stream()
            .map(m -> new MemberDto(m.getName()))
            .collect(toList());
    return new Result(collect.size(), collect); // 배열 대신 객체로 감싸야 API 유연성 확보
}

@Data
@AllArgsConstructor
static class Result<T> {
    private int count;
    private T data;
}
```

---

### 10. 쿼리 최적화 단계 (OrderQueryRepository)

쿼리 최적화는 필요한 만큼만 단계적으로 적용합니다.

```
엔티티 조회 방식 (권장 순서)
──────────────────────────────────────────────────────
1단계: 엔티티 조회 후 DTO 변환 (가장 유지보수 좋음)
2단계: 필요 시 fetch join으로 성능 최적화
3단계: 페이징이 필요하면 batch_fetch_size 적용

DTO 직접 조회 방식 (성능 극한 최적화 시)
──────────────────────────────────────────────────────
4단계: JPA에서 DTO 직접 조회 (N+1 발생)
5단계: IN 절로 컬렉션 일괄 조회 (쿼리 1+1)
6단계: 단일 쿼리로 flat 조회 후 Java에서 그룹핑 (페이징 불가)
```

| 버전 | 방식 | 쿼리 수 | 페이징 |
|-----|------|---------|-------|
| V4 | DTO 조회 N+1 | 1 + N | O |
| V5 | DTO 조회 IN절 최적화 | 1 + 1 | O |
| V6 | DTO 단일 flat 쿼리 | 1 | X |

---

## 트랜잭션 관리

```java
@Service
@Transactional(readOnly = true) // 기본: 읽기 전용 (성능 최적화)
public class MemberService {

    @Transactional // 쓰기 작업은 별도 트랜잭션
    public Long join(Member member) { ... }
}
```

**더티 체킹 (변경 감지)**  
트랜잭션 안에서 엔티티 필드를 수정하면 `merge()` 없이도 자동 UPDATE됩니다.

```java
@Transactional
public void update(Long id, String name) {
    Member member = memberRepository.find(id);
    member.setName(name); // flush 시점에 자동으로 UPDATE SQL 실행
}
```

---

## 실행 방법

```bash
cd spring-jpa
./gradlew bootRun
```

- 앱 실행: http://localhost:8080
- H2 콘솔: http://localhost:8080/h2-console
  - JDBC URL: `jdbc:h2:mem:testdb`

> 기동 시 `initDb.java`의 `@PostConstruct`로 샘플 데이터(회원 2명, 주문 2건)가 자동 삽입됩니다.

---

## 주요 API 엔드포인트

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/v1/members` | 회원 조회 (엔티티 직접 노출 — 학습용) |
| GET | `/api/v2/members` | 회원 조회 (DTO 반환 — 권장) |
| POST | `/api/v1/members` | 회원 등록 (엔티티 직접 받음 — 학습용) |
| POST | `/api/v2/members` | 회원 등록 (DTO — 권장) |
| PUT | `/api/v2/members/{id}` | 회원 수정 (더티 체킹) |
| GET | `/api/v1/simple-orders` | 주문 단순 조회 (LAZY 강제 초기화) |
| GET | `/api/v3/simple-orders` | 주문 조회 (fetch join) |
| GET | `/api/v3.1/orders` | 주문+컬렉션 (fetch join + batch, 페이징 O) |
| GET | `/api/v4/orders` | 주문 DTO 조회 (N+1) |
| GET | `/api/v5/orders` | 주문 DTO IN절 최적화 |
| GET | `/api/v6/orders` | 주문 단일 flat 쿼리 |
