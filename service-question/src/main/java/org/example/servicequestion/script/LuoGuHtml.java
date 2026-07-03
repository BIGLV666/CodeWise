package org.example.servicequestion.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.servicequestion.entry.Question;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;


import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LuoGuHtml {
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 洛谷标签ID到名称的映射（常见的标签）
    private static final Map<Integer, String> LUOGU_TAG_MAP = new HashMap<>();

    static {
        // 基础算法
        LUOGU_TAG_MAP.put(1, "暴力");
        LUOGU_TAG_MAP.put(2, "枚举");
        LUOGU_TAG_MAP.put(3, "模拟");
        LUOGU_TAG_MAP.put(4, "贪心");
        LUOGU_TAG_MAP.put(5, "递归");
        LUOGU_TAG_MAP.put(6, "递推");
        LUOGU_TAG_MAP.put(7, "排序");
        LUOGU_TAG_MAP.put(8, "搜索");
        LUOGU_TAG_MAP.put(9, "DFS");
        LUOGU_TAG_MAP.put(10, "BFS");

        // 数据结构
        LUOGU_TAG_MAP.put(11, "栈");
        LUOGU_TAG_MAP.put(12, "队列");
        LUOGU_TAG_MAP.put(13, "链表");
        LUOGU_TAG_MAP.put(14, "树");
        LUOGU_TAG_MAP.put(15, "图");
        LUOGU_TAG_MAP.put(16, "堆");
        LUOGU_TAG_MAP.put(17, "哈希表");
        LUOGU_TAG_MAP.put(18, "并查集");
        LUOGU_TAG_MAP.put(19, "线段树");
        LUOGU_TAG_MAP.put(20, "树状数组");

        // 动态规划
        LUOGU_TAG_MAP.put(21, "DP");
        LUOGU_TAG_MAP.put(22, "背包DP");
        LUOGU_TAG_MAP.put(23, "树形DP");
        LUOGU_TAG_MAP.put(24, "区间DP");
        LUOGU_TAG_MAP.put(25, "状压DP");
        LUOGU_TAG_MAP.put(26, "数位DP");
        LUOGU_TAG_MAP.put(27, "概率DP");
        LUOGU_TAG_MAP.put(28, "记忆化搜索");

        // 数学
        LUOGU_TAG_MAP.put(30, "数学");
        LUOGU_TAG_MAP.put(31, "数论");
        LUOGU_TAG_MAP.put(32, "组合数学");
        LUOGU_TAG_MAP.put(33, "概率论");
        LUOGU_TAG_MAP.put(34, "线性代数");
        LUOGU_TAG_MAP.put(35, "几何");
        LUOGU_TAG_MAP.put(36, "博弈论");

        // 字符串
        LUOGU_TAG_MAP.put(40, "字符串");
        LUOGU_TAG_MAP.put(41, "KMP");
        LUOGU_TAG_MAP.put(42, "Trie");
        LUOGU_TAG_MAP.put(43, "AC自动机");
        LUOGU_TAG_MAP.put(44, "后缀数组");
        LUOGU_TAG_MAP.put(45, "后缀自动机");
        LUOGU_TAG_MAP.put(46, "Manacher");

        // 其他
        LUOGU_TAG_MAP.put(50, "二分查找");
        LUOGU_TAG_MAP.put(51, "分治");
        LUOGU_TAG_MAP.put(52, "贪心");
        LUOGU_TAG_MAP.put(53, "前缀和");
        LUOGU_TAG_MAP.put(54, "差分");
        LUOGU_TAG_MAP.put(55, "离散化");
        LUOGU_TAG_MAP.put(56, "高精度");
        LUOGU_TAG_MAP.put(57, "位运算");
        LUOGU_TAG_MAP.put(58, "ST表");
        LUOGU_TAG_MAP.put(59, "单调栈");
        LUOGU_TAG_MAP.put(60, "单调队列");
        LUOGU_TAG_MAP.put(61, "尺取法");
        LUOGU_TAG_MAP.put(62, "折半搜索");
        LUOGU_TAG_MAP.put(63, "倍增");
        LUOGU_TAG_MAP.put(64, "构造");
        LUOGU_TAG_MAP.put(65, "打表");
        LUOGU_TAG_MAP.put(66, "随机化");
        LUOGU_TAG_MAP.put(67, "离线");
        LUOGU_TAG_MAP.put(68, "启发式合并");
        LUOGU_TAG_MAP.put(69, "分块");
        LUOGU_TAG_MAP.put(70, "莫队");
        LUOGU_TAG_MAP.put(71, "CDQ分治");
        LUOGU_TAG_MAP.put(72, "整体二分");
        LUOGU_TAG_MAP.put(73, "LCT");
        LUOGU_TAG_MAP.put(74, "平衡树");
        LUOGU_TAG_MAP.put(75, "Splay");
        LUOGU_TAG_MAP.put(76, "Treap");
        LUOGU_TAG_MAP.put(77, "SBT");
        LUOGU_TAG_MAP.put(78, "AVL");
        LUOGU_TAG_MAP.put(79, "红黑树");
        LUOGU_TAG_MAP.put(80, "B树");
        LUOGU_TAG_MAP.put(81, "B+树");
        LUOGU_TAG_MAP.put(82, "跳表");
        LUOGU_TAG_MAP.put(83, "递归");
        LUOGU_TAG_MAP.put(84, "暴力");
        LUOGU_TAG_MAP.put(85, "模拟");
        LUOGU_TAG_MAP.put(86, "贪心");
        LUOGU_TAG_MAP.put(87, "排序");
        LUOGU_TAG_MAP.put(88, "搜索");
        LUOGU_TAG_MAP.put(89, "DFS");
        LUOGU_TAG_MAP.put(90, "BFS");
        LUOGU_TAG_MAP.put(91, "DP");
        LUOGU_TAG_MAP.put(92, "背包DP");
        LUOGU_TAG_MAP.put(93, "树形DP");
        LUOGU_TAG_MAP.put(94, "区间DP");
        LUOGU_TAG_MAP.put(95, "状压DP");
        LUOGU_TAG_MAP.put(96, "数位DP");
        LUOGU_TAG_MAP.put(97, "概率DP");
        LUOGU_TAG_MAP.put(98, "记忆化搜索");
        LUOGU_TAG_MAP.put(99, "数学");
        LUOGU_TAG_MAP.put(100, "数论");
        LUOGU_TAG_MAP.put(101, "组合数学");
        LUOGU_TAG_MAP.put(102, "概率论");
        LUOGU_TAG_MAP.put(103, "线性代数");
        LUOGU_TAG_MAP.put(104, "几何");
        LUOGU_TAG_MAP.put(105, "博弈论");
        LUOGU_TAG_MAP.put(106, "字符串");
        LUOGU_TAG_MAP.put(107, "KMP");
        LUOGU_TAG_MAP.put(108, "Trie");
        LUOGU_TAG_MAP.put(109, "AC自动机");
        LUOGU_TAG_MAP.put(110, "后缀数组");
        LUOGU_TAG_MAP.put(111, "后缀自动机");
        LUOGU_TAG_MAP.put(112, "Manacher");
        LUOGU_TAG_MAP.put(113, "二分查找");
        LUOGU_TAG_MAP.put(114, "分治");
        LUOGU_TAG_MAP.put(115, "前缀和");
        LUOGU_TAG_MAP.put(116, "差分");
        LUOGU_TAG_MAP.put(117, "离散化");
        LUOGU_TAG_MAP.put(118, "高精度");
        LUOGU_TAG_MAP.put(119, "位运算");
        LUOGU_TAG_MAP.put(120, "ST表");
        LUOGU_TAG_MAP.put(121, "单调栈");
        LUOGU_TAG_MAP.put(122, "单调队列");
        LUOGU_TAG_MAP.put(123, "尺取法");
        LUOGU_TAG_MAP.put(124, "折半搜索");
        LUOGU_TAG_MAP.put(125, "倍增");
        LUOGU_TAG_MAP.put(126, "构造");
        LUOGU_TAG_MAP.put(127, "打表");
        LUOGU_TAG_MAP.put(128, "随机化");
        LUOGU_TAG_MAP.put(129, "离线");
        LUOGU_TAG_MAP.put(130, "启发式合并");
        LUOGU_TAG_MAP.put(131, "分块");
        LUOGU_TAG_MAP.put(132, "莫队");
        LUOGU_TAG_MAP.put(133, "CDQ分治");
        LUOGU_TAG_MAP.put(134, "整体二分");
        LUOGU_TAG_MAP.put(135, "LCT");
        LUOGU_TAG_MAP.put(136, "平衡树");
        LUOGU_TAG_MAP.put(137, "Splay");
        LUOGU_TAG_MAP.put(138, "Treap");
        LUOGU_TAG_MAP.put(139, "SBT");
        LUOGU_TAG_MAP.put(140, "AVL");
        LUOGU_TAG_MAP.put(141, "红黑树");
        LUOGU_TAG_MAP.put(142, "B树");
        LUOGU_TAG_MAP.put(143, "B+树");
        LUOGU_TAG_MAP.put(144, "跳表");
        LUOGU_TAG_MAP.put(145, "递归");
        LUOGU_TAG_MAP.put(146, "暴力");
        LUOGU_TAG_MAP.put(147, "模拟");
        LUOGU_TAG_MAP.put(148, "贪心");
        LUOGU_TAG_MAP.put(149, "排序");
        LUOGU_TAG_MAP.put(150, "搜索");
        LUOGU_TAG_MAP.put(151, "DFS");
        LUOGU_TAG_MAP.put(152, "BFS");
        LUOGU_TAG_MAP.put(153, "DP");
        LUOGU_TAG_MAP.put(154, "背包DP");
        LUOGU_TAG_MAP.put(155, "树形DP");
        LUOGU_TAG_MAP.put(156, "区间DP");
        LUOGU_TAG_MAP.put(157, "状压DP");
        LUOGU_TAG_MAP.put(158, "数位DP");
        LUOGU_TAG_MAP.put(159, "概率DP");
        LUOGU_TAG_MAP.put(160, "记忆化搜索");
        LUOGU_TAG_MAP.put(161, "数学");
        LUOGU_TAG_MAP.put(162, "数论");
        LUOGU_TAG_MAP.put(163, "组合数学");
        LUOGU_TAG_MAP.put(164, "概率论");
        LUOGU_TAG_MAP.put(165, "线性代数");
        LUOGU_TAG_MAP.put(166, "几何");
        LUOGU_TAG_MAP.put(167, "博弈论");
        LUOGU_TAG_MAP.put(168, "字符串");
        LUOGU_TAG_MAP.put(169, "KMP");
        LUOGU_TAG_MAP.put(170, "Trie");
        LUOGU_TAG_MAP.put(171, "AC自动机");
        LUOGU_TAG_MAP.put(172, "后缀数组");
        LUOGU_TAG_MAP.put(173, "后缀自动机");
        LUOGU_TAG_MAP.put(174, "Manacher");
        LUOGU_TAG_MAP.put(175, "二分查找");
        LUOGU_TAG_MAP.put(176, "分治");
        LUOGU_TAG_MAP.put(177, "前缀和");
        LUOGU_TAG_MAP.put(178, "差分");
        LUOGU_TAG_MAP.put(179, "离散化");
        LUOGU_TAG_MAP.put(180, "高精度");
        LUOGU_TAG_MAP.put(181, "位运算");
        LUOGU_TAG_MAP.put(182, "ST表");
        LUOGU_TAG_MAP.put(183, "单调栈");
        LUOGU_TAG_MAP.put(184, "单调队列");
        LUOGU_TAG_MAP.put(185, "尺取法");
        LUOGU_TAG_MAP.put(186, "折半搜索");
        LUOGU_TAG_MAP.put(187, "倍增");
        LUOGU_TAG_MAP.put(188, "构造");
        LUOGU_TAG_MAP.put(189, "打表");
        LUOGU_TAG_MAP.put(190, "随机化");
        LUOGU_TAG_MAP.put(191, "离线");
        LUOGU_TAG_MAP.put(192, "启发式合并");
        LUOGU_TAG_MAP.put(193, "分块");
        LUOGU_TAG_MAP.put(194, "莫队");
        LUOGU_TAG_MAP.put(195, "CDQ分治");
        LUOGU_TAG_MAP.put(196, "整体二分");
        LUOGU_TAG_MAP.put(197, "LCT");
        LUOGU_TAG_MAP.put(198, "平衡树");
        LUOGU_TAG_MAP.put(199, "Splay");
        LUOGU_TAG_MAP.put(200, "Treap");
        LUOGU_TAG_MAP.put(201, "SBT");
        LUOGU_TAG_MAP.put(202, "AVL");
        LUOGU_TAG_MAP.put(203, "红黑树");
        LUOGU_TAG_MAP.put(204, "B树");
        LUOGU_TAG_MAP.put(205, "B+树");
        LUOGU_TAG_MAP.put(206, "跳表");
        LUOGU_TAG_MAP.put(207, "递归");
        LUOGU_TAG_MAP.put(208, "暴力");
        LUOGU_TAG_MAP.put(209, "模拟");
        LUOGU_TAG_MAP.put(210, "贪心");
        LUOGU_TAG_MAP.put(211, "排序");
        LUOGU_TAG_MAP.put(212, "搜索");
        LUOGU_TAG_MAP.put(213, "DFS");
        LUOGU_TAG_MAP.put(214, "BFS");
        LUOGU_TAG_MAP.put(215, "DP");
        LUOGU_TAG_MAP.put(216, "背包DP");
        LUOGU_TAG_MAP.put(217, "树形DP");
        LUOGU_TAG_MAP.put(218, "区间DP");
        LUOGU_TAG_MAP.put(219, "状压DP");
        LUOGU_TAG_MAP.put(220, "数位DP");
        LUOGU_TAG_MAP.put(221, "概率DP");
        LUOGU_TAG_MAP.put(222, "记忆化搜索");
        LUOGU_TAG_MAP.put(223, "数学");
        LUOGU_TAG_MAP.put(224, "数论");
        LUOGU_TAG_MAP.put(225, "组合数学");
        LUOGU_TAG_MAP.put(226, "概率论");
        LUOGU_TAG_MAP.put(227, "线性代数");
        LUOGU_TAG_MAP.put(228, "几何");
        LUOGU_TAG_MAP.put(229, "博弈论");
        LUOGU_TAG_MAP.put(230, "字符串");
        LUOGU_TAG_MAP.put(231, "KMP");
        LUOGU_TAG_MAP.put(232, "Trie");
        LUOGU_TAG_MAP.put(233, "AC自动机");
        LUOGU_TAG_MAP.put(234, "后缀数组");
        LUOGU_TAG_MAP.put(235, "后缀自动机");
        LUOGU_TAG_MAP.put(236, "Manacher");
        LUOGU_TAG_MAP.put(237, "二分查找");
        LUOGU_TAG_MAP.put(238, "分治");
        LUOGU_TAG_MAP.put(239, "前缀和");
        LUOGU_TAG_MAP.put(240, "差分");
        LUOGU_TAG_MAP.put(241, "离散化");
        LUOGU_TAG_MAP.put(242, "高精度");
        LUOGU_TAG_MAP.put(243, "位运算");
        LUOGU_TAG_MAP.put(244, "ST表");
        LUOGU_TAG_MAP.put(245, "单调栈");
        LUOGU_TAG_MAP.put(246, "单调队列");
        LUOGU_TAG_MAP.put(247, "尺取法");
        LUOGU_TAG_MAP.put(248, "折半搜索");
        LUOGU_TAG_MAP.put(249, "倍增");
        LUOGU_TAG_MAP.put(250, "构造");
        LUOGU_TAG_MAP.put(251, "打表");
        LUOGU_TAG_MAP.put(252, "随机化");
        LUOGU_TAG_MAP.put(253, "离线");
        LUOGU_TAG_MAP.put(254, "启发式合并");
        LUOGU_TAG_MAP.put(255, "分块");
        LUOGU_TAG_MAP.put(256, "莫队");
        LUOGU_TAG_MAP.put(257, "CDQ分治");
        LUOGU_TAG_MAP.put(258, "整体二分");
        LUOGU_TAG_MAP.put(259, "LCT");
        LUOGU_TAG_MAP.put(260, "递归");
    }

    /**
     * 解析洛谷HTML并返回Question对象
     */
    public Question parseLuoguHtml(String htmlContent) throws Exception {
        Question question = new Question();

        // 1. 尝试从 _feInjection 中提取JSON数据
        try {
            parseFeInjection(htmlContent, question);
        } catch (Exception e) {
            // 如果JSON解析失败，使用传统HTML解析方式
            parseTraditionalHtml(htmlContent, question);
        }

        // 2. 处理LaTeX公式
        processLatexFormulas(question);

        // 3. 转换标签ID为标签名称
        convertTagsToNames(question);

        // 4. 设置默认值
        setDefaults(question);

        return question;
    }

    /**
     * 从 _feInjection 中解析JSON数据
     */
    private void parseFeInjection(String html, Question question) throws Exception {
        Pattern pattern = Pattern.compile("window\\._feInjection\\s*=\\s*JSON\\.parse\\(decodeURIComponent\\(\"(.*?)\"\\)\\)");
        Matcher matcher = pattern.matcher(html);

        if (!matcher.find()) {
            throw new RuntimeException("未找到 _feInjection 数据");
        }

        String encodedJson = matcher.group(1);
        String decodedJson = URLDecoder.decode(encodedJson, StandardCharsets.UTF_8.name());

        JsonNode rootNode = objectMapper.readTree(decodedJson);
        JsonNode problemNode = rootNode.path("currentData").path("problem");

        // 提取题目信息
        question.setTitle(problemNode.path("title").asText());
        question.setDescription(problemNode.path("description").asText());
        question.setInputDesc(problemNode.path("inputFormat").asText());
        question.setOutputDesc(problemNode.path("outputFormat").asText());
        question.setHint(problemNode.path("hint").asText());

        // 提取样例
        JsonNode samplesNode = problemNode.path("samples");
        if (samplesNode.isArray() && samplesNode.size() > 0) {
            JsonNode firstSample = samplesNode.get(0);
            if (firstSample.isArray() && firstSample.size() >= 2) {
                question.setSampleInput(firstSample.get(0).asText());
                question.setSampleOutput(firstSample.get(1).asText());
            }
        }

        // 提取限制
        JsonNode limitsNode = problemNode.path("limits");
        if (limitsNode.has("time")) {
            JsonNode timeNode = limitsNode.path("time");
            if (timeNode.isArray() && timeNode.size() > 0) {
                question.setTimeLimit(timeNode.get(0).asInt());
            }
        }
        if (limitsNode.has("memory")) {
            JsonNode memoryNode = limitsNode.path("memory");
            if (memoryNode.isArray() && memoryNode.size() > 0) {
                int memoryKB = memoryNode.get(0).asInt();
                question.setMemoryLimit(memoryKB / 1024);
            }
        }

        // 提取难度
        int difficulty = problemNode.path("difficulty").asInt();
        question.setDifficulty(difficulty);

        // 提取标签
        JsonNode tagsNode = problemNode.path("tags");
        if (tagsNode.isArray()) {
            StringBuilder tags = new StringBuilder();
            for (JsonNode tag : tagsNode) {
                if (tags.length() > 0) {
                    tags.append(",");
                }
                tags.append(tag.asText());
            }
            question.setTags(tags.toString());
        }

        // 提取来源
        JsonNode providerNode = problemNode.path("provider");
        if (providerNode.has("name")) {
            question.setSource(providerNode.path("name").asText());
        }
    }

    /**
     * 处理LaTeX公式，转换为可读文本
     */
    private void processLatexFormulas(Question question) {
        if (question.getDescription() != null) {
            question.setDescription(cleanLatex(question.getDescription()));
        }
        if (question.getInputDesc() != null) {
            question.setInputDesc(cleanLatex(question.getInputDesc()));
        }
        if (question.getOutputDesc() != null) {
            question.setOutputDesc(cleanLatex(question.getOutputDesc()));
        }
        if (question.getHint() != null) {
            question.setHint(cleanLatex(question.getHint()));
        }
    }

    /**
     * 清理LaTeX公式标记
     */
    private String cleanLatex(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // 移除 $ 符号（LaTeX公式标记）
        // $a$ -> a
        text = text.replaceAll("\\$([^$]+)\\$", "$1");

        // 移除 \frac{a}{b} -> a/b
        text = text.replaceAll("\\\\frac\\{([^}]+)\\}\\{([^}]+)\\}", "$1/$2");

        // 移除 \leq -> ≤
        text = text.replaceAll("\\\\leq", "≤");

        // 移除 \geq -> ≥
        text = text.replaceAll("\\\\geq", "≥");

        // 移除 \neq -> ≠
        text = text.replaceAll("\\\\neq", "≠");

        // 移除 \times -> ×
        text = text.replaceAll("\\\\times", "×");

        // 移除 \div -> ÷
        text = text.replaceAll("\\\\div", "÷");

        // 移除 \pm -> ±
        text = text.replaceAll("\\\\pm", "±");

        // 移除 \cdot -> ·
        text = text.replaceAll("\\\\cdot", "·");

        // 移除 \ldots -> ...
        text = text.replaceAll("\\\\ldots", "...");

        // 移除 \cdots -> ···
        text = text.replaceAll("\\\\cdots", "···");

        // 移除 \vdots -> ⋮
        text = text.replaceAll("\\\\vdots", "⋮");

        // 移除 \ddots -> ⋱
        text = text.replaceAll("\\\\ddots", "⋱");

        // 移除 \sqrt{x} -> √x
        text = text.replaceAll("\\\\sqrt\\{([^}]+)\\}", "√$1");

        // 移除 \sqrt{x} -> √x
        text = text.replaceAll("\\\\sqrt\\s+(\\S+)", "√$1");

        // 移除 \sum -> Σ
        text = text.replaceAll("\\\\sum", "Σ");

        // 移除 \prod -> Π
        text = text.replaceAll("\\\\prod", "Π");

        // 移除 \int -> ∫
        text = text.replaceAll("\\\\int", "∫");

        // 移除 \infty -> ∞
        text = text.replaceAll("\\\\infty", "∞");

        // 移除 \partial -> ∂
        text = text.replaceAll("\\\\partial", "∂");

        // 移除 \nabla -> ∇
        text = text.replaceAll("\\\\nabla", "∇");

        // 移除 \forall -> ∀
        text = text.replaceAll("\\\\forall", "∀");

        // 移除 \exists -> ∃
        text = text.replaceAll("\\\\exists", "∃");

        // 移除 \in -> ∈
        text = text.replaceAll("\\\\in", "∈");

        // 移除 \notin -> ∉
        text = text.replaceAll("\\\\notin", "∉");

        // 移除 \subset -> ⊂
        text = text.replaceAll("\\\\subset", "⊂");

        // 移除 \supset -> ⊃
        text = text.replaceAll("\\\\supset", "⊃");

        // 移除 \subseteq -> ⊆
        text = text.replaceAll("\\\\subseteq", "⊆");

        // 移除 \supseteq -> ⊇
        text = text.replaceAll("\\\\supseteq", "⊇");

        // 移除 \cup -> ∪
        text = text.replaceAll("\\\\cup", "∪");

        // 移除 \cap -> ∩
        text = text.replaceAll("\\\\cap", "∩");

        // 移除 \emptyset -> ∅
        text = text.replaceAll("\\\\emptyset", "∅");

        // 移除 \alpha -> α
        text = text.replaceAll("\\\\alpha", "α");

        // 移除 \beta -> β
        text = text.replaceAll("\\\\beta", "β");

        // 移除 \gamma -> γ
        text = text.replaceAll("\\\\gamma", "γ");

        // 移除 \delta -> δ
        text = text.replaceAll("\\\\delta", "δ");

        // 移除 \epsilon -> ε
        text = text.replaceAll("\\\\epsilon", "ε");

        // 移除 \theta -> θ
        text = text.replaceAll("\\\\theta", "θ");

        // 移除 \lambda -> λ
        text = text.replaceAll("\\\\lambda", "λ");

        // 移除 \mu -> μ
        text = text.replaceAll("\\\\mu", "μ");

        // 移除 \pi -> π
        text = text.replaceAll("\\\\pi", "π");

        // 移除 \rho -> ρ
        text = text.replaceAll("\\\\rho", "ρ");

        // 移除 \sigma -> σ
        text = text.replaceAll("\\\\sigma", "σ");

        // 移除 \tau -> τ
        text = text.replaceAll("\\\\tau", "τ");

        // 移除 \phi -> φ
        text = text.replaceAll("\\\\phi", "φ");

        // 移除 \omega -> ω
        text = text.replaceAll("\\\\omega", "ω");

        // 移除 \Delta -> Δ
        text = text.replaceAll("\\\\Delta", "Δ");

        // 移除 \Sigma -> Σ
        text = text.replaceAll("\\\\Sigma", "Σ");

        // 移除 \Omega -> Ω
        text = text.replaceAll("\\\\Omega", "Ω");

        // 移除 \left( -> (
        text = text.replaceAll("\\\\left\\(", "(");

        // 移除 \right) -> )
        text = text.replaceAll("\\\\right\\)", ")");

        // 移除 \left[ -> [
        text = text.replaceAll("\\\\left\\[", "[");

        // 移除 \right] -> ]
        text = text.replaceAll("\\\\right\\]", "]");

        // 移除 \left\{ -> {
        text = text.replaceAll("\\\\left\\{", "{");

        // 移除 \right\} -> }
        text = text.replaceAll("\\\\right\\}", "}");

        // 移除 \text{...} -> ...
        text = text.replaceAll("\\\\text\\{([^}]+)\\}", "$1");

        // 移除 \mathrm{...} -> ...
        text = text.replaceAll("\\\\mathrm\\{([^}]+)\\}", "$1");

        // 移除 \mathbf{...} -> ...
        text = text.replaceAll("\\\\mathbf\\{([^}]+)\\}", "$1");

        // 移除 \mathbb{...} -> ...
        text = text.replaceAll("\\\\mathbb\\{([^}]+)\\}", "$1");

        // 移除 \mathcal{...} -> ...
        text = text.replaceAll("\\\\mathcal\\{([^}]+)\\}", "$1");

        // 移除 \begin{...}...\end{...}
        text = text.replaceAll("\\\\begin\\{[^}]+\\}", "");
        text = text.replaceAll("\\\\end\\{[^}]+\\}", "");

        // 移除其他LaTeX命令
        text = text.replaceAll("\\\\[a-zA-Z]+", "");

        // 清理多余的空格和换行
        text = text.replaceAll("  +", " ");
        text = text.replaceAll("\\n\\s*\\n", "\n\n");

        return text.trim();
    }

    /**
     * 转换标签ID为标签名称
     */
    private void convertTagsToNames(Question question) {
        if (question.getTags() == null || question.getTags().isEmpty()) {
            return;
        }

        String[] tagIds = question.getTags().split(",");
        StringBuilder tagNames = new StringBuilder();

        for (String tagIdStr : tagIds) {
            try {
                int tagId = Integer.parseInt(tagIdStr.trim());
                String tagName = LUOGU_TAG_MAP.get(tagId);

                if (tagName != null) {
                    if (tagNames.length() > 0) {
                        tagNames.append(",");
                    }
                    tagNames.append(tagName);
                } else {
                    // 如果找不到映射，保留原始ID
                    if (tagNames.length() > 0) {
                        tagNames.append(",");
                    }
                    tagNames.append("标签").append(tagId);
                }
            } catch (NumberFormatException e) {
                // 忽略非数字标签
            }
        }

        question.setTags(tagNames.toString());
    }

    /**
     * 传统HTML解析方式（备用方案）
     */
    private void parseTraditionalHtml(String html, Question question) {
        Document doc = Jsoup.parse(html);

        // 解析标题
        Element titleElement = doc.selectFirst("h1");
        if (titleElement != null) {
            question.setTitle(titleElement.text());
        }

        // 解析题目描述
        Element descElement = findElementByText(doc, "题目描述");
        if (descElement != null) {
            Element nextDiv = descElement.nextElementSibling();
            if (nextDiv != null) {
                question.setDescription(nextDiv.text());
            }
        }

        // 解析输入格式
        Element inputElement = findElementByText(doc, "输入格式");
        if (inputElement != null) {
            Element nextDiv = inputElement.nextElementSibling();
            if (nextDiv != null) {
                question.setInputDesc(nextDiv.text());
            }
        }

        // 解析输出格式
        Element outputElement = findElementByText(doc, "输出格式");
        if (outputElement != null) {
            Element nextDiv = outputElement.nextElementSibling();
            if (nextDiv != null) {
                question.setOutputDesc(nextDiv.text());
            }
        }

        // 解析样例
        Elements preElements = doc.select("pre");
        if (preElements.size() >= 2) {
            question.setSampleInput(preElements.get(0).text());
            question.setSampleOutput(preElements.get(1).text());
        }

        // 解析提示
        Element hintElement = findElementByText(doc, "说明/提示");
        if (hintElement != null) {
            Element nextDiv = hintElement.nextElementSibling();
            if (nextDiv != null) {
                question.setHint(nextDiv.text());
            }
        }
    }

    /**
     * 通过文本内容查找元素
     */
    private Element findElementByText(Document doc, String text) {
        Elements elements = doc.select("h2, h3");
        for (Element element : elements) {
            if (element.text().contains(text)) {
                return element;
            }
        }
        return null;
    }

    /**
     * 设置默认值
     */
    private void setDefaults(Question question) {
        if (question.getTimeLimit() == null || question.getTimeLimit() == 0) {
            question.setTimeLimit(1000);
        }

        if (question.getMemoryLimit() == null || question.getMemoryLimit() == 0) {
            question.setMemoryLimit(256);
        }
        if (question.getDifficulty() == null) {
            question.setDifficulty(1);
        }
        if (question.getStatus() == null) {
            question.setStatus(1);
        }
        if (question.getTotalSubmit() == null) {
            question.setTotalSubmit(0L);
        }
        if (question.getTotalAc() == null) {
            question.setTotalAc(0L);
        }
        if (question.getPassRate() == null) {
            question.setPassRate(BigDecimal.valueOf(0.0));
        }
    }

    /**
     * 从URL获取HTML内容
     */
    public String fetchHtmlFromUrl(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(10000)
                .get()
                .html();
    }
}
