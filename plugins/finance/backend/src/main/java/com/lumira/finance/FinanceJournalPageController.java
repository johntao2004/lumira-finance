package com.lumira.finance;

import com.lumira.platform.modules.ModuleEndpoint;

import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}")
@ModuleEndpoint("finance")
public class FinanceJournalPageController {
    final FinanceJournalPages pages;

    public FinanceJournalPageController(FinanceJournalPages pages) {
        this.pages = pages;
    }

    @GetMapping("/journal-pages")
    public Object journals(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestParam MultiValueMap<String, String> query) {
        return FinanceHttp.respond(
                200,
                () ->
                        FinanceJournalPages.boundary(
                                () ->
                                        pages.page(
                                                actor.getName(),
                                                FinanceJournalPages.uuid(book),
                                                FinanceJournalPages.uuid(year),
                                                "journals",
                                                query)));
    }

    @GetMapping("/ledger-pages")
    public Object ledger(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestParam MultiValueMap<String, String> query) {
        return FinanceHttp.respond(
                200,
                () ->
                        FinanceJournalPages.boundary(
                                () ->
                                        pages.page(
                                                actor.getName(),
                                                FinanceJournalPages.uuid(book),
                                                FinanceJournalPages.uuid(year),
                                                "ledger",
                                                query)));
    }
}
