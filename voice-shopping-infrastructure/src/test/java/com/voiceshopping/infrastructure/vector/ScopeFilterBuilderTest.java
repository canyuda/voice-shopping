package com.voiceshopping.infrastructure.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.common.dto.agent.Filter;
import com.voiceshopping.common.dto.session.SessionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeFilterBuilderTest {

    private ScopeFilterBuilder scopeBuilder;
    private SqlFilterBuilder sqlBuilder;

    @BeforeEach
    void setUp() {
        scopeBuilder = new ScopeFilterBuilder();
        sqlBuilder = new SqlFilterBuilder(new ObjectMapper());
    }

    @Test
    void build_nullScope_returnsEmpty() {
        assertThat(scopeBuilder.build(null)).isEqualTo(Filter.EMPTY);
    }

    @Test
    void build_platformWideScope_returnsEmpty() {
        SessionScope scope = new SessionScope(7L, List.of(), null);
        assertThat(scopeBuilder.build(scope)).isEqualTo(Filter.EMPTY);
    }

    @Test
    void build_nullAllowedMerchantIds_returnsEmpty() {
        SessionScope scope = new SessionScope(7L, null, null);
        assertThat(scopeBuilder.build(scope)).isEqualTo(Filter.EMPTY);
    }

    @Test
    void build_singleMerchantScope_emitsInClauseWithOnePlaceholder() {
        SessionScope scope = new SessionScope(7L, List.of(5L), null);
        Filter filter = scopeBuilder.build(scope);

        assertThat(filter.clause()).isEqualTo("merchant_id IN (?)");
        assertThat(filter.params()).containsExactly(5L);
    }

    @Test
    void build_multiMerchantScope_emitsInClauseWithMultiplePlaceholders() {
        SessionScope scope = new SessionScope(7L, List.of(5L, 9L, 12L), null);
        Filter filter = scopeBuilder.build(scope);

        assertThat(filter.clause()).isEqualTo("merchant_id IN (?, ?, ?)");
        assertThat(filter.params()).containsExactly(5L, 9L, 12L);
    }

    @Test
    void build_resultMergesCorrectlyWithGenericFilter() {
        Filter generic = new Filter("price <= ?", List.of(500));
        Filter scope = scopeBuilder.build(new SessionScope(7L, List.of(5L), null));

        Filter merged = sqlBuilder.merge(generic, scope);

        assertThat(merged.clause()).isEqualTo("price <= ? AND merchant_id IN (?)");
        assertThat(merged.params()).containsExactly(500, 5L);
    }
}
