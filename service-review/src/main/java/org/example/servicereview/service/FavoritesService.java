package org.example.servicereview.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.serviceapi.dto.question.QuestionBriefDto;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicereview.dto.AgentFavoriteCreateDto;
import org.example.servicereview.dto.AgentFavoriteMoveDto;
import org.example.servicereview.dto.AgentFavoriteUpdateDto;
import org.example.servicereview.dto.ReceiveDto;
import org.example.servicereview.entry.Favorites;
import org.example.servicereview.mapper.FavoritesMapper;
import org.example.servicereview.vo.FavoriteAddResultVo;
import org.example.servicereview.vo.FavoriteFolderBriefVo;
import org.example.servicereview.vo.FavoriteFolderVo;
import org.example.servicereview.vo.FavoriteLocationVo;
import org.example.servicereview.vo.FavoriteMoveResultVo;
import org.example.servicereview.vo.FavoriteQuestionBriefVo;
import org.example.servicereview.vo.FavoriteRemoveResultVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class FavoritesService {
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    public FavoritesService(FavoritesMapper favoritesMapper,QuestionFeignClient questionFeignClient) {
        this.favoritesMapper = favoritesMapper;
        this.questionFeignClient = questionFeignClient;
    }

    private final FavoritesMapper favoritesMapper;

    private QuestionFeignClient questionFeignClient;


    public List<Favorites> getAllFavoritesByUserId() {
        if(UserContext.getUserId()==null){
            throw new IllegalArgumentException("请登录后操作");
        }
        return favoritesMapper.selectList(new QueryWrapper<Favorites>().eq("user_id",UserContext.getUserId()));
    }
    /**
     * 获取收藏下的所有问题
     * 同时懒删除不存在的问题
     * @param favoriteId
     * @return
     */
    public List<QuestionDto> getAllFavoritesByUserId(Long favoriteId) {
       Favorites favorites = favoritesMapper.selectById(favoriteId);
       if(favorites == null) {
        throw new IllegalArgumentException("未找到该收藏");
       }
       if(!favorites.getUserId().equals(UserContext.getUserId())) {
        throw new IllegalArgumentException("该收藏不是您的收藏");
       }
       List<Long> questionIds = favorites.getQuestionIds();
       if(questionIds.isEmpty()) {
        return Collections.emptyList();
       }
       Result<List<QuestionDto>> questionDtoBodys= questionFeignClient.getFavorites(questionIds);
       if(!questionDtoBodys.getCode().equals(200)) {
           throw new RuntimeException(questionDtoBodys.getMessage());
       }
       // 懒删除不存在的问题
       List<QuestionDto> questionDtos = questionDtoBodys.getData();
       if(questionDtos.isEmpty()) {
         return questionDtos;   
       }
       List<QuestionDto>remove=new ArrayList<>();
       for(QuestionDto questionDto:questionDtos) {
            if(!Integer.valueOf(1).equals(questionDto.getStatus())&&!Objects.equals(questionDto.getCreateUserId(), UserContext.getUserId())) {
                remove.add(questionDto);
            }
        }

      
       if(!remove.isEmpty()){
            questionDtos.removeAll(remove);
            questionIds.removeAll(remove.stream().map(QuestionDto::getQuestionId).toList());
            favorites.setQuestionIds(questionIds);
            favoritesMapper.updateById(favorites);
       }

       return questionDtos;
    }
    /**
     * 创建收藏
     * 首先前端获取一个请求ID，请求ID用于判断是否重复创建
     * @param favoritesName
     * @param requestId
     */
    public void createFavorites(String favoritesName,String requestId){
        if(Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(RedisContext.REQUEST_ID_KEY+requestId,requestId,3, TimeUnit.MINUTES))) {
            throw new IllegalArgumentException("该收藏已创建");
        }
        Favorites favorites = Favorites.builder()
                .favoritesName(favoritesName)
                .questionIds(Collections.emptyList())
                .userId(UserContext.getUserId())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        favoritesMapper.insert(favorites);
    }
    /**
     * 获取请求ID
     * @return
     */
    public String getRequestId() {
        String requestId = UUID.randomUUID().toString();
      

        return requestId;
    }
    /**
     * 插入问题ID到收藏
     * @param questionId
     * @param favoriteId
     * @return
     */
    public String insertQuestionId(Long questionId,Long favoriteId) {
        Favorites favorites = favoritesMapper.selectById(favoriteId);
        if(favorites == null) {
            throw new IllegalArgumentException("未找到该收藏");
        }
        if(!favorites.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("该收藏不是您的收藏");
        }
        if(favorites.getQuestionIds().contains(questionId)) {
            throw new IllegalArgumentException("该收藏已存在该问题");
        }
        Result<QuestionDto> questionDtoBody = questionFeignClient.getQuestionInfo(questionId);
        if(!questionDtoBody.getCode().equals(200)) {
            throw new RuntimeException(questionDtoBody.getMessage());
        }
        QuestionDto questionDto = questionDtoBody.getData();
        List<Long>questionIds=favorites.getQuestionIds();
        questionIds.add(questionId);
        favorites.setQuestionIds(questionIds);
        favoritesMapper.updateById(favorites);
        return "success";
    }
     /**
      * 删除问题ID从收藏
      * @param questionId
      * @param favoriteId
      * @return
      */
     public String deleteQuestionId(Long questionId,Long favoriteId) {
        Favorites favorites = favoritesMapper.selectById(favoriteId);
        if(favorites == null) {
            throw new IllegalArgumentException("未找到该收藏");
        }
        if(!favorites.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("该收藏不是您的收藏");
        }
        if(!favorites.getQuestionIds().contains(questionId)) {
            throw new IllegalArgumentException("该收藏不存在该问题");
        }
        List<Long>questionIds=favorites.getQuestionIds();
        questionIds.remove(questionId);
        favorites.setQuestionIds(questionIds);
        favoritesMapper.updateById(favorites);
        return "success";
     }
    /**
     * 删除收藏
     * @param favoriteId
     * @return
     */
    public String deleteFavorites(Long favoriteId) {
        Favorites favorites = favoritesMapper.selectById(favoriteId);
        if(favorites == null) {
            throw new IllegalArgumentException("未找到该收藏");
        }
        if(!favorites.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("该收藏不是您的收藏");
        }
        favoritesMapper.deleteById(favoriteId);
        return "success";
    }
    /**
     * 更新收藏
     * 更新收藏的字段可以为空，为空则不更新
     * @param receiveDto
     * @return
     */
    public String updateFavorites(ReceiveDto receiveDto) {
        int count=0;
        StringBuilder sb=new StringBuilder();
        Favorites favorites=favoritesMapper.selectById(receiveDto.getFavoritesId());
        if(favorites == null) {
            throw new IllegalArgumentException("未找到该收藏");
        }
        if(!favorites.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("该收藏不是您的收藏");
        }
        if(receiveDto.getFavoritesName() != null) {
            count++;
            favorites.setFavoritesName(receiveDto.getFavoritesName());
        }
        if(receiveDto.getFavoritesContent() != null) {
            count++;
            favorites.setFavoritesContent(receiveDto.getFavoritesContent());
        }
        if(receiveDto.getFavoritesType() != null) {
            count++;
            favorites.setFavoritesType(receiveDto.getFavoritesType());
        }
        if(receiveDto.getQuestionIds() != null) {
            count++;
            favorites.setQuestionIds(receiveDto.getQuestionIds());
        }   
        if(count>0) {
            sb.append("更新成功");
            favoritesMapper.updateById(favorites);
        } else {
            sb.append("未更新任何字段");
        }
        return sb.toString();
    }

    // ==================== agent 专用批量操作（AgentFavoritesController 调用） ====================

    /** agent 批量接口单次最多操作的题目数量上限。 */
    private static final int MAX_BATCH_SIZE = 50;

    /**
     * 收藏夹瘦身列表（agent）：不含 userId 与 questionIds 明细，只含题目数量。
     *
     * @return 当前用户全部收藏夹的瘦身 VO
     */
    public List<FavoriteFolderVo> listFavoriteFolderVos() {
        if(UserContext.getUserId()==null){
            throw new IllegalArgumentException("请登录后操作");
        }
        return favoritesMapper.selectList(new QueryWrapper<Favorites>().eq("user_id",UserContext.getUserId()))
                .stream().map(this::toFolderVo).toList();
    }

    /**
     * 收藏夹内题目瘦身列表（agent）：仅 ID/标题/难度/标签/通过统计。
     *
     * <p>保留与网页端一致的懒删除语义：他人私密/下架/审核中的题目从收藏夹剔除并回写。
     * 相比网页端全量接口，本方法经 service-question 批量瘦身接口取数并投影，
     * 不向网关回传题干等大字段。</p>
     *
     * @param favoriteId 收藏夹 ID
     * @return 瘦身题目列表（不可见题剔除后）
     */
    public List<FavoriteQuestionBriefVo> getFavoriteQuestionBriefs(Long favoriteId) {
        Favorites favorites = requireOwnedFavorite(favoriteId);
        List<Long> questionIds = favorites.getQuestionIds();
        if(questionIds.isEmpty()) {
            return Collections.emptyList();
        }
        Result<List<QuestionBriefDto>> briefResult = questionFeignClient.getFavoritesBrief(questionIds);
        if(!briefResult.getCode().equals(200)) {
            throw new RuntimeException(briefResult.getMessage());
        }
        List<QuestionBriefDto> briefs = briefResult.getData();
        if(briefs.isEmpty()) {
            return Collections.emptyList();
        }
        // 懒删除：他人非公开题（status != 1 且非本人创建）从列表剔除并回写 DB
        List<QuestionBriefDto> invisible = briefs.stream()
                .filter(b -> !Integer.valueOf(1).equals(b.getStatus())
                        && !Objects.equals(b.getCreateUserId(), UserContext.getUserId()))
                .toList();
        if(!invisible.isEmpty()){
            List<Long> deadIds = invisible.stream().map(QuestionBriefDto::getQuestionId).toList();
            briefs.removeAll(invisible);
            questionIds.removeAll(deadIds);
            favorites.setQuestionIds(questionIds);
            favoritesMapper.updateById(favorites);
        }
        return briefs.stream().map(this::toBriefVo).toList();
    }

    /**
     * agent 创建收藏夹：一次请求完成幂等校验与名称/类型/简介落库。
     *
     * <p>requestId 由 agent 客户端生成，复用网页端的 Redis 幂等键（3 分钟窗口）。
     * 插入后自增主键回填，直接返回含 favoritesId 的瘦身 VO，
     * 调用方无需再查一次列表定位新收藏夹。</p>
     *
     * @param dto 创建请求（名称必填；类型/简介可选）
     * @return 新建收藏夹的瘦身 VO
     */
    public FavoriteFolderVo createFavoritesAgent(AgentFavoriteCreateDto dto) {
        if(UserContext.getUserId()==null){
            throw new IllegalArgumentException("请登录后操作");
        }
        if(dto == null || dto.getRequestId()==null || dto.getRequestId().isBlank()){
            throw new IllegalArgumentException("requestId 不能为空");
        }
        String name = normalizeText(dto.getFavoritesName(), "收藏夹名称", true);
        String type = normalizeText(dto.getFavoritesType(), "收藏夹类型", false);
        String content = normalizeText(dto.getFavoritesContent(), "收藏夹简介", false);
        if(Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(RedisContext.REQUEST_ID_KEY+dto.getRequestId(),dto.getRequestId(),3, TimeUnit.MINUTES))) {
            throw new IllegalArgumentException("该收藏已创建");
        }
        Favorites favorites = Favorites.builder()
                .favoritesName(name)
                .favoritesType(type)
                .favoritesContent(content)
                .questionIds(Collections.emptyList())
                .userId(UserContext.getUserId())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        favoritesMapper.insert(favorites);
        return toFolderVo(favorites);
    }

    /**
     * agent 更新收藏夹元信息：只允许改名称/类型/简介。
     *
     * <p>刻意不接收 questionIds——收藏夹题目列表只能通过批量增删/移动接口变更，
     * 防止模型用整体覆盖方式写坏数据（网页端 PUT 的读-改-写覆盖风险不暴露给 agent）。</p>
     *
     * @param dto 更新请求（favoritesId 必填，其余可选）
     * @return 更新后的瘦身 VO
     */
    public FavoriteFolderVo updateFavoritesMeta(AgentFavoriteUpdateDto dto) {
        if(dto == null || dto.getFavoritesId()==null){
            throw new IllegalArgumentException("favoritesId 不能为空");
        }
        Favorites favorites = requireOwnedFavorite(dto.getFavoritesId());
        String name = normalizeText(dto.getFavoritesName(), "收藏夹名称", false);
        String type = normalizeText(dto.getFavoritesType(), "收藏夹类型", false);
        String content = normalizeText(dto.getFavoritesContent(), "收藏夹简介", false);
        if(name == null && type == null && content == null) {
            throw new IllegalArgumentException("未提供任何需要更新的字段");
        }
        if(name != null) favorites.setFavoritesName(name);
        if(type != null) favorites.setFavoritesType(type);
        if(content != null) favorites.setFavoritesContent(content);
        favoritesMapper.updateById(favorites);
        return toFolderVo(favorites);
    }

    /**
     * 批量添加题目到收藏夹（agent）：一次请求完成去重、存在性与可见性校验、追加落库。
     *
     * <p>题目存在性与可见性通过一次批量瘦身 Feign 调用校验：只有 status==1 或本人创建的
     * 题目才会真正写入（不可见题写入后读取时也会被懒删除，这里直接前置拦截）。
     * 已存在/不可见/不存在的题目分别报告，不视为整体失败。</p>
     *
     * @param favoriteId 收藏夹 ID
     * @param inputIds   题目 ID 列表（自动去重，≤50）
     * @return 添加结果明细
     */
    public FavoriteAddResultVo batchAddQuestions(Long favoriteId, List<Long> inputIds) {
        Favorites favorites = requireOwnedFavorite(favoriteId);
        List<Long> distinct = distinctInput(inputIds);
        Set<Long> existing = new HashSet<>(favorites.getQuestionIds());
        List<Long> duplicates = distinct.stream().filter(existing::contains).toList();
        List<Long> candidates = distinct.stream().filter(id -> !existing.contains(id)).toList();

        List<Long> invalid = new ArrayList<>();
        List<Long> invisible = new ArrayList<>();
        if(!candidates.isEmpty()) {
            Result<List<QuestionBriefDto>> briefResult = questionFeignClient.getFavoritesBrief(candidates);
            if(!briefResult.getCode().equals(200)) {
                throw new RuntimeException(briefResult.getMessage());
            }
            Map<Long, QuestionBriefDto> briefMap = new HashMap<>();
            for(QuestionBriefDto brief : briefResult.getData()) {
                briefMap.put(brief.getQuestionId(), brief);
            }
            Long userId = UserContext.getUserId();
            for(Long id : candidates) {
                QuestionBriefDto brief = briefMap.get(id);
                if(brief == null) {
                    invalid.add(id);
                } else if(!Integer.valueOf(1).equals(brief.getStatus()) && !Objects.equals(brief.getCreateUserId(), userId)) {
                    invisible.add(id);
                }
            }
        }
        List<Long> addable = candidates.stream().filter(id -> !invalid.contains(id) && !invisible.contains(id)).toList();
        if(!addable.isEmpty()) {
            List<Long> merged = new ArrayList<>(favorites.getQuestionIds());
            merged.addAll(addable);
            favorites.setQuestionIds(merged);
            favoritesMapper.updateById(favorites);
        }
        return FavoriteAddResultVo.builder()
                .added(addable.size())
                .skippedDuplicateIds(duplicates)
                .skippedInvisibleIds(invisible)
                .invalidQuestionIds(invalid)
                .build();
    }

    /**
     * 从收藏夹批量移除题目（agent）：移除实际存在的题目，其余报告 notInFolderIds。
     *
     * @param favoriteId 收藏夹 ID
     * @param inputIds   题目 ID 列表（自动去重，≤50）
     * @return 移除结果明细
     */
    public FavoriteRemoveResultVo batchRemoveQuestions(Long favoriteId, List<Long> inputIds) {
        Favorites favorites = requireOwnedFavorite(favoriteId);
        List<Long> distinct = distinctInput(inputIds);
        List<Long> current = favorites.getQuestionIds();
        List<Long> removed = distinct.stream().filter(current::contains).toList();
        List<Long> notInFolder = distinct.stream().filter(id -> !current.contains(id)).toList();
        if(!removed.isEmpty()) {
            current.removeAll(removed);
            favorites.setQuestionIds(current);
            favoritesMapper.updateById(favorites);
        }
        return FavoriteRemoveResultVo.builder()
                .removed(removed.size())
                .notInFolderIds(notInFolder)
                .build();
    }

    /**
     * 跨收藏夹批量移动题目（agent）。
     *
     * <p>并发安全：事务内先按主键升序对源/目标两行 FOR UPDATE 加行锁（见
     * {@link FavoritesMapper#lockByIdsForUpdate}），锁内再读取实体并读改写，
     * 避免与网页端/其他会话的写操作互相覆盖。只移动「在源中且不在目标中」的题目；
     * 已同时在两侧的题目保留在源夹中（移动会造成重复删除语义不清），原样报告。</p>
     *
     * @param fromFavoriteId 源收藏夹 ID
     * @param toFavoriteId   目标收藏夹 ID（不能与源相同）
     * @param inputIds       题目 ID 列表（自动去重，≤50）
     * @return 移动结果明细
     */
    @Transactional
    public FavoriteMoveResultVo moveQuestions(Long fromFavoriteId, Long toFavoriteId, List<Long> inputIds) {
        if(fromFavoriteId == null || toFavoriteId == null) {
            throw new IllegalArgumentException("源收藏夹与目标收藏夹不能为空");
        }
        if(fromFavoriteId.equals(toFavoriteId)) {
            throw new IllegalArgumentException("源收藏夹与目标收藏夹相同");
        }
        // 行锁：固定主键升序加锁避免交叉死锁；随后在同一事务内读取实体（行已被当前事务锁定）
        favoritesMapper.lockByIdsForUpdate(fromFavoriteId, toFavoriteId);
        Favorites from = favoritesMapper.selectById(fromFavoriteId);
        Favorites to = favoritesMapper.selectById(toFavoriteId);
        if(from == null || to == null) {
            throw new IllegalArgumentException("未找到该收藏");
        }
        Long userId = UserContext.getUserId();
        if(!from.getUserId().equals(userId) || !to.getUserId().equals(userId)) {
            throw new IllegalArgumentException("该收藏不是您的收藏");
        }
        List<Long> distinct = distinctInput(inputIds);
        List<Long> source = from.getQuestionIds();
        List<Long> target = to.getQuestionIds();
        List<Long> notInSource = distinct.stream().filter(id -> !source.contains(id)).toList();
        List<Long> alreadyInTarget = distinct.stream().filter(id -> source.contains(id) && target.contains(id)).toList();
        List<Long> movable = distinct.stream().filter(id -> source.contains(id) && !target.contains(id)).toList();
        if(!movable.isEmpty()) {
            source.removeAll(movable);
            target.addAll(movable);
            favoritesMapper.updateById(from);
            favoritesMapper.updateById(to);
        }
        return FavoriteMoveResultVo.builder()
                .moved(movable.size())
                .notInSourceIds(notInSource)
                .alreadyInTargetIds(alreadyInTarget)
                .build();
    }

    /**
     * 定位题目所在收藏夹（agent）：扫描当前用户全部收藏夹的 questionIds。
     *
     * @param inputIds 题目 ID 列表（自动去重，≤50）
     * @return 每个题目对应的包含它的收藏夹列表（可能为空列表）
     */
    public List<FavoriteLocationVo> locateQuestions(List<Long> inputIds) {
        if(UserContext.getUserId()==null){
            throw new IllegalArgumentException("请登录后操作");
        }
        List<Long> distinct = distinctInput(inputIds);
        List<Favorites> folders = favoritesMapper.selectList(new QueryWrapper<Favorites>().eq("user_id",UserContext.getUserId()));
        Map<Long, List<FavoriteFolderBriefVo>> byQuestion = new HashMap<>();
        for(Favorites folder : folders) {
            if(folder.getQuestionIds()==null) {
                continue;
            }
            for(Long questionId : folder.getQuestionIds()) {
                if(distinct.contains(questionId)) {
                    byQuestion.computeIfAbsent(questionId, k -> new ArrayList<>())
                            .add(new FavoriteFolderBriefVo(folder.getFavoritesId(), folder.getFavoritesName()));
                }
            }
        }
        return distinct.stream().map(id -> FavoriteLocationVo.builder()
                .questionId(id)
                .folders(byQuestion.getOrDefault(id, Collections.emptyList()))
                .build()).toList();
    }

    /** 校验收藏归属（agent 专用方法共用）；网页端旧方法保持原实现不动。 */
    private Favorites requireOwnedFavorite(Long favoriteId) {
        if(UserContext.getUserId()==null){
            throw new IllegalArgumentException("请登录后操作");
        }
        Favorites favorites = favoritesMapper.selectById(favoriteId);
        if(favorites == null) {
            throw new IllegalArgumentException("未找到该收藏");
        }
        if(!favorites.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("该收藏不是您的收藏");
        }
        return favorites;
    }

    /** 批量入参卫生：去 null、去重、保序，并校验数量上限与取值范围。 */
    private List<Long> distinctInput(List<Long> inputIds) {
        List<Long> distinct = inputIds == null ? Collections.emptyList()
                : inputIds.stream().filter(Objects::nonNull).distinct().toList();
        if(distinct.isEmpty()) {
            throw new IllegalArgumentException("题目 ID 列表不能为空");
        }
        if(distinct.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("单次最多操作 " + MAX_BATCH_SIZE + " 道题目");
        }
        for(Long id : distinct) {
            if(id <= 0) {
                throw new IllegalArgumentException("题目 ID 必须为正整数");
            }
        }
        return distinct;
    }

    /** 文本字段规整：trim；必填校验；超长（DB varchar(255)）前置拦截避免 DB 报错被脱敏吞掉。 */
    private String normalizeText(String value, String label, boolean required) {
        if(value == null) {
            if(required) {
                throw new IllegalArgumentException(label + "不能为空");
            }
            return null;
        }
        String trimmed = value.trim();
        if(trimmed.isEmpty()) {
            if(required) {
                throw new IllegalArgumentException(label + "不能为空");
            }
            return null;
        }
        if(trimmed.length() > 255) {
            throw new IllegalArgumentException(label + "长度不能超过 255 字符");
        }
        return trimmed;
    }

    /** 实体 → 瘦身 VO 投影。 */
    private FavoriteFolderVo toFolderVo(Favorites favorites) {
        return FavoriteFolderVo.builder()
                .favoritesId(favorites.getFavoritesId())
                .favoritesName(favorites.getFavoritesName())
                .favoritesType(favorites.getFavoritesType())
                .favoritesContent(favorites.getFavoritesContent())
                .questionCount(favorites.getQuestionIds()==null?0:favorites.getQuestionIds().size())
                .createTime(favorites.getCreateTime())
                .updateTime(favorites.getUpdateTime())
                .build();
    }

    /** 瘦身 DTO → VO 投影（不外传 status/createUserId，可见性已在服务端过滤）。 */
    private FavoriteQuestionBriefVo toBriefVo(QuestionBriefDto brief) {
        return FavoriteQuestionBriefVo.builder()
                .questionId(brief.getQuestionId())
                .title(brief.getTitle())
                .difficulty(brief.getDifficulty())
                .tags(brief.getTags())
                .totalSubmit(brief.getTotalSubmit())
                .totalAc(brief.getTotalAc())
                .passRate(brief.getPassRate())
                .build();
    }

}
