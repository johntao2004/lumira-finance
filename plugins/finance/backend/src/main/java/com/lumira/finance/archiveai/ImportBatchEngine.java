package com.lumira.finance.archiveai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Deterministic Import Template, Dry-Run Row Validator, and Batch Reconciliation Engine (INT01).
 */
public final class ImportBatchEngine {

    public record FieldMapping(
            String sourceColumn,
            String targetField,
            String dataType, // STRING, DECIMAL, DATE, UUID, INTEGER
            boolean required
    ) {}

    public record ImportRowError(
            int lineNo,
            String column,
            String errorCode,
            String message,
            String sampleValue
    ) {}

    public record DryRunResult(
            int totalRows,
            int validRows,
            int errorRows,
            BigDecimal controlTotalDebit,
            BigDecimal controlTotalCredit,
            List<ImportRowError> errors,
            boolean passed
    ) {}

    public record ReconciliationResult(
            boolean matched,
            BigDecimal expectedDebit,
            BigDecimal actualDebit,
            BigDecimal expectedCredit,
            BigDecimal actualCredit,
            int expectedRows,
            int actualRows,
            String status // RECONCILED, MISMATCH_DETECTED
    ) {}

    public static DryRunResult dryRunCsv(
            List<FieldMapping> mappings,
            String delimiter,
            String csvContent
    ) {
        Objects.requireNonNull(mappings, "mappings must not be null");
        Objects.requireNonNull(delimiter, "delimiter must not be null");
        Objects.requireNonNull(csvContent, "csvContent must not be null");

        // Strip UTF-8 BOM if present
        if (csvContent.startsWith("\uFEFF")) {
            csvContent = csvContent.substring(1);
        }

        String[] lines = csvContent.split("\\r?\\n");
        if (lines.length == 0 || lines[0].trim().isEmpty()) {
            return new DryRunResult(0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    List.of(new ImportRowError(1, "FILE", "EMPTY_FILE", "CSV content is empty", "")), false);
        }

        // Header parsing via RFC 4180 tokenizer
        List<String> headers = parseCsvLine(lines[0], delimiter);
        Map<String, Integer> headerIndex = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            headerIndex.put(headers.get(i).trim(), i);
        }

        List<ImportRowError> errors = new ArrayList<>();
        // Verify all required mappings exist in header
        for (FieldMapping fm : mappings) {
            if (fm.required() && !headerIndex.containsKey(fm.sourceColumn())) {
                errors.add(new ImportRowError(1, fm.sourceColumn(), "MISSING_HEADER",
                        "Required column not found in CSV header: " + fm.sourceColumn(), ""));
            }
        }
        if (!errors.isEmpty()) {
            return new DryRunResult(lines.length - 1, 0, lines.length - 1, BigDecimal.ZERO, BigDecimal.ZERO, errors, false);
        }

        int totalDataRows = 0;
        int validRows = 0;
        BigDecimal sumDebit = BigDecimal.ZERO;
        BigDecimal sumCredit = BigDecimal.ZERO;

