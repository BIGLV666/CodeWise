package org.example.servicejudge.functionsService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.servicejudge.Util.CodeBuild;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;


public class Java {

    private static final ObjectMapper objectMapper=new ObjectMapper();

    public static String ToMain(String paserConfig,String FunctionName) throws JsonProcessingException {
        List<String> parameterTypes = CodeBuild.getType(paserConfig);
        String inferredOutputType = parameterTypes.contains("ListNode")
                ? "ListNode"
                : parameterTypes.contains("TreeNode") ? "TreeNode" : null;
        return ToMain(paserConfig, FunctionName, inferredOutputType);
    }

    public static String ToMain(
            String paserConfig,
            String FunctionName,
            String outputType
    ) throws JsonProcessingException {
        List<String> parameterTypes = CodeBuild.getType(paserConfig);
        boolean usesListNode = parameterTypes.contains("ListNode");
        boolean usesTreeNode = parameterTypes.contains("TreeNode");
        boolean returnsListNode = "ListNode".equals(outputType);
        boolean returnsTreeNode = "TreeNode".equals(outputType);
        StringBuilder main = new StringBuilder();

         main .append
                ("import java.lang.*;\n" +
                        "import java.util.*;\n" +
                        "import java.io.*;\n" +
                        "import java.nio.charset.StandardCharsets;\n" +
                        "import java.nio.file.*;\n" +
                        "import java.util.concurrent.*;\n" +
                        "import java.util.stream.*;\n" +
                        "import com.fasterxml.jackson.databind.ObjectMapper;\n" +
                        "\n" +
                        "public class Main {\n" +
                        "\n" +
                        "    public static void main(String[] args) throws Exception {\n" +
                        "        BufferedReader reader = new BufferedReader(\n" +
                        "                new InputStreamReader(System.in)\n" +
                        "        );\n" +
                        "        ObjectMapper objectMapper = new ObjectMapper();\n");

        LinkedHashMap<String,String>map=getInput(paserConfig);
        main.append("Solution solution=new Solution();\n");
        StringBuilder method=new StringBuilder("Object result = solution.").append(FunctionName).append("(");

        for(Map.Entry<String, String> key:map.entrySet()){
            main.append(key.getValue()).append("\n");
            method.append(key.getKey()).append(",");
        }

        if (!map.isEmpty()) {
            method.deleteCharAt(method.length() - 1);
        }
        method.append(");\n");
        main.append(method);
        main.append("        Path resultPath = Path.of(\"result.txt\");\n");
        main.append("        if (result == null) {\n");
        main.append("            Files.writeString(resultPath, \"")
                .append(returnsTreeNode ? "[]" : "null")
                .append("\", StandardCharsets.UTF_8);\n");
        main.append("        }\n");

        if (returnsListNode) {
            main.append("""
                else if (result instanceof ListNode) {
                    writeListNodeResult(resultPath, (ListNode) result);
                }
                """);
        }
        if (returnsTreeNode) {
            main.append("""
                else if (result instanceof TreeNode) {
                    writeTreeNodeResult(resultPath, (TreeNode) result);
                }
                """);
        }
        main.append("""
                else if (result instanceof CharSequence
                        || result instanceof Number
                        || result instanceof Boolean
                        || result instanceof Character) {
                    Files.writeString(
                            resultPath,
                            String.valueOf(result),
                            StandardCharsets.UTF_8
                    );
                } else {
                    objectMapper.writeValue(resultPath.toFile(), result);
                }
                """);

        main.append("    }\n");
        String methodCode = CodeBuild.formatNodeMethod(paserConfig, outputType);
        main.append(methodCode);

        main.append("}\n");
        main.append(buildBatchMain(
                map,
                FunctionName,
                usesListNode,
                usesTreeNode,
                returnsListNode,
                returnsTreeNode
        ));

        return main.toString();



    }

    private static String buildBatchMain(
            LinkedHashMap<String, String> parameters,
            String functionName,
            boolean usesListNode,
            boolean usesTreeNode,
            boolean returnsListNode,
            boolean returnsTreeNode
    ) {
        StringBuilder batch = new StringBuilder("""

                class BatchMain {
                    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
                    private static final long TIME_LIMIT_MS = Long.getLong("codewise.timeLimitMs", 2000L);

                    public static void main(String[] args) throws Exception {
                        Path casesDirectory = Path.of("cases");
                        List<Path> caseFiles;
                        try (Stream<Path> files = Files.list(casesDirectory)) {
                            caseFiles = files
                                    .filter(Files::isRegularFile)
                                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                                    .toList();
                        }

                        PrintStream protocolOut = System.out;
                        for (Path caseFile : caseFiles) {
                            ByteArrayOutputStream userStdout = new ByteArrayOutputStream();
                            PrintStream capturedOut = new PrintStream(userStdout, true, StandardCharsets.UTF_8);
                            ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
                                Thread thread = new Thread(task, "codewise-case-runner");
                                thread.setDaemon(true);
                                return thread;
                            });
                            long startedAt = System.nanoTime();
                            int exitCode = 0;
                            String resultOutput = "";
                            String errorOutput = "";
                            boolean timedOut = false;

                            System.setOut(capturedOut);
                            Future<String> execution = executor.submit(() -> executeCase(caseFile));
                            try {
                                resultOutput = execution.get(TIME_LIMIT_MS, TimeUnit.MILLISECONDS);
                            } catch (TimeoutException exception) {
                                timedOut = true;
                                exitCode = 124;
                                errorOutput = "Time limit exceeded";
                                execution.cancel(true);
                            } catch (ExecutionException exception) {
                                exitCode = 1;
                                errorOutput = stackTrace(exception.getCause());
                            } finally {
                                executor.shutdownNow();
                                System.setOut(protocolOut);
                                capturedOut.close();
                            }

                            long timeUsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                            writeCaseResult(
                                    protocolOut,
                                    exitCode,
                                    timeUsed,
                                    resultOutput,
                                    userStdout.toString(StandardCharsets.UTF_8),
                                    errorOutput
                            );

                            if (timedOut) {
                                protocolOut.flush();
                                Runtime.getRuntime().halt(124);
                            }
                            if (exitCode != 0) {
                                break;
                            }
                        }
                    }

                    private static String executeCase(Path inputPath) throws Exception {
                        try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
                            ObjectMapper objectMapper = OBJECT_MAPPER;
                            Solution solution = new Solution();
                """);

        StringBuilder invocation = new StringBuilder("Object result = solution.")
                .append(functionName)
                .append("(");
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            batch.append("            ").append(parameter.getValue()).append("\n");
            invocation.append(parameter.getKey()).append(",");
        }
        if (!parameters.isEmpty()) {
            invocation.deleteCharAt(invocation.length() - 1);
        }
        invocation.append(");\n");
        batch.append("            ").append(invocation);
        batch.append("            return serializeResult(result);\n");
        batch.append("        }\n");
        batch.append("    }\n\n");

