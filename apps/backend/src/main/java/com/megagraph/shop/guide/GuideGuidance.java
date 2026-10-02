package com.megagraph.shop.guide;

import com.megagraph.shop.api.SupplyDetails;
import java.util.ArrayList;
import java.util.List;

public final class GuideGuidance {
    public static final String VERSION = "guidance-plan-v1";

    public enum Question { NONE, PURPOSE, USE_CASE, PRIORITY, BUDGET, SIZE, DIGITAL_GOAL, SERVICE_GOAL }
    public enum Check {
        MATERIAL_FEEL, FIT_CONFIRMATION, GARMENT_MEASUREMENTS, ITEM_DIMENSIONS, CLOSURE, PAPER_USE,
        PERFORMANCE_EVIDENCE, CONFIRMED_DIFFERENCES, BUDGET_PRIORITY, DIGITAL_RIGHTS, SERVICE_SCOPE, GENERAL_PRIORITY,
        MANUAL_CONFIRMATION
    }

    public record Plan(Question question, List<Check> checks, String subject) {
        public Plan {
            if (question == null || checks == null || checks.size() > 3 || checks.stream().anyMatch(item -> item == null)
                    || checks.stream().distinct().count() != checks.size() || subject == null || subject.length() > 24
                    || subject.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid guide guidance plan");
            }
            checks = List.copyOf(checks);
        }

        public static Plan general() { return new Plan(Question.NONE, List.of(Check.GENERAL_PRIORITY), ""); }
    }

    private GuideGuidance() {}

    static boolean valid(Plan plan, GuideIntent intent, List<GuideModel.Candidate> selected, String currentUserText) {
        if (plan == null || !plan.subject().isEmpty() && !currentUserText.contains(plan.subject())) return false;
        boolean apparel = selected.stream().anyMatch(candidate -> candidate.evidence() != null
                && candidate.evidence().details() != null && candidate.evidence().details().profile() == SupplyDetails.Profile.APPAREL);
        boolean general = selected.stream().anyMatch(candidate -> candidate.evidence() != null
                && candidate.evidence().details() != null && candidate.evidence().details().profile() == SupplyDetails.Profile.GENERAL_PHYSICAL);
        if (plan.question() == Question.BUDGET && intent.maxPrice() != null
                || plan.question() == Question.SIZE && (intent.deliveryType() == GuideIntent.DeliveryType.DIGITAL
                        || intent.deliveryType() == GuideIntent.DeliveryType.SERVICE)
                || plan.question() == Question.DIGITAL_GOAL && intent.deliveryType() != GuideIntent.DeliveryType.DIGITAL
                || plan.question() == Question.SERVICE_GOAL && intent.deliveryType() != GuideIntent.DeliveryType.SERVICE) return false;
        return plan.checks().stream().allMatch(check -> switch (check) {
            case FIT_CONFIRMATION -> intent.deliveryType() == GuideIntent.DeliveryType.PHYSICAL
                    || intent.deliveryType() == GuideIntent.DeliveryType.UNKNOWN;
            case GARMENT_MEASUREMENTS -> apparel;
            case ITEM_DIMENSIONS -> general && hasSpecification(selected);
            case CLOSURE -> general && hasLabel(selected, "封口");
            case PAPER_USE -> general && (hasLabel(selected, "内页") || selected.stream().flatMap(candidate -> candidate.facts().stream())
                    .anyMatch(fact -> fact.key().equals("material") && fact.value().contains("纸质内页")));
            case CONFIRMED_DIFFERENCES -> selected.size() > 1;
            case DIGITAL_RIGHTS -> intent.deliveryType() == GuideIntent.DeliveryType.DIGITAL;
            case SERVICE_SCOPE -> intent.deliveryType() == GuideIntent.DeliveryType.SERVICE;
            case MATERIAL_FEEL -> intent.deliveryType() == GuideIntent.DeliveryType.PHYSICAL || apparel || general;
            default -> true;
        });
    }

