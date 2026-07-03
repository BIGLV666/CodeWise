package org.example.serviceai.serviceQuestion;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.example.servicecommon.dto.TestMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测试用例JSON解析器
 * 支持 GLM 和 Qwen 的响应格式
 */
public class TestCaseParser {

    /**
     * 将AI返回的JSON解析为TestMessage列表
     */
    public static List<TestMessage> parse(String aiResponse, Long questionId, Long createUserId) {
        System.out.println("原始AI响应长度: " + aiResponse.length());
        List<TestMessage> testMessages = new ArrayList<>();

        try {
            if (aiResponse == null || aiResponse.trim().isEmpty()) {
                throw new RuntimeException("AI响应内容为空");
            }

            String jsonContent = extractAndCleanJson(aiResponse);
            System.out.println("清理后的JSON前500字符: " + jsonContent);

            JSONObject root = JSONUtil.parseObj(jsonContent);

            JSONArray testCases = root.getJSONArray("testCases");
            if (testCases == null || testCases.isEmpty()) {
                // 尝试直接解析为数组
                try {
                    JSONArray directArray = JSONUtil.parseArray(jsonContent);
                    if (directArray != null && !directArray.isEmpty()) {
                        testCases = directArray;
                    }
                } catch (Exception ignored) {
                }
            }

            if (testCases == null || testCases.isEmpty()) {
                System.out.println("没有解析到任何测试用例");
                return testMessages;
            }

            for (int i = 0; i < testCases.size(); i++) {
                JSONObject testCase = testCases.getJSONObject(i);

                TestMessage message = new TestMessage();
                message.setQuestionId(questionId);
                message.setCreateUserId(createUserId);

                message.setInputData(testCase.getStr("inputData"));
                message.setExpectedOutput(testCase.getStr("expectedOutput"));

                Integer timeLimit = testCase.getInt("timeLimit");
                if (timeLimit != null) {
                    message.setTimeLimit(timeLimit);
                }

                Integer memoryLimit = testCase.getInt("memoryLimit");
                if (memoryLimit != null) {
                    message.setMemoryLimit(memoryLimit);
                }

                Integer sortOrder = testCase.getInt("sortOrder");
                if (sortOrder != null) {
                    message.setSortOrder(sortOrder);
                }

                Integer scoreWeight = testCase.getInt("scoreWeight");
                if (scoreWeight != null) {
                    message.setScoreWeight(scoreWeight);
                }

                testMessages.add(message);
            }

            System.out.println("成功解析 " + testMessages.size() + " 个测试用例");

        } catch (Exception e) {
            System.err.println("解析测试用例JSON失败，原始响应前500字符: " +
                    (aiResponse.length() > 500 ? aiResponse.substring(0, 500) : aiResponse));
            throw new RuntimeException("解析测试用例JSON失败: " + e.getMessage(), e);
        }

        return testMessages;
    }

    /**
     * 提取并清理JSON内容
     */
    private static String extractAndCleanJson(String aiResponse) {
        if (aiResponse == null || aiResponse.trim().isEmpty()) {
            throw new RuntimeException("AI响应内容为空");
        }

        String text = aiResponse;

        // ========== 第一步：彻底移除所有 markdown 标记 ==========
        // 移除所有 ```json 和 ``` 标记（包括重复的）
        text = text.replaceAll("```json\\s*", "");
        text = text.replaceAll("```\\s*", "");
        text = text.replaceAll("`", "");

        // 移除所有单独的 "json" 标记
        text = text.replaceAll("\\bjson\\b", "");

        // 移除多余的空行
        text = text.replaceAll("\\n\\s*\\n", "\n");
        text = text.trim();

        // ========== 第二步：找到真正的 JSON 开始位置 ==========
        // 找到第一个 "testCases" 出现的位置，往前找到最近的 {
        int testCasesIndex = text.indexOf("\"testCases\"");
        if (testCasesIndex == -1) {
            testCasesIndex = text.indexOf("testCases");
        }

        String jsonContent = text;
        if (testCasesIndex != -1) {
            // 从 testCases 位置往前找到第一个 {
            int start = text.lastIndexOf('{', testCasesIndex);
            if (start != -1) {
                // 从 start 开始，找到匹配的 }
                int braceCount = 0;
                int end = -1;
                for (int i = start; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '{') braceCount++;
                    else if (c == '}') {
                        braceCount--;
                        if (braceCount == 0) {
                            end = i + 1;
                            break;
                        }
                    }
                }
                if (end != -1) {
                    jsonContent = text.substring(start, end);
                }
            }
        } else {
            // 如果没有找到 testCases，尝试提取第一个 { 到最后一个 } 之间的内容
            int firstBrace = text.indexOf('{');
            int lastBrace = text.lastIndexOf('}');
            if (firstBrace != -1 && lastBrace != -1 && firstBrace < lastBrace) {
                jsonContent = text.substring(firstBrace, lastBrace + 1);
            }
        }

