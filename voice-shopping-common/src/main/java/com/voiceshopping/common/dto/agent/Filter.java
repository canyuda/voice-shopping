package com.voiceshopping.common.dto.agent;

import java.util.List;

/**
 * SQL WHERE clause fragment with parameterized values.
 * Used by SqlFilterBuilder to compose dynamic filter conditions.
 *
 * @param clause SQL fragment (e.g. "price <= ? AND attributes @> CAST(? AS jsonb)")
 * @param params parameter values corresponding to placeholders in clause
 */
public record Filter(String clause, List<Object> params) {

    /** Empty filter — no conditions, no parameters. */
    public static final Filter EMPTY = new Filter("", List.of());

    /** Whether this filter has any conditions. */
    public boolean isEmpty() {
        return clause == null || clause.isBlank();
    }
}