    private static boolean hasLabel(List<GuideModel.Candidate> selected, String label) {
        return selected.stream().flatMap(candidate -> candidate.facts().stream()).anyMatch(fact -> fact.label().contains(label));
    }

    static java.util.Map<String, Object> allowed(GuideIntent intent, List<GuideModel.Candidate> selected) {
        var questions = java.util.Arrays.stream(Question.values()).filter(question ->
                valid(new Plan(question, List.of(), ""), intent, selected, "")).map(Enum::name).toList();
        var checks = java.util.Arrays.stream(Check.values()).filter(check ->
                valid(new Plan(Question.NONE, List.of(check), ""), intent, selected, "")).map(Enum::name).toList();
        return java.util.Map.of("questions", questions, "checks", checks);
    }

    private static boolean hasSpecification(List<GuideModel.Candidate> selected) {
        return selected.stream().flatMap(candidate -> candidate.facts().stream()).anyMatch(fact -> fact.key().startsWith("spec-"));
    }

    static String render(Plan plan, GuideIntent intent) {
        var parts = new ArrayList<String>();
        if (plan.question() != Question.NONE) {
            if (!plan.subject().isBlank()) parts.add("你提到「" + plan.subject() + "」。");
            parts.add(switch (plan.question()) {
                case PURPOSE -> "想先改善哪个场景，或解决什么问题？";
                case USE_CASE -> "主要会在什么场景使用？";
                case PRIORITY -> "这次选择最看重哪一点？";
                case BUDGET -> "单件预算上限大约是多少？";
                case SIZE -> "希望核对哪种规格，或有哪些实测尺寸可以参考？";
                case DIGITAL_GOAL -> "希望通过课程或工具学会什么、完成什么目标？";
                case SERVICE_GOAL -> "希望这项服务具体解决什么问题？";
                case NONE -> throw new IllegalStateException("No question to render");
            });
        }
        for (Check check : plan.checks()) parts.add(switch (check) {
            case MATERIAL_FEEL -> "有触感要求时，先核对商家的手感或试用资料，不只按材料名称判断。";
            case FIT_CONFIRMATION -> "仅凭规格标签不能保证适配你的需要，需核对实际尺寸和使用条件。";
            case GARMENT_MEASUREMENTS -> "用自己的实测尺寸和活动余量核对成衣测量，不只按尺码标签判断合身。";
            case ITEM_DIMENSIONS -> "先量好要放入的物品，再核对商品对应规格和尺寸。";
            case CLOSURE -> "把封口方式与日常携带习惯一起比较，必要时向商家补充确认。";
            case PAPER_USE -> "按常用纸张大小、页数、装订与用笔习惯选择；钢笔使用还需核对防洇墨资料。";
            case PERFORMANCE_EVIDENCE -> "把某项性能作为必须条件时，先取得对应的验证资料，再决定是否选择。";
            case CONFIRMED_DIFFERENCES -> "先对照各款已确认的用途和规格差异，再看哪些缺失资料会影响你的选择。";
            case BUDGET_PRIORITY -> "先排清必须满足的条件与可取舍的偏好，再按单件预算比较。";
            case DIGITAL_RIGHTS -> "比较数字内容时，先明确内容范围、使用期限、授权方式和交付形式。";
            case SERVICE_SCOPE -> "比较服务时，先明确服务范围、时间安排和完成标准。";
            case GENERAL_PRIORITY -> "先明确使用场景和最重要的条件，再做取舍。";
            case MANUAL_CONFIRMATION -> "我不能替你自动加购、下单或支付；需要你确认商品后自行操作。";
        });
        if (parts.isEmpty()) {
            if (intent.needsClarification()) return "想先改善哪个场景，或解决什么问题？";
            return "先明确使用场景和最重要的条件，再做取舍。";
        }
        return String.join("", parts);
    }
}
