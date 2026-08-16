package com.diet.service.plan;

import com.diet.enums.SourceMode;
import com.diet.model.MealItem;
import com.diet.model.MealRankRequest;
import com.diet.model.MealSearchRequest;
import com.diet.model.SlotBundle;
import com.diet.service.meal.MealRankService;
import com.diet.service.meal.MealSearchService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 多餐规划服务：解析目标餐次，并按餐次拆分检索/重排后各取一款。
 */
@Service
public class MealPlanService {

    /** 默认三餐规划目标。 */
    public static final List<String> DEFAULT_PLAN_MEAL_TIMES = List.of("早餐", "午餐", "晚餐");

    /** 「三餐」聚合标签，需展开为具体餐次。 */
    private static final String AGGREGATE_THREE_MEALS = "三餐";

    private final MealSearchService mealSearchService;
    private final MealRankService mealRankService;

    public MealPlanService(MealSearchService mealSearchService, MealRankService mealRankService) {
        this.mealSearchService = mealSearchService;
        this.mealRankService = mealRankService;
    }

    /**
     * 从合并后的槽位解析规划餐次。
     *   含「三餐」或为空 → 默认早/中/晚
     *   含 ≥2 个具体餐次 → 按用户指定顺序去重保留
     *   仅 1 个具体餐次 → 仍按默认三餐规划（MEAL_PLAN 语义是多餐）
     */
    public List<String> resolveMealTimes(SlotBundle slots) {
        List<String> raw = slots == null || slots.mealTime() == null ? List.of() : slots.mealTime();
        if (raw.isEmpty() || raw.stream().anyMatch(AGGREGATE_THREE_MEALS::equals)) {
            return DEFAULT_PLAN_MEAL_TIMES;
        }
        List<String> specific = raw.stream()
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> !AGGREGATE_THREE_MEALS.equals(value))
                .distinct()
                .toList();
        if (specific.size() >= 2) {
            return specific;
        }
        return DEFAULT_PLAN_MEAL_TIMES;
    }

    /**
     * 复制共享槽位，仅替换 mealTime 为单一餐次。
     */
    public SlotBundle slotsForMealTime(SlotBundle base, String mealTime) {
        SlotBundle safe = base == null ? SlotBundle.empty() : base;
        return new SlotBundle(
                List.of(mealTime),
                safe.mood(),
                safe.scene(),
                safe.healthGoal(),
                safe.cuisine(),
                safe.taste(),
                safe.convenience()
        );
    }

    /**
     * 按餐次依次检索重排，每餐取 top1；跨餐次排除已选 mealId，避免三餐重复同一道菜。
     */
    public List<PlannedMeal> planMeals(SourceMode sourceMode, Long userId, SlotBundle baseSlots, List<String> mealTimes) {
        List<String> targets = mealTimes == null || mealTimes.isEmpty() ? DEFAULT_PLAN_MEAL_TIMES : mealTimes;
        List<PlannedMeal> planned = new ArrayList<>();
        Set<Long> usedIds = new LinkedHashSet<>();

        for (String mealTime : targets) {
            SlotBundle querySlots = slotsForMealTime(baseSlots, mealTime);
            List<Long> excludeIds = List.copyOf(usedIds);
            List<MealItem> candidates = mealSearchService.search(
                    new MealSearchRequest(sourceMode, userId, querySlots, excludeIds));
            List<MealItem> ranked = mealRankService.rank(
                    new MealRankRequest(candidates, querySlots, excludeIds));
            MealItem picked = ranked.stream()
                    .filter(item -> item != null && item.id() != null && !usedIds.contains(item.id()))
                    .findFirst()
                    .orElse(null);
            if (picked != null) {
                usedIds.add(picked.id());
                planned.add(new PlannedMeal(mealTime, picked, querySlots));
            } else {
                planned.add(new PlannedMeal(mealTime, null, querySlots));
            }
        }
        return planned;
    }

    /**
     * 单餐规划结果：餐次 + 命中餐食（可能为空）+ 该餐检索用槽位。
     */
    public record PlannedMeal(String mealTime, MealItem meal, SlotBundle querySlots) {
        public boolean matched() {
            return meal != null && meal.id() != null;
        }
    }
}