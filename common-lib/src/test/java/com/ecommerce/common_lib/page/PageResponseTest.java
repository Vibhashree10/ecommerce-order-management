package com.ecommerce.common_lib.page;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void of_computesTotalPages() {
        PageResponse<String> p = PageResponse.of(List.of("a", "b"), 0, 2, 5);
        assertThat(p.totalPages()).isEqualTo(3);
        assertThat(PageResponse.of(List.<String>of(), 0, 10, 0).totalPages()).isZero();
        assertThat(PageResponse.of(List.<String>of(), 0, 0, 5).totalPages()).isZero();
    }

    @Test
    void map_convertsContentOnly() {
        PageResponse<Integer> p = PageResponse.of(List.of("a", "bb"), 1, 2, 4).map(String::length);
        assertThat(p.content()).containsExactly(1, 2);
        assertThat(p.page()).isEqualTo(1);
        assertThat(p.totalElements()).isEqualTo(4);
    }

    @Test
    void content_isCopied() {
        java.util.ArrayList<String> src = new java.util.ArrayList<>(List.of("a"));
        PageResponse<String> p = PageResponse.of(src, 0, 1, 1);
        src.add("b");
        assertThat(p.content()).containsExactly("a");
    }
}
