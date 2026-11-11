package com.lumira.finance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FinanceTestCredentials {
    private FinanceTestCredentials() {}

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://127.0.0.1:15489/lumira_qa_finance_kernel";
    private static final String DEFAULT_USER = "finance_qa";

    public static String jdbcUrl() {
        String url = System.getenv("FINANCE_TEST_JDBC_URL");
        if (url != null && !url.isBlank()) return url;
        return DEFAULT_JDBC_URL;
    }

    public static String databaseUser() {
        String user = System.getenv("FINANCE_TEST_DB_USER");
        if (user != null && !user.isBlank()) return user;
        for (String p : List.of(".runtime/finance-qa.json", "../../../.runtime/finance-qa.json", "../../../../.runtime/finance-qa.json")) {
            Path path = Path.of(p);
            if (Files.exists(path)) {
                try {
                    String json = Files.readString(path).replace("\uFEFF", "");
                    Matcher m = Pattern.compile("\"(?:FINANCE_TEST_DB_USER|LUMIRA_DB_USER)\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                    if (m.find()) return m.group(1);
                } catch (Exception ignored) {}
            }
        }
        return DEFAULT_USER;
    }

    public static String databasePassword() {
        String pass = System.getenv("FINANCE_TEST_DB_PASSWORD");
        if (pass != null && !pass.isBlank()) return pass;
        for (String p : List.of(".runtime/finance-qa.json", "../../../.runtime/finance-qa.json", "../../../../.runtime/finance-qa.json")) {
            Path path = Path.of(p);
            if (Files.exists(path)) {
                try {
                    String json = Files.readString(path).replace("\uFEFF", "");
                    Matcher m = Pattern.compile("\"(?:FINANCE_TEST_DB_PASSWORD|LUMIRA_DB_PASSWORD)\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                    if (m.find()) return m.group(1);
                } catch (Exception ignored) {}
            }
        }
        return "";
    }
}
