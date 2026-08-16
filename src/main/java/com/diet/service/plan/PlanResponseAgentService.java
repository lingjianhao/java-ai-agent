package com.diet.service.plan;

import com.diet.agent.factory.AgentFactory;
import com.diet.enums.SourceMode;
import com.diet.model.*;
import com.diet.service.recommend.RecommendResponseAgentService;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多餐规划应答 Agent：按餐次生成理由与结构化口语回复。
 */
@Service
public class PlanResponseAgentService {

    private final AgentFactory agentFactory;
    private final LlmJsonService llmJsonService;
    private final AgentTraceService agentTraceService;
    private final String modelName;

    public PlanResponseAgentService(
            AgentFactory agentFactory,
            LlmJsonService llmJsonService,
            AgentTraceService agentTraceService,
            @Value("${diet.llm.main-model:qwen-max}") String modelName
    ) {
        this.agentFactory = agentFactory;
        this.llmJsonService = llmJsonService;
        this.agentTraceService = agentTraceService;
        this.modelName = modelName;
    }

    /**
     * 将按餐次选出的方案包装为 RecommendResult + ResponseResult。
     */
    public RecommendResponseAgentService.Result planAndRespond(
            String sessionId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle sharedSlots,
            List<MealPlanService.PlannedMeal> plannedMeals
    ) {
        List<MealPlanService.PlannedMeal> safePlans = plannedMeals == null ? List.of() : plannedMeals;
        List<MealPlanService.PlannedMeal> matched = safePlans.stream().filter(MealPlanService.PlannedMeal::matched).toList();
        boolean needDisclaimer = needsDisclaimer(sharedSlots);

        if (matched.isEmpty()) {
            RecommendResult empty = RecommendResult.empty();
            return new RecommendResponseAgentService.Result(
                    empty,
                    ResponseResult.textOnly("暂时没有拼出完整的多餐方案，你可以补充口味、菜系，或切换数据源后再试。")
            );
        }

        try {
            ReActAgent agent = agentFactory.get(sessionId).planResponse();
            agent.getMemory().clear();
            Msg response = agentTraceService.callAgent(
                    sessionId,
                    "PlanResponseAgent",
                    modelName,
                    agent,
                    buildUserPrompt(userInput, sourceMode, sharedSlots, safePlans)
            );
            ParsedOutput parsed = parseOutput(response.getTextContent(), safePlans, sharedSlots);
            RecommendResult recommend = new RecommendResult(parsed.options(), needDisclaimer);
            ResponseResult responseResult = new ResponseResult(parsed.speechText(), toDisplayBlocks(recommend), "WAIT_USER");
            return new RecommendResponseAgentService.Result(recommend, responseResult);
        } catch (Exception ignored) {
            RecommendResult recommend = new RecommendResult(templateOptions(safePlans, sharedSlots), needDisclaimer);
            return new RecommendResponseAgentService.Result(
                    recommend,
                    new ResponseResult(templateSpeech(safePlans, recommend), toDisplayBlocks(recommend), "WAIT_USER")
            );
        }
    }

    private String buildUserPrompt(
            String userInput,
            SourceMode sourceMode,
            SlotBundle sharedSlots,
            List<MealPlanService.PlannedMeal> plannedMeals
    ) {
        StringBuilder mealSection = new StringBuilder();
        for (MealPlanService.PlannedMeal planned : plannedMeals) {
            mealSection.append("\n- 餐次=").append(planned.mealTime());
            if (planned.matched()) {
                MealItem meal = planned.meal();
                mealSection.append("，候选=[mealId=").append(meal.id())
                        .append(", name=").append(meal.name())
                        .append(", score=").append(meal.matchScore())
                        .append("]");
            } else {
                mealSection.append("，候选=[]（暂无匹配）");
            }
        }
        return """
                用户原话：%s
                数据源模式：%s
                共享槽位：%s
                各餐次候选：%s
                请输出 JSON，包含 mealPlans 数组（每项 mealTime + mealId + reason）和 speechText；mealId 必须来自对应餐次候选。
                """.formatted(userInput, sourceMode, sharedSlots, mealSection);
    }

