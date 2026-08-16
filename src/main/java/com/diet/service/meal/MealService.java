package com.diet.service.meal;

import com.diet.exception.DietException;
import com.diet.mapper.MealMapper;
import com.diet.model.MealItem;
import com.diet.model.MealItemRow;
import com.diet.model.MealRequest;
import com.diet.model.SlotBundle;
import com.diet.enums.SourceMode;
import com.diet.service.slot.SlotOptionService;
import com.diet.util.JsonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 餐食数据服务。
 * 提供 CRUD 和基于 MySQL JSON_OVERLAPS 的标签检索；Orchestrator 推荐链路通过 {@link #search} 召回候选。
 */
@Service
public class MealService {

    /** 单次检索从 DB 拉取的最大行数，初排后取 top10 交给 Rank 层。 */
    private static final int SEARCH_LIMIT = 50;
    private final MealMapper mealMapper;
    private final SlotOptionService slotOptionService;
    private final JsonService jsonService;
    public MealService(MealMapper mealMapper, SlotOptionService slotOptionService, JsonService jsonService) {
        this.mealMapper = mealMapper;
        this.slotOptionService = slotOptionService;
        this.jsonService = jsonService;
    }

    public List<MealItem> findPersonalMeals(Long userId) {
        return mealMapper.findPersonalMeals(userId).stream().map(this::toMealItem).toList();
    }

    public List<MealItem> findPublicMeals() {
        return mealMapper.findPublicMeals().stream().map(this::toMealItem).toList();
    }

    /**
     * PERSONAL 模式空库前置检查。
     * 由 Orchestrator#handleTurn 调用，count > 0 才继续推荐链路。
     */
    public boolean hasPersonalMeals(Long userId) {
        return mealMapper.countPersonalMeals(userId) > 0; // 查个人餐食数量是否大于 0
    }

    @Transactional
    public MealItem createPersonalMeal(Long userId, MealRequest request) {
        validateMealRequest(request);
        MealItemRow row = toRow(null, SourceMode.PERSONAL, userId, request);
        mealMapper.insert(row);
        return toMealItem(row);
    }

    @Transactional
    public MealItem updatePersonalMeal(Long userId, Long mealId, MealRequest request) {
        validateMealRequest(request);
        MealItemRow row = toRow(mealId, SourceMode.PERSONAL, userId, request);
        int updated = mealMapper.updatePersonal(row);
        if (updated == 0) {
            throw new DietException("个人餐食不存在或无权限修改");
        }
        return toMealItem(mealMapper.findPersonalById(mealId, userId));
    }

    @Transactional
    public void deletePersonalMeal(Long userId, Long mealId) {
        int deleted = mealMapper.deletePersonal(mealId, userId);
        if (deleted == 0) {
            throw new DietException("个人餐食不存在或无权限删除");
        }
    }

    /**
     * 按槽位标签检索餐食并计算初排 matchScore。
     * 由 MealSearchService#search 调用；MySQL JSON_OVERLAPS 召回后 Java 侧 overlap 打分。
     */
    public List<MealItem> search(SourceMode sourceMode, Long userId, SlotBundle slots) {
        // MyBatis 执行 JSON_OVERLAPS 检索，7 维槽位各传 JSON 数组，最多拉 SEARCH_LIMIT=50 条
        List<MealItemRow> rows = mealMapper.search(
                sourceMode,                                      // PERSONAL 或 PUBLIC，决定查哪张数据
                userId,                                          // PERSONAL 时过滤 owner_user_id
                jsonService.toJsonArray(slots.mealTime()),       // 餐次标签 JSON 数组
                jsonService.toJsonArray(slots.mood()),           // 心情标签 JSON 数组
                jsonService.toJsonArray(slots.scene()),          // 场景标签 JSON 数组
                jsonService.toJsonArray(slots.healthGoal()),     // 健康目标 JSON 数组
                jsonService.toJsonArray(slots.cuisine()),        // 菜系 JSON 数组
                jsonService.toJsonArray(slots.taste()),          // 口味 JSON 数组
                jsonService.toJsonArray(slots.convenience()),    // 便捷性 JSON 数组
                SEARCH_LIMIT                                     // DB 层最多返回 50 行
        );
        // Row → MealItem
        return rows.stream().map(this::toMealItem).toList();
    }


    /**
     * 将公共库中的餐食复制到用户的个人库。
     * 如果同名餐食已存在于个人库中，则抛出异常防止重复添加。
     */
    @Transactional
    public MealItem copyPublicMealToPersonal(Long userId, Long publicMealId) {
        MealItemRow publicRow = mealMapper.findById(publicMealId);
        if (publicRow == null || !"PUBLIC".equals(publicRow.getSourceType())) {
            throw new DietException("公共餐食不存在");
        }
        // Check if user already has a personal meal with the same name
        List<MealItemRow> existing = mealMapper.findPersonalMeals(userId);
        boolean alreadyExists = existing.stream()
                .anyMatch(row -> row.getName().equals(publicRow.getName()));
        if (alreadyExists) {
            throw new DietException("个人库中已存在同名餐食，请勿重复添加");
        }
        MealItemRow newRow = new MealItemRow();
        newRow.setSourceType(SourceMode.PERSONAL.name());
        newRow.setOwnerUserId(userId);
        newRow.setName(publicRow.getName());
        newRow.setMealTime(publicRow.getMealTime());
        newRow.setMood(publicRow.getMood());
        newRow.setScene(publicRow.getScene());
        newRow.setHealthGoal(publicRow.getHealthGoal());
        newRow.setCuisine(publicRow.getCuisine());
        newRow.setTaste(publicRow.getTaste());
        newRow.setConvenience(publicRow.getConvenience());
        mealMapper.insert(newRow);
        return toMealItem(newRow);
    }

    /**
     * 将聊天推荐中的餐食导入当前用户的个人食谱。
     * 推荐卡片里的 mealId 可能来自公共库，也可能来自用户自己的个人库；
     * 只要该餐食尚未出现在当前用户的个人食谱中，就按其原始标签复制一份 PERSONAL 记录。
     * 若个人食谱中已存在同名餐食（含推荐即来自本人个人库的情况），则抛出异常防止重复导入。
     */
    @Transactional
    public MealItem importMealToPersonal(Long userId, Long mealId) {
        MealItemRow source = mealMapper.findById(mealId);
        if (source == null) {
            throw new DietException("餐食不存在，无法导入个人食谱");
        }
        // 推荐餐食本身已属于当前用户个人库时，视为已导入，给出明确提示
        if (SourceMode.PERSONAL.name().equals(source.getSourceType()) && userId.equals(source.getOwnerUserId())) {
            throw new DietException("该餐食已在你的个人食谱中");
        }
        List<MealItemRow> existing = mealMapper.findPersonalMeals(userId);
        boolean alreadyExists = existing.stream()
                .anyMatch(row -> row.getName().equals(source.getName()));
        if (alreadyExists) {
            throw new DietException("个人食谱中已存在同名餐食，请勿重复添加");
        }
        MealItemRow newRow = new MealItemRow();
        newRow.setSourceType(SourceMode.PERSONAL.name());
        newRow.setOwnerUserId(userId);
        newRow.setName(source.getName());
        newRow.setMealTime(source.getMealTime());
        newRow.setMood(source.getMood());
        newRow.setScene(source.getScene());
        newRow.setHealthGoal(source.getHealthGoal());
        newRow.setCuisine(source.getCuisine());
        newRow.setTaste(source.getTaste());
        newRow.setConvenience(source.getConvenience());
        mealMapper.insert(newRow);
        return toMealItem(newRow);
    }

    private void validateMealRequest(MealRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new DietException("餐食名称不能为空");
        }
        SlotBundle slots = request.toSlots();
        if (slots.mealTime().isEmpty()) {
            throw new DietException("餐次至少选择一个标签");
        }
        slotOptionService.validate(slots);
    }

    private MealItemRow toRow(Long id, SourceMode sourceMode, Long ownerUserId, MealRequest request) {
        SlotBundle slots = request.toSlots();
        MealItemRow row = new MealItemRow();
        row.setId(id);
        row.setSourceType(sourceMode.name());
        row.setOwnerUserId(ownerUserId);
        row.setName(request.name().trim());
        row.setMealTime(jsonService.toJsonArray(slots.mealTime()));
        row.setMood(jsonService.toJsonArray(slots.mood()));
        row.setScene(jsonService.toJsonArray(slots.scene()));
        row.setHealthGoal(jsonService.toJsonArray(slots.healthGoal()));
        row.setCuisine(jsonService.toJsonArray(slots.cuisine()));
        row.setTaste(jsonService.toJsonArray(slots.taste()));
        row.setConvenience(jsonService.toJsonArray(slots.convenience()));
        return row;
    }

    private MealItem toMealItem(MealItemRow row) {
        if (row == null) {
            return null;
        }
        SlotBundle slots = new SlotBundle(
                jsonService.fromJsonArray(row.getMealTime()),
                jsonService.fromJsonArray(row.getMood()),
                jsonService.fromJsonArray(row.getScene()),
                jsonService.fromJsonArray(row.getHealthGoal()),
                jsonService.fromJsonArray(row.getCuisine()),
                jsonService.fromJsonArray(row.getTaste()),
                jsonService.fromJsonArray(row.getConvenience())
        );
        return new MealItem(
                row.getId(),
                SourceMode.valueOf(row.getSourceType()),
                row.getOwnerUserId(),
                row.getName(),
                slots,
                0
        );
    }
}