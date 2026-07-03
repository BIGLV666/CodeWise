package org.example.servicequestion.fps;

import com.google.gson.*;
import jakarta.annotation.PostConstruct;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.TestCase;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.TestCaseMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.*;
@Component
public class OJImportUtil {
    @Autowired
    private  QuestionMapper questionMapper;
    @Autowired
    private TestCaseMapper testCaseMapper;

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();


    public void importData() throws IOException {
        System.out.println("========== 开始导入 ==========");

        // 先清空数据
        testCaseMapper.delete(null);
        questionMapper.delete(null);
        System.out.println("✅ 数据已清空");

        // 1-100 目录路径
        Path baseDir = Paths.get("E:/learncard/CodeWise/service-question/src/main/resources/static/uploads");

        // 找出所有 JSON 文件
        List<Path> jsonFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, "problem_*.json")) {
            for (Path p : stream) {
                jsonFiles.add(p);
            }
        }

        System.out.println("找到 " + jsonFiles.size() + " 个 JSON 文件\n");

        for (Path jsonFile : jsonFiles) {
            try {
                String fileName = jsonFile.getFileName().toString();
                String problemId = fileName.replace(".json", "");

                String jsonStr = Files.readString(jsonFile);
                JsonObject root = gson.fromJson(jsonStr, JsonObject.class);
                JsonObject problem = root.getAsJsonObject("problem");

                // 1. 构建 Question
                Question question = buildQuestion(problem, root, problemId);

                // 2. 先插入 Question，获取 questionId
                questionMapper.insert(question);
                Long questionId = question.getQuestionId();
                System.out.println("  插入题目: " + questionId + " - " + question.getTitle());

                // 3. 读取样例文件夹
                Path sampleDir = baseDir.resolve(problemId);
                List<TestCase> testCases = new ArrayList<>();

                if (Files.exists(sampleDir) && Files.isDirectory(sampleDir)) {
                    testCases = loadTestCasesFromDir(sampleDir, question);
                } else {
                    testCases = loadTestCasesFromJson(root, question);
                }

                // 4. 设置 questionId 并批量插入测试点
                for (TestCase tc : testCases) {
                    tc.setQuestionId(questionId);
                    testCaseMapper.insert(tc);
                }

                System.out.println("✅ " + problemId + " → " + testCases.size() + " 个测试点插入成功");

            } catch (Exception e) {
                System.err.println("❌ 处理失败: " + jsonFile.getFileName() + " - " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("\n========== 导入完成 ==========");
        System.out.println("✅ 所有题目和测试点已导入数据库");
    }

    // 构建 Question 对象
    private  Question buildQuestion(JsonObject problem, JsonObject root, String problemId) {
        String title = problem.get("title").getAsString();
        String description = cleanHtml(problem.get("description").getAsString());
        String inputDesc = cleanHtml(problem.get("input").getAsString());
        String outputDesc = cleanHtml(problem.get("output").getAsString());
        String hint = problem.has("hint") ? cleanHtml(problem.get("hint").getAsString()) : "";
        String source = problem.has("source") ? problem.get("source").getAsString() : "一本通在线评测";
        Integer difficulty = problem.get("difficulty").getAsInt();
        String tags = extractTags(root);
        Integer timeLimit = problem.get("timeLimit").getAsInt();
        Integer memoryLimit = problem.get("memoryLimit").getAsInt();

        // 解析样例
        String sampleInput = "";
        String sampleOutput = "";
        String examples = problem.get("examples").getAsString();
        if (examples != null && !examples.isEmpty()) {
            String[] parsed = parseExample(examples);
            sampleInput = parsed[0];
            sampleOutput = parsed[1];
        }

        Question question = Question.builder()
                .title(title)
                .description(description)
                .inputDesc(inputDesc)
                .outputDesc(outputDesc)
                .sampleInput(sampleInput)
                .sampleOutput(sampleOutput)
                .hint(hint)
                .source(source)
                .difficulty(difficulty)
                .tags(tags)
                .timeLimit(timeLimit)
                .memoryLimit(memoryLimit)
                .status(1)
                .aiStatue("success")
                .totalSubmit(0L)
                .totalAc(0L)
                .passRate(BigDecimal.ZERO)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .createUserId(2070815253393932289L)
                .build();

        question.setContentHash(DigestUtils.md5DigestAsHex(description.getBytes()));
        return question;
    }

    // 从文件夹读取 .in/.out
    private  List<TestCase> loadTestCasesFromDir(Path sampleDir, Question question) throws IOException {
        List<TestCase> testCases = new ArrayList<>();
        List<Path> inFiles = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sampleDir, "*.in")) {
            for (Path p : stream) {
                inFiles.add(p);
            }
        }

        // 按文件名排序
        inFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

        int total = inFiles.size();
        if (total == 0) return testCases;

        // 计算权重
        int[] weights = calculateWeights(total);

        for (int i = 0; i < total; i++) {
            Path inFile = inFiles.get(i);
            String baseName = inFile.getFileName().toString().replace(".in", "");
            Path outFile = sampleDir.resolve(baseName + ".out");

            String inputData = Files.readString(inFile).trim();
            String expectedOutput = Files.exists(outFile) ? Files.readString(outFile).trim() : "";

            boolean isSample = (i < 2);

            TestCase testCase = TestCase.builder()
                    .inputData(inputData)
                    .expectedOutput(expectedOutput)
                    .isSample(isSample ? 1 : 0)
                    .isHidden(isSample ? 0 : 1)
                    .sortOrder(i)
                    .scoreWeight(weights[i])
                    .timeLimit(question.getTimeLimit())
                    .memoryLimit(question.getMemoryLimit())
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .createUserId(2070815253393932289L)
                    .build();

            testCases.add(testCase);
        }

        return testCases;
    }

    // 从 JSON 的 samples 数组读取（备用）
    private  List<TestCase> loadTestCasesFromJson(JsonObject root, Question question) {
        List<TestCase> testCases = new ArrayList<>();
        JsonArray samplesArray = root.getAsJsonArray("samples");

        if (samplesArray == null || samplesArray.size() == 0) return testCases;

        int total = samplesArray.size();
        int[] weights = calculateWeights(total);

        for (int i = 0; i < total; i++) {
            JsonObject sample = samplesArray.get(i).getAsJsonObject();

            String inputData = sample.get("input").getAsString();
            String expectedOutput = sample.get("output").getAsString();
            boolean isSample = (i < 2);

            TestCase testCase = TestCase.builder()
                    .inputData(inputData)
                    .expectedOutput(expectedOutput)
                    .isSample(isSample ? 1 : 0)
                    .isHidden(isSample ? 0 : 1)
                    .sortOrder(i)
                    .scoreWeight(weights[i])
                    .timeLimit(question.getTimeLimit())
                    .memoryLimit(question.getMemoryLimit())
                    .build();

            testCases.add(testCase);
        }

        return testCases;
    }

    // 计算权重分配：前30% 20分，中间50% 60分，后20% 20分
    private  int[] calculateWeights(int total) {
        int[] weights = new int[total];
        if (total == 0) return weights;

        int weight20Count = (int) Math.ceil(total * 0.3);
        int weight60Count = (int) Math.ceil(total * 0.5);
        int lastCount = total - weight20Count - weight60Count;
        if (lastCount < 0) lastCount = 0;

        int score1 = weight20Count > 0 ? 20 / weight20Count : 0;
        int score2 = weight60Count > 0 ? 60 / weight60Count : 0;
        int score3 = lastCount > 0 ? 20 / lastCount : 0;

        int idx = 0;
        for (int i = 0; i < weight20Count && idx < total; i++) {
            weights[idx++] = Math.max(1, score1);
        }
        for (int i = 0; i < weight60Count && idx < total; i++) {
            weights[idx++] = Math.max(1, score2);
        }
        for (int i = 0; i < lastCount && idx < total; i++) {
            weights[idx++] = Math.max(1, score3);
        }

        // 如果还有剩余，补1分
        for (int i = idx; i < total; i++) {
            weights[i] = 1;
        }

        return weights;
    }

    // ========== 工具方法 ==========

    private  String cleanHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .trim();
    }

    private  String[] parseExample(String examples) {
        String input = "";
        String output = "";
        Pattern inputPattern = Pattern.compile("<input>(.*?)</input>", Pattern.DOTALL);
        Pattern outputPattern = Pattern.compile("<output>(.*?)</output>", Pattern.DOTALL);

        Matcher inputMatcher = inputPattern.matcher(examples);
        Matcher outputMatcher = outputPattern.matcher(examples);

        if (inputMatcher.find()) {
            input = inputMatcher.group(1).trim();
        }
        if (outputMatcher.find()) {
            output = outputMatcher.group(1).trim();
        }
        return new String[]{input, output};
    }

    private  String extractTags(JsonObject root) {
        JsonArray tags = root.getAsJsonArray("tags");
        if (tags == null || tags.size() == 0) return "一本通";
        List<String> tagList = new ArrayList<>();
        for (JsonElement tag : tags) {
            tagList.add(tag.getAsString());
        }
        return String.join(",", tagList);
    }


}