        // ========== 第三步：清理 JSON 语法问题 ==========
        jsonContent = cleanJsonSyntax(jsonContent);

        // ========== 第四步：验证并修复 JSON ==========
        try {
            JSONUtil.parse(jsonContent);
            return jsonContent;
        } catch (Exception e) {
            // 尝试修复
            String fixed = tryFixJson(jsonContent);
            try {
                JSONUtil.parse(fixed);
                return fixed;
            } catch (Exception ex) {
                // 尝试提取 testCases 数组
                String extracted = extractTestCasesArray(jsonContent);
                if (extracted != null) {
                    try {
                        JSONUtil.parse(extracted);
                        return extracted;
                    } catch (Exception ignored) {
                    }
                }
                throw new RuntimeException("无法提取有效的JSON内容: " + e.getMessage());
            }
        }
    }

    /**
     * 清理 JSON 语法问题
     */
    private static String cleanJsonSyntax(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }

        String cleaned = json;

        // 1. 修复错误的key格式: ="sortOrder" -> "sortOrder"
        cleaned = cleaned.replaceAll("=\\s*\"", "\"");

        // 2. 修复可能的语法错误
        cleaned = cleaned.replaceAll(",\\s*}", "}");
        cleaned = cleaned.replaceAll(",\\s*]", "]");

        // 3. 移除注释
        cleaned = removeComments(cleaned);

        // 4. 修复未闭合的字符串
        cleaned = fixUnclosedStrings(cleaned);

        return cleaned;
    }

    /**
     * 修复不完整的 JSON
     */
    private static String tryFixJson(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }

        String fixed = json.trim();

        // 补全缺失的括号
        int openBraces = countChar(fixed, '{');
        int closeBraces = countChar(fixed, '}');
        while (closeBraces < openBraces) {
            fixed += "}";
            closeBraces++;
        }

        int openBrackets = countChar(fixed, '[');
        int closeBrackets = countChar(fixed, ']');
        while (closeBrackets < openBrackets) {
            fixed += "]";
            closeBrackets++;
        }

        // 如果最后一个字符是逗号，移除
        if (fixed.endsWith(",")) {
            fixed = fixed.substring(0, fixed.length() - 1);
        }

        // 修复未闭合的字符串
        fixed = fixUnclosedStrings(fixed);

        return fixed;
    }

    /**
     * 修复未闭合的字符串
     */
    private static String fixUnclosedStrings(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }

        StringBuilder result = new StringBuilder();
        boolean inString = false;
        char prev = 0;

        for (char c : json.toCharArray()) {
            if (c == '"' && prev != '\\') {
                inString = !inString;
            }
            result.append(c);
            prev = c;
        }

        if (inString) {
            result.append('"');
        }

        return result.toString();
    }

    /**
     * 移除JSON中的注释
     */
    private static String removeComments(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }

        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean inSingleLineComment = false;
        boolean inMultiLineComment = false;
        char prev = 0;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (!inSingleLineComment && !inMultiLineComment) {
                if (c == '"' && prev != '\\') {
                    inString = !inString;
                }
            }

            if (inString) {
                result.append(c);
                prev = c;
                continue;
            }

            if (!inMultiLineComment && c == '/' && prev == '/') {
                result.deleteCharAt(result.length() - 1);
                inSingleLineComment = true;
                prev = c;
                continue;
            }

            if (!inSingleLineComment && c == '*' && prev == '/') {
                result.deleteCharAt(result.length() - 1);
                inMultiLineComment = true;
                prev = c;
                continue;
            }

            if (inMultiLineComment && c == '/' && prev == '*') {
                inMultiLineComment = false;
                prev = c;
                continue;
            }

            if (inSingleLineComment) {
                if (c == '\n' || c == '\r') {
                    inSingleLineComment = false;
                    result.append(c);
                }
                prev = c;
                continue;
            }

            if (inMultiLineComment) {
                prev = c;
                continue;
            }

            result.append(c);
            prev = c;
        }

        return result.toString();
    }

    /**
     * 提取 testCases 数组
     */
    private static String extractTestCasesArray(String text) {
        // 匹配 {"testCases": [...]}
        Pattern pattern = Pattern.compile("\\{\\s*\"testCases\"\\s*:\\s*(\\[[\\s\\S]*?\\])\\s*\\}");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return "{\"testCases\":" + matcher.group(1) + "}";
        }

        // 匹配纯数组 [...]
        pattern = Pattern.compile("(\\[[\\s\\S]*?\\])");
        matcher = pattern.matcher(text);
        if (matcher.find()) {
            return "{\"testCases\":" + matcher.group(1) + "}";
        }

        return null;
    }

    private static int countChar(String str, char ch) {
        int count = 0;
        for (char c : str.toCharArray()) {
            if (c == ch) {
                count++;
            }
        }
        return count;
    }

    public static List<TestMessage> parse(String aiResponse, Long questionId) {
        return parse(aiResponse, questionId, null);
    }
}