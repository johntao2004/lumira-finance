package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lumira.finance.domain.ReportSourceEngine;
import com.lumira.finance.domain.ReportSourceEngineTest;
import com.lumira.finance.tax.TaxObligationModelTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FinanceV4DomainAndTaxTest {
    @Test
    void reportSourceEngineScenariosAndJsonRoundTrip() throws Exception {
        ReportSourceEngineTest.main(new String[0]);
        var mapper = new ObjectMapper();
        for (var standard : ReportSourceEngine.Standard.values()) {
            var scope =
                    new ReportSourceEngine.Scope(
                            "tenant",
                            "entity",
                            "book",
                            "year",
                            standard,
                            "INDIVIDUAL",
                            "CNY",
                            LocalDate.of(2026, 1, 1),
                            LocalDate.of(2026, 1, 31),
                            "ledger-v9",
                            "mapping-v2",
                            "mapping-approval-v2",
                            "snapshot-v9");
            var in =
                    new ReportSourceEngine.Input(
                            scope, "FULL_HISTORY", List.of(), List.of(), List.of(), List.of());
            assertEquals(
                    in, mapper.readValue(mapper.writeValueAsString(in), ReportSourceEngine.Input.class));
            var out = ReportSourceEngine.calculate(in);
            assertEquals(
                    out,
                    mapper.readValue(
                            mapper.writeValueAsString(out), ReportSourceEngine.Result.class));
        }
    }

    @Test
    void taxObligationModelScenarios() throws Exception {
        TaxObligationModelTest.main(new String[0]);
    }
}
