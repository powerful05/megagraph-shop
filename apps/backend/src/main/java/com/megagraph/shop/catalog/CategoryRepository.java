package com.megagraph.shop.catalog;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByVisibleTrueOrderByLevelNoAscNameAsc();

    boolean existsByParentIdAndVisibleTrue(Long parentId);
}
