package com.lumira.finance.assets;

import static com.lumira.finance.assets.AssetModel.*;
import static com.lumira.finance.assets.AssetRules.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

class AssetRulesTest {
    static Card card(String cost,String residual,int months,String framework) {
        return new Card(UUID.randomUUID(),"Synthetic equipment","EQUIPMENT",LocalDate.of(2026,1,1),LocalDate.of(2026,1,1),"QA location",UUID.randomUUID(),
            new Policy(framework,"FIXED","STRAIGHT_LINE","2026-01",months,money(residual),UUID.randomUUID(),ZERO,0),
            new Accounts(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()),money(cost),ZERO,ZERO,0,null,1,"IN_USE");
    }
    @Test void independentThreeMonthExampleKeepsFinalRemainder() {
        for(String framework:List.of("CAS","SMALL")) {
            var rows=schedule(card("100","0",3,framework));
            assertEquals(List.of("33.33000000","33.33000000","33.34000000"),rows.stream().map(r->r.amount().toPlainString()).toList());
            assertEquals(ZERO,rows.getLast().remaining());
        }
    }
    @Test void residualAndCrossYearContinuity() {
        Card old=card("1200","120",12,"SMALL");
        Policy p=new Policy("SMALL","FIXED","STRAIGHT_LINE","2026-12",12,money("120"),UUID.randomUUID(),ZERO,0);
        Card c=copy(old,p,ZERO,0,null,1,"IN_USE");
        Change dec=depreciate(c,"2026-12"),jan=depreciate(dec.after(),"2027-01");
        assertEquals(c.id(),jan.after().id());assertEquals(money("180"),jan.after().accumulated());
        assertEquals(money("1080"),schedule(c).stream().map(ScheduleRow::amount).reduce(ZERO,BigDecimal::add));
    }
    @Test void rawCnyNeverRoundsInvalidInput() {
        for(String raw:List.of("1.000","1.005","1e2","-1","01","100000000000000000000")) assertThrows(IllegalArgumentException.class,()->money(raw));
        assertEquals(new BigDecimal("1.00000000"),money("1.00"));
    }
    @Test void duplicateAndSkippedPeriodRejected() {
        Card c=card("100","10",3,"CAS");Card after=depreciate(c,"2026-01").after();
        assertThrows(IllegalArgumentException.class,()->depreciate(after,"2026-01"));
        assertThrows(IllegalArgumentException.class,()->depreciate(after,"2026-03"));
    }
    @Test void estimatePreservesPastAndUsesNewBasis() {
        Card c=depreciate(card("100","0",4,"CAS"),"2026-01").after();
        Policy p=new Policy("CAS","FIXED","STRAIGHT_LINE","2026-01",3,money("5"),UUID.randomUUID(),c.accumulated(),c.elapsed());
        Card revised=copy(c,p,c.accumulated(),c.elapsed(),c.lastPeriod(),c.version()+1,"IN_USE");
        assertEquals(money("25"),revised.accumulated());
        assertEquals(List.of(money("35"),money("35")),schedule(revised).stream().map(ScheduleRow::amount).toList());
        assertEquals(3,schedule(c).size());
    }
    @Test void samePersonDifferentAccountCannotApprove() {
        UUID person=UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->independent(new Actor(UUID.randomUUID(),person),new Actor(UUID.randomUUID(),person)));
    }
    @Test void reversalSwapsSidesOnceAndNeverCreatesNegativeAmounts() {
        var original=depreciationLines(depreciate(card("100","0",3,"CAS"),"2026-01"));
        var reversed=reverse(original);balanced(reversed);
        assertEquals(original,reverse(reversed));assertEquals(original.getFirst().debit(),reversed.getFirst().credit());
        assertTrue(reversed.stream().allMatch(l->l.debit().signum()>=0&&l.credit().signum()>=0));
    }
    @Test void impairmentAndUnknownPolicyCannotPretendApplicable() {
        Card c=card("100","0",3,"SMALL");
        Card invalid=new Card(c.id(),c.name(),c.classification(),c.acquired(),c.ready(),c.location(),c.custodian(),c.policy(),c.accounts(),c.cost(),ZERO,money("1"),0,null,1,"IN_USE");
        assertThrows(IllegalArgumentException.class,()->valid(invalid));
        Policy unknown=new Policy("CAS","INTANGIBLE_INDEFINITE","STRAIGHT_LINE","2026-01",3,ZERO,UUID.randomUUID(),ZERO,0);
        assertThrows(IllegalArgumentException.class,()->valid(copy(c,unknown,ZERO,0,null,1,"IN_USE")));
    }
    @Test void tinyAmountsCannotOverDepreciate() {
        var rows=schedule(card("0.01","0",3,"CAS"));
        assertEquals(List.of(ZERO,ZERO,money("0.01")),rows.stream().map(ScheduleRow::amount).toList());
    }
    @Test void repositoryJacksonRoundTripPreservesScaleAndDigest() throws Exception {
        var repo=new AssetRepository(new tools.jackson.databind.ObjectMapper());
        Card c=card("100","0",3,"CAS");
        Change ch=depreciate(c,"2026-01");
        Scope s=new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),LocalDate.of(2026,1,31));
        Plan p=new Plan(UUID.randomUUID(),s,"DEPRECIATE","DEPRECIATE/"+c.id()+"/2026-01",null,"Monthly depreciation",
                new Actor(UUID.randomUUID(),UUID.randomUUID()),List.of(ch),depreciationLines(ch),
                new EstimatePreview(schedule(c),schedule(ch.after())),null,ZERO);
        String encoded=repo.encode(p);
        assertTrue(encoded.contains("\"33.33000000\""));
        Plan decoded=repo.decode(encoded,Plan.class);
        assertEquals(p,decoded);
        assertEquals(repo.hash(p),repo.hash(decoded));
    }
}
