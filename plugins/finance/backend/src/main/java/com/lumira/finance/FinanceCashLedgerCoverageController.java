package com.lumira.finance;

import com.lumira.platform.modules.ModuleEndpoint;

import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/cashflow/ledger-coverage-preview")
@ModuleEndpoint("finance")
public class FinanceCashLedgerCoverageController {
    final FinanceCashLedgerCoverage coverage;

    public FinanceCashLedgerCoverageController(FinanceCashLedgerCoverage coverage) {
        this.coverage = coverage;
    }

    @GetMapping
    public Object preview(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam MultiValueMap<String, String> query) {
        return FinanceHttp.respond(
                200,
                () -> {
                    if (!Set.of("scopeRevisionId", "limit", "cursor", "primaryStage")
                                    .containsAll(query.keySet())
                            || !query.containsKey("scopeRevisionId")
                            || query.values().stream()
                                    .anyMatch(
                                            v ->
                                                    v == null
                                                            || v.size() != 1
                                                            || v.getFirst() == null
                                                            || v.getFirst().isEmpty()))
                        throw FinanceException.invalid("预览query字段缺失、重复或未知");
                    UUID scope = FinanceCashLedgerCoverage.uuid(query.getFirst("scopeRevisionId"));
                    int limit = 20;
                    if (query.containsKey("limit")) {
                        String value = query.getFirst("limit");
                        if (!value.matches("[1-9][0-9]{0,2}"))
                            throw FinanceException.invalid("limit须为1至100整数");
                        limit = Integer.parseInt(value);
                    }
                    return coverage.preview(
                            actor.getName(),
                            book,
                            year,
                            scope,
                            limit,
                            query.getFirst("cursor"),
                            query.getFirst("primaryStage"));
                });
    }
}