    private ParsedOutput parseOutput(String content, List<MealPlanService.PlannedMeal> plannedMeals, SlotBundle sharedSlots) {
        JsonNode root = llmJsonService.parseObject(content);
        Map<String, String> reasonsByMealTime = new LinkedHashMap<>();
        JsonNode plansNode = root.path("mealPlans");
        if (plansNode.isArray()) {
            plansNode.forEach(node -> {
                String mealTime = node.path("mealTime").asText("").trim();
                String reason = node.path("reason").asText("").trim();
                if (!mealTime.isBlank() && !reason.isBlank()) {
                    reasonsByMealTime.put(mealTime, reason);
                }
            });
        }

        List<RecommendedMealOption> options = new ArrayList<>();
        for (MealPlanService.PlannedMeal planned : plannedMeals) {
            if (!planned.matched()) {
                continue;
            }
            MealItem meal = planned.meal();
            // 以 Java 已选餐食为准，避免 LLM 跨餐次挪用 mealId
            String reason = reasonsByMealTime.getOrDefault(planned.mealTime(), templateReason(planned, sharedSlots));
            options.add(toOption(meal, reason, planned.querySlots()));
        }

        String speechText = root.path("speechText").asText("").trim();
        if (speechText.isBlank()) {
            speechText = templateSpeech(plannedMeals, new RecommendResult(options, needsDisclaimer(sharedSlots)));
        }
        return new ParsedOutput(options, speechText);
    }

    private List<RecommendedMealOption> templateOptions(List<MealPlanService.PlannedMeal> plannedMeals, SlotBundle sharedSlots) {
        List<RecommendedMealOption> options = new ArrayList<>();
        for (MealPlanService.PlannedMeal planned : plannedMeals) {
            if (!planned.matched()) {
                continue;
            }
            options.add(toOption(planned.meal(), templateReason(planned, sharedSlots), planned.querySlots()));
        }
        return options;
    }

    private RecommendedMealOption toOption(MealItem meal, String reason, SlotBundle querySlots) {
        SlotBundle displaySlots = querySlots != null ? querySlots : meal.slots();
        return new RecommendedMealOption(meal.id(), meal.sourceType(), meal.name(), reason, meal.matchScore(), displaySlots);
    }

    private String templateReason(MealPlanService.PlannedMeal planned, SlotBundle sharedSlots) {
        String name = planned.meal().name();
        if (sharedSlots != null && !sharedSlots.healthGoal().isEmpty()) {
            return name + "比较符合你提到的" + String.join("、", sharedSlots.healthGoal()) + "诉求，适合作为" + planned.mealTime() + "。";
        }
        if (sharedSlots != null && !sharedSlots.taste().isEmpty()) {
            return name + "比较贴近你想要的" + String.join("、", sharedSlots.taste()) + "口味，适合" + planned.mealTime() + "。";
        }
        return name + "和你的偏好匹配度较高，适合安排在" + planned.mealTime() + "。";
    }

    private String templateSpeech(List<MealPlanService.PlannedMeal> plannedMeals, RecommendResult recommendResult) {
        StringBuilder builder = new StringBuilder("今天按餐次给你搭了一套方案：");
        for (MealPlanService.PlannedMeal planned : plannedMeals) {
            builder.append("\n- ").append(planned.mealTime()).append("：");
            if (planned.matched()) {
                String reason = recommendResult.recommendations().stream()
                        .filter(option -> option.itemId().equals(planned.meal().id()))
                        .map(RecommendedMealOption::reason)
                        .findFirst()
                        .orElse(planned.meal().name());
                builder.append(planned.meal().name()).append("（").append(reason).append("）");
            } else {
                builder.append("暂时没有很匹配的餐食");
            }
        }
        builder.append("\n想换其中某一餐，或者整体再清淡/顶饱一点，直接跟我说就行。");
        if (recommendResult.needDisclaimer()) {
            builder.append("\n这些建议只做日常饮食参考，如果有明确疾病或特殊身体情况，建议咨询医生或营养师。");
        }
        return builder.toString();
    }

    private List<MealResponse> toDisplayBlocks(RecommendResult recommendResult) {
        if (recommendResult == null || recommendResult.recommendations() == null) {
            return List.of();
        }
        return recommendResult.recommendations().stream()
                .map(option -> new MealResponse(
                        option.itemId(),
                        option.sourceType(),
                        option.name(),
                        option.matchedSlots().mealTime(),
                        option.matchedSlots().mood(),
                        option.matchedSlots().scene(),
                        option.matchedSlots().healthGoal(),
                        option.matchedSlots().cuisine(),
                        option.matchedSlots().taste(),
                        option.matchedSlots().convenience(),
                        option.matchScore()
                ))
                .toList();
    }

    private boolean needsDisclaimer(SlotBundle slots) {
        return slots != null && slots.healthGoal().stream().anyMatch(value ->
                value.contains("减脂") || value.contains("低糖") || value.contains("控碳水") || value.contains("养胃"));
    }

    private record ParsedOutput(List<RecommendedMealOption> options, String speechText) {
    }
}