        for (int rowIdx = 1; rowIdx < lines.length; rowIdx++) {
            String line = lines[rowIdx].trim();
            if (line.isEmpty()) continue;
            totalDataRows++;

            List<String> cells = parseCsvLine(line, delimiter);
            boolean rowValid = true;
            BigDecimal rowDebit = BigDecimal.ZERO;
            BigDecimal rowCredit = BigDecimal.ZERO;

            for (FieldMapping fm : mappings) {
                Integer colIdx = headerIndex.get(fm.sourceColumn());
                String cellVal = (colIdx != null && colIdx < cells.size()) ? stripSurroundingQuotes(cells.get(colIdx).trim()) : "";

                if (fm.required() && cellVal.isEmpty()) {
                    errors.add(new ImportRowError(rowIdx + 1, fm.sourceColumn(), "REQUIRED_FIELD_EMPTY",
                            "Required field is empty", cellVal));
                    rowValid = false;
                    continue;
                }

                if (!cellVal.isEmpty()) {
                    switch (fm.dataType().toUpperCase(Locale.ROOT)) {
                        case "DECIMAL" -> {
                            try {
                                BigDecimal bd = new BigDecimal(cellVal);
                                if (bd.scale() > 8) {
                                    errors.add(new ImportRowError(rowIdx + 1, fm.sourceColumn(), "PRECISION_EXCEEDED",
                                            "Decimal scale exceeds maximum 8 places: " + cellVal, cellVal));
                                    rowValid = false;
                                }
                                if ("debit".equalsIgnoreCase(fm.targetField())) {
                                    rowDebit = rowDebit.add(bd);
                                } else if ("credit".equalsIgnoreCase(fm.targetField())) {
                                    rowCredit = rowCredit.add(bd);
                                }
                            } catch (NumberFormatException e) {
                                errors.add(new ImportRowError(rowIdx + 1, fm.sourceColumn(), "INVALID_DECIMAL",
                                        "Value cannot be parsed as decimal: " + cellVal, cellVal));
                                rowValid = false;
                            }
                        }
                        case "DATE" -> {
                            if (!cellVal.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                                errors.add(new ImportRowError(rowIdx + 1, fm.sourceColumn(), "INVALID_DATE_FORMAT",
                                        "Date must be in ISO format YYYY-MM-DD: " + cellVal, cellVal));
                                rowValid = false;
                            }
                        }
                        case "UUID" -> {
                            try {
                                UUID.fromString(cellVal);
                            } catch (Exception e) {
                                errors.add(new ImportRowError(rowIdx + 1, fm.sourceColumn(), "INVALID_UUID",
                                        "Value is not a valid UUID: " + cellVal, cellVal));
                                rowValid = false;
                            }
                        }
                        case "INTEGER" -> {
                            try {
                                Long.parseLong(cellVal);
                            } catch (Exception e) {
                                errors.add(new ImportRowError(rowIdx + 1, fm.sourceColumn(), "INVALID_INTEGER",
                                        "Value is not a valid integer: " + cellVal, cellVal));
                                rowValid = false;
                            }
                        }
                    }
                }
            }

            if (rowValid) {
                validRows++;
                sumDebit = sumDebit.add(rowDebit);
                sumCredit = sumCredit.add(rowCredit);
            }
        }

        sumDebit = sumDebit.setScale(8, RoundingMode.HALF_UP);
        sumCredit = sumCredit.setScale(8, RoundingMode.HALF_UP);

        boolean passed = errors.isEmpty();
        return new DryRunResult(
                totalDataRows,
                validRows,
                errors.size(),
                sumDebit,
                sumCredit,
                Collections.unmodifiableList(errors),
                passed
        );
    }

    public static List<String> parseCsvLine(String line, String delimiter) {
        List<String> cells = new ArrayList<>();
        if (line == null) return cells;
        if (line.startsWith("\uFEFF")) {
            line = line.substring(1);
        }
        char delim = (delimiter != null && !delimiter.isEmpty()) ? delimiter.charAt(0) : ',';
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++; // skip escaped quote
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delim && !inQuotes) {
                cells.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        cells.add(sb.toString().trim());
        return cells;
    }

    public static String stripSurroundingQuotes(String val) {
        if (val == null) return "";
        val = val.trim();
        if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
            return val.substring(1, val.length() - 1).replace("\"\"", "\"").trim();
        }
        return val;
    }

    public static void validateBatchApproval(UUID preparerPersonId, UUID approverPersonId) {
        Objects.requireNonNull(preparerPersonId, "preparerPersonId must not be null");
        Objects.requireNonNull(approverPersonId, "approverPersonId must not be null");
        if (preparerPersonId.equals(approverPersonId)) {
            throw new IllegalStateException("SOD_VIOLATION: Preparer cannot approve their own import batch");
        }
    }

    public static ReconciliationResult reconcileControlTotals(
            BigDecimal expectedDebit,
            BigDecimal expectedCredit,
            BigDecimal actualDebit,
            BigDecimal actualCredit,
            int expectedRows,
            int actualRows
    ) {
        expectedDebit = expectedDebit.setScale(8, RoundingMode.HALF_UP);
        actualDebit = actualDebit.setScale(8, RoundingMode.HALF_UP);
        expectedCredit = expectedCredit.setScale(8, RoundingMode.HALF_UP);
        actualCredit = actualCredit.setScale(8, RoundingMode.HALF_UP);

        boolean rowsMatch = (expectedRows == actualRows);
        boolean debitMatch = (expectedDebit.compareTo(actualDebit) == 0);
        boolean creditMatch = (expectedCredit.compareTo(actualCredit) == 0);

        boolean matched = rowsMatch && debitMatch && creditMatch;
        return new ReconciliationResult(
                matched,
                expectedDebit,
                actualDebit,
                expectedCredit,
                actualCredit,
                expectedRows,
                actualRows,
                matched ? "RECONCILED" : "MISMATCH_DETECTED"
        );
    }
}
