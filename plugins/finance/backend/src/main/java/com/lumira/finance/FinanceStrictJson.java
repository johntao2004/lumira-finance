package com.lumira.finance;

import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/** Closed-schema, zero-coercion JSON reader for V4 HTTP controller boundaries. */
public final class FinanceStrictJson {
    public static final JsonMapper MAPPER = buildMapper();

    private FinanceStrictJson() {}

    private static JsonMapper buildMapper() {
        var builder = JsonMapper.builder();
        builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        builder.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        builder.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        builder.enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        builder.withCoercionConfig(
                LogicalType.Integer,
                cfg -> {
                    cfg.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                });
        builder.withCoercionConfig(
                LogicalType.Float,
                cfg -> {
                    cfg.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                });
        builder.withCoercionConfig(
                LogicalType.Boolean,
                cfg -> {
                    cfg.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
                });
        builder.withCoercionConfig(
                LogicalType.Textual,
                cfg -> {
                    cfg.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                });
        return builder.build();
    }

    public static void requireKeys(
            Map<String, Object> map, Set<String> required, Set<String> allowed, String label) {
        if (map == null) throw FinanceException.invalid(label + "不能为空");
        for (String req : required) {
            if (!map.containsKey(req)) throw FinanceException.invalid(label + "缺少必填字段: " + req);
        }
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) throw FinanceException.invalid(label + "包含未知字段: " + key);
        }
    }

    public static <T> T convert(Object rawValue, Class<T> type, String label) {
        if (rawValue == null) throw FinanceException.invalid(label + "不能为空");
        try {
            String raw = MAPPER.writeValueAsString(rawValue);
            return MAPPER.readValue(raw, type);
        } catch (JacksonException | IllegalArgumentException e) {
            if (e instanceof FinanceException f) throw f;
            throw FinanceException.invalid(label + "格式、类型或字段集合无效");
        }
    }
}