        if (usesListNode) {
            batch.append("""
                        private static ListNode buildListForListNode(int[] values) {
                            ListNode dummy = new ListNode(0);
                            ListNode current = dummy;
                            for (int value : values) {
                                current.next = new ListNode(value);
                                current = current.next;
                            }
                            return dummy.next;
                        }

                    """);
        }
        if (usesTreeNode) {
            batch.append("""
                        private static TreeNode buildTreeNode(Integer[] values) {
                            if (values.length == 0 || values[0] == null) {
                                return null;
                            }
                            TreeNode root = new TreeNode(values[0]);
                            Queue<TreeNode> queue = new ArrayDeque<>();
                            queue.offer(root);
                            int index = 1;
                            while (!queue.isEmpty() && index < values.length) {
                                TreeNode current = queue.poll();
                                if (values[index] != null) {
                                    current.left = new TreeNode(values[index]);
                                    queue.offer(current.left);
                                }
                                index++;
                                if (index < values.length && values[index] != null) {
                                    current.right = new TreeNode(values[index]);
                                    queue.offer(current.right);
                                }
                                index++;
                            }
                            return root;
                        }

                    """);
        }

        batch.append("""
                    private static String serializeResult(Object result) throws Exception {
                        if (result == null) {
                """);
        batch.append(returnsTreeNode ? "            return \"[]\";\n" : "            return \"null\";\n");
        batch.append("""
                        }
                """);
        if (returnsListNode) {
            batch.append("""
                            if (result instanceof ListNode) {
                                StringBuilder text = new StringBuilder("[");
                                ListNode current = (ListNode) result;
                                while (current != null) {
                                    if (text.length() > 1) {
                                        text.append(",");
                                    }
                                    text.append(current.val);
                                    current = current.next;
                                }
                                return text.append("]").toString();
                            }
                    """);
        }
        if (returnsTreeNode) {
            batch.append("""
                            if (result instanceof TreeNode) {
                                List<String> values = new ArrayList<>();
                                Queue<TreeNode> queue = new LinkedList<>();
                                queue.offer((TreeNode) result);
                                while (!queue.isEmpty()) {
                                    TreeNode current = queue.poll();
                                    if (current == null) {
                                        values.add("null");
                                    } else {
                                        values.add(String.valueOf(current.val));
                                        queue.offer(current.left);
                                        queue.offer(current.right);
                                    }
                                }
                                int last = values.size() - 1;
                                while (last >= 0 && "null".equals(values.get(last))) {
                                    last--;
                                }
                                return "[" + String.join(",", values.subList(0, last + 1)) + "]";
                            }
                    """);
        }
        batch.append("""
                        if (result instanceof CharSequence
                                || result instanceof Number
                                || result instanceof Boolean
                                || result instanceof Character) {
                            return String.valueOf(result);
                        }
                        return OBJECT_MAPPER.writeValueAsString(result);
                    }

                    private static String stackTrace(Throwable throwable) {
                        StringWriter writer = new StringWriter();
                        throwable.printStackTrace(new PrintWriter(writer));
                        return writer.toString();
                    }

                    private static void writeCaseResult(
                            PrintStream output,
                            int exitCode,
                            long timeUsed,
                            String result,
                            String stdout,
                            String stderr
                    ) {
                        output.printf("__CODEWISE_CASE_BEGIN__%d%n", exitCode);
                        output.printf("__CODEWISE_TIME_MS__%d%n", timeUsed);
                        output.println("__CODEWISE_RESULT_BEGIN__");
                        output.print(result);
                        output.println("\\n__CODEWISE_STDOUT_BEGIN__");
                        output.print(stdout);
                        output.println("\\n__CODEWISE_STDERR_BEGIN__");
                        output.print(stderr);
                        output.println("\\n__CODEWISE_CASE_END__");
                        output.flush();
                    }
                }
                """);
        return batch.toString();
    }

    public static LinkedHashMap<String, String> getInput(String paserConfig) throws JsonProcessingException {
        JsonNode paserConfigNode=objectMapper.readTree(paserConfig);
        LinkedHashMap<String,String>map=new LinkedHashMap<>();
        for(int i=0;i<paserConfigNode.size();i++){
            JsonNode declarationNode=paserConfigNode.get(i);
            String type=declarationNode.get("type").asText();
            String name=declarationNode.get("name").asText().trim();
            map.put(name, CodeBuild.getType(type,name));
        }


        return map;

    }


}
