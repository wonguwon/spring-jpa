package jpabook.jpashop.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
public class Member {
    @Id @GeneratedValue
    @Column(name = "member_id")
    private Long id;

    @NotEmpty
    private String name;

    @Embedded
    private Address address;

    @JsonIgnore
    @OneToMany(mappedBy = "member")
    private List<Order> orders = new ArrayList<>();

//    하이버네이트는 엔티티 필드에서 컬렉션을 초기화하는 것이 안전합니다.
//    메서드에서 임의로 컬렉션을 생성하면 하이버네이트가 변경 추적을 제대로 하지 못할 수 있습니다.
//    따라서 필드 레벨에서 컬렉션을 생성하면 코드도 간결하고 관리가 용이합니다.
}
