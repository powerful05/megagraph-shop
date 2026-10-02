package com.megagraph.shop.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GuideGuidanceTest {
    private GuideIntent physical() {
        return new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, null, "CNY", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), "");
    }

    private GuideModel.Candidate notebook() {
        var product = GuideEvidenceTest.notebook("TEST_CONFIRMED");
        return new GuideModel.Candidate(product.id(), product.title(), product.subtitle(), "29", "CNY",
                List.of("A5"), product.evidence(), GuideEvidence.facts(product));
    }

    @Test
    void tactileCheckRemainsUsefulWithoutClaimingTouchFromComposition() {
        var plan = new GuideGuidance.Plan(GuideGuidance.Question.PRIORITY, List.of(GuideGuidance.Check.MATERIAL_FEEL), "贴身穿");
        assertThat(GuideGuidance.valid(plan, physical(), List.of(), "想贴身穿，材质能证明手感吗")).isTrue();
        assertThat(GuideGuidance.render(plan, physical())).contains("最看重", "核对商家的手感", "不只按材料")
                .doesNotContain("更柔和", "这款柔软", "保证", "防水");
    }

    @Test
    void questionSubjectMustComeFromCurrentUserNotOldAssistantOrProduct() {
        var plan = new GuideGuidance.Plan(GuideGuidance.Question.USE_CASE, List.of(), "有领落肩");
        assertThat(GuideGuidance.valid(plan, physical(), List.of(notebook()), "改找纸本")).isFalse();
        var valid = new GuideGuidance.Plan(GuideGuidance.Question.PURPOSE, List.of(), "换点感觉");
        assertThat(GuideGuidance.valid(valid, GuideIntent.empty(), List.of(), "最近想换点感觉")).isTrue();
        assertThat(GuideGuidance.render(valid, GuideIntent.empty())).contains("换点感觉", "场景", "？")
                .doesNotContain("衣柜", "版型");
    }

    @Test
    void paperOrClosureAdviceNeedsRelevantConfirmedFieldsNotJustGeneralProfile() {
        var paper = new GuideGuidance.Plan(GuideGuidance.Question.NONE, List.of(GuideGuidance.Check.PAPER_USE), "");
        var closure = new GuideGuidance.Plan(GuideGuidance.Question.NONE, List.of(GuideGuidance.Check.CLOSURE), "");
        assertThat(GuideGuidance.valid(paper, physical(), List.of(notebook()), "纸本")).isTrue();
        assertThat(GuideGuidance.valid(closure, physical(), List.of(notebook()), "纸本")).isFalse();
        var current = notebook();
        var unknown = new GuideModel.Candidate(current.id(), current.title(), current.subtitle(), "29", "CNY",
                current.skuNames(), current.evidence(), List.of(new GuideEvidence.Fact("material", "材质", "棉")));
        assertThat(GuideGuidance.valid(paper, physical(), List.of(unknown), "纸本")).isFalse();
        assertThat(GuideGuidance.allowed(physical(), List.of()).get("checks").toString()).doesNotContain("PAPER_USE", "CLOSURE");
        assertThat(GuideGuidance.allowed(physical(), List.of(current)).get("checks").toString()).contains("PAPER_USE");
        var confirmed = new GuideModel.Candidate(current.id(), current.title(), current.subtitle(), "29", "CNY",
                current.skuNames(), current.evidence(), List.of(new GuideEvidence.Fact("spec-1", "内页", "横线，160页")));
        assertThat(GuideGuidance.valid(paper, physical(), List.of(confirmed), "纸本")).isTrue();
        assertThat(GuideGuidance.render(paper, physical())).contains("用笔习惯", "核对防洇墨")
                .doesNotContain("已防洇墨", "可以防水");
    }

    @Test
    void generalPhysicalItemsCannotReceiveBodyFitAdvice() {
        var plan = new GuideGuidance.Plan(GuideGuidance.Question.SIZE, List.of(GuideGuidance.Check.GARMENT_MEASUREMENTS), "");
        assertThat(GuideGuidance.valid(plan, physical(), List.of(notebook()), "A5纸本")).isFalse();
        var dimensions = new GuideGuidance.Plan(GuideGuidance.Question.NONE, List.of(GuideGuidance.Check.ITEM_DIMENSIONS), "");
        assertThat(GuideGuidance.valid(dimensions, physical(), List.of(notebook()), "A5纸本")).isTrue();
        assertThat(GuideGuidance.render(dimensions, physical())).contains("物品", "尺寸").doesNotContain("胸围", "合身");
    }

    @Test
    void fitQuestionRemainsAnswerableWithoutProductsOrBodyFitClaims() {
        var intent = new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, null, "CNY", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), "M", true);
        var plan = new GuideGuidance.Plan(GuideGuidance.Question.SIZE,
                List.of(GuideGuidance.Check.FIT_CONFIRMATION), "平时穿M");
        assertThat(GuideGuidance.valid(plan, intent, List.of(), "我平时穿M，能保证都合身吗？")).isTrue();
        assertThat(GuideGuidance.render(plan, intent)).contains("实测尺寸", "不能保证", "实际尺寸")
                .doesNotContain("保证合身", "胸围", "衣长", "想先改善");
        var digital = new GuideIntent(GuideIntent.DeliveryType.DIGITAL, null, "CNY", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), "");
        assertThat(GuideGuidance.valid(plan, digital, List.of(), "平时穿M")).isFalse();
        assertThat(GuideGuidance.allowed(digital, List.of()).get("checks").toString()).doesNotContain("FIT_CONFIRMATION");
    }

    @Test
    void previouslyProvidedBudgetIsNotAskedAgain() {
        var intent = new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, new BigDecimal("30"), "CNY", List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), "");
        var budget = new GuideGuidance.Plan(GuideGuidance.Question.BUDGET, List.of(), "");
        assertThat(GuideGuidance.valid(budget, intent, List.of(), "预算改30")).isFalse();
        assertThat(GuideGuidance.valid(budget, physical(), List.of(), "找纸本")).isTrue();
    }

    @Test
    void codesCannotCreateFreeTextOrUnboundedQuestionPlans() {
        assertThatThrownBy(() -> GuideGuidance.Check.valueOf("COTTON_IS_SOFT")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuideGuidance.Plan(GuideGuidance.Question.NONE,
                List.of(GuideGuidance.Check.GENERAL_PRIORITY, GuideGuidance.Check.GENERAL_PRIORITY), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void digitalAndServiceChecksDoNotSuggestPhysicalAlternatives() {
        var digital = new GuideIntent(GuideIntent.DeliveryType.DIGITAL, null, "CNY", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), "");
        var plan = new GuideGuidance.Plan(GuideGuidance.Question.DIGITAL_GOAL, List.of(GuideGuidance.Check.DIGITAL_RIGHTS), "摄影");
        assertThat(GuideGuidance.valid(plan, digital, List.of(), "学摄影课程")).isTrue();
        assertThat(GuideGuidance.valid(plan, physical(), List.of(notebook()), "摄影")).isFalse();
        assertThat(GuideGuidance.render(plan, digital)).contains("摄影", "授权方式").doesNotContain("衣服", "版型", "已购买");
    }
}
