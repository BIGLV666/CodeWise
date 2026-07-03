package org.example.serviceai.serviceQuestion;

import org.example.serviceai.service.AIService;
import org.example.servicecommon.dto.QuestionMessage;
import org.example.servicecommon.dto.TestMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TestCaseBuilder {

    @Autowired
    private AIService aiService;

    public List<TestMessage> buildTestCases(QuestionMessage questionMessage) {
        String prompt = buildPrompt(questionMessage);
        String aiResponse = aiService.callAi(prompt);
        return TestCaseParser.parse(aiResponse, questionMessage.getQuestionId(), questionMessage.getCreateUserId());
    }

    /**
     * 🔥 优化后的提示词 - 不使用 ```json 代码块，避免 Qwen 流式 bug
     */
    private static String buildPrompt(QuestionMessage questionMessage) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个专业的算法竞赛命题专家和测试用例设计师。\n");
        prompt.append("请根据以下题目信息，生成全面、严谨的测试用例。\n\n");

        // ========== 题目信息 ==========
        prompt.append("【题目信息】\n");
        prompt.append("标题: ").append(safeStr(questionMessage.getTitle(), "未知")).append("\n");
        prompt.append("时间限制: ").append(questionMessage.getTimeLimit() != null ? questionMessage.getTimeLimit() : 1000).append(" ms\n");
        prompt.append("内存限制: ").append(questionMessage.getMemoryLimit() != null ? questionMessage.getMemoryLimit() : 256).append(" MB\n");

        if (questionMessage.getTags() != null && !questionMessage.getTags().isEmpty()) {
            prompt.append("标签: ").append(questionMessage.getTags()).append("\n");
        }

        prompt.append("\n【题目描述】\n");
        prompt.append(safeStr(questionMessage.getDescription(), "无")).append("\n\n");

        prompt.append("【输入描述】\n");
        prompt.append(safeStr(questionMessage.getInputDesc(), "无")).append("\n\n");

        prompt.append("【输出描述】\n");
        prompt.append(safeStr(questionMessage.getOutputDesc(), "无")).append("\n\n");

        prompt.append("【样例输入】\n");
        prompt.append(safeStr(questionMessage.getSampleInput(), "无")).append("\n\n");

        prompt.append("【样例输出】\n");
        prompt.append(safeStr(questionMessage.getSampleOutput(), "无")).append("\n\n");

        if (questionMessage.getHint() != null && !questionMessage.getHint().isEmpty()) {
            prompt.append("【提示】\n");
            prompt.append(questionMessage.getHint()).append("\n\n");
        }

        // ========== 生成要求 ==========
        prompt.append("【生成要求】\n");
        prompt.append("请生成 8-12 个测试用例，必须包含以下类型：\n\n");

        prompt.append("1. 样例验证用例（1-2个）\n");
        prompt.append("   - 使用题目给出的样例数据\n");
        prompt.append("   - sortOrder: 1\n");
        prompt.append("   - scoreWeight: 10\n\n");

        prompt.append("2. 基础功能用例（2-3个）\n");
        prompt.append("   - 使用简单、典型的输入数据\n");
        prompt.append("   - sortOrder: 2-4\n");
        prompt.append("   - scoreWeight: 10\n\n");

        prompt.append("3. 边界测试用例（2-3个）\n");
        prompt.append("   - 最小输入值\n");
        prompt.append("   - 最大输入值\n");
        prompt.append("   - sortOrder: 5-7\n");
        prompt.append("   - scoreWeight: 15\n\n");

        prompt.append("4. 特殊数据用例（2-3个）\n");
        prompt.append("   - 全相同数据\n");
        prompt.append("   - 递增/递减序列\n");
        prompt.append("   - sortOrder: 8-10\n");
        prompt.append("   - scoreWeight: 15\n\n");

        prompt.append("5. 性能压力用例（1-2个）\n");
        prompt.append("   - 大规模数据\n");
        prompt.append("   - sortOrder: 11-12\n");
        prompt.append("   - scoreWeight: 20\n\n");

        prompt.append("6. 陷阱/易错用例（1-2个）\n");
        prompt.append("   - 容易WA的特殊情况\n");
        prompt.append("   - sortOrder: 13-15\n");
        prompt.append("   - scoreWeight: 20\n\n");

        // ========== 🔥 输出格式 - 不使用 ```json ==========
        prompt.append("【输出格式 - 重要】\n");
        prompt.append("直接输出纯JSON对象，不要使用任何markdown代码块标记（如 ```json 或 ```）。\n\n");
        prompt.append("输出格式必须是：\n");
        prompt.append("{\n");
        prompt.append("  \"testCases\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"inputData\": \"输入数据\",\n");
        prompt.append("      \"expectedOutput\": \"期望输出\",\n");
        prompt.append("      \"timeLimit\": 1000,\n");
        prompt.append("      \"memoryLimit\": 125,\n");
        prompt.append("      \"sortOrder\": 1,\n");
        prompt.append("      \"scoreWeight\": 10\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n\n");

        prompt.append("【注意事项】\n");
        prompt.append("1. 只输出纯JSON，不要任何解释\n");
        prompt.append("2. 不要使用 ```json 或 ``` 标记\n");
        prompt.append("3. inputData 和 expectedOutput 中的换行用 \\n 表示\n");
        prompt.append("4. 所有测试用例的 timeLimit 使用 ").append(questionMessage.getTimeLimit() != null ? questionMessage.getTimeLimit() : 1000).append("\n");
        prompt.append("5. 所有测试用例的 memoryLimit 使用 ").append(questionMessage.getMemoryLimit() != null ? questionMessage.getMemoryLimit() : 256).append("\n");
        prompt.append("6. sortOrder 从1开始递增\n");
        prompt.append("7. scoreWeight 总和接近100\n");
        prompt.append("8. 确保输入符合约束条件\n");
        prompt.append("9.所有测试用例必须保证完全正确，先根据题目生成标准代码与你的用例输出结果比对，避免错误用例的出现\n");
        prompt.append("10.只输出你能确保绝对正确的样例，严禁错误用例出现\n");
        prompt.append("11.如果题目你判断无需测试用例，只有一个标准答案，那么生成的用例如果需要输入则添加输入样例和输出答案，无需输入只需要生成一个唯一的结果即可\n");

        return prompt.toString();
    }

    /**
     * 安全处理字符串
     */
    private static String safeStr(String str, String defaultValue) {
        return (str != null && !str.trim().isEmpty()) ? str : defaultValue;
    }
}