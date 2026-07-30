package org.example.servicejudge;

import org.example.servicejudge.Util.CodeBuild;
import org.example.servicejudge.functionsService.Java;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;


public class JavaTest {
    private final Java generator = new Java();

    @TempDir
    Path tempDirectory;

    @Test
    void shouldGenerateMultipleParameters() throws Exception {
        String parameterConfig = """
                [
                  {"type":"String","name":"first"},
                  {"type":"int","name":"count"}
                ]
                """;

        String source = generator.ToMain(
                parameterConfig,
                "repeat"
        );
        System.out.println(source);

        assertAll(
                () -> assertTrue(source.contains("new Solution()")),
                () -> assertTrue(source.contains(
                        "String first = reader.readLine();"
                )),
                () -> assertTrue(source.contains(
                        "int count = Integer.parseInt(reader.readLine());"
                )),
                () -> assertTrue(source.contains(
                        "solution.repeat(first,count)"
                )),
                () -> assertTrue(source.contains("Object result =")),
                () -> assertTrue(source.contains("Path.of(\"result.txt\")")),
                () -> assertFalse(source.contains("System.out.print(solution."))
        );
    }
    @Test
    void shouldRejectUnsupportedType() {
        String parameterConfig = """
            [
              {"type":"TreeNode","name":"root"}
            ]
            """;

        assertThrows(
                IllegalArgumentException.class,
                () -> generator.ToMain(
                        parameterConfig,
                        "maxDepth"
                )
        );
    }
    @Test
    void generatedSourceShouldCompile() throws Exception {
        String parameterConfig = """
                [
                  {"type":"String","name":"first"},
                  {"type":"String","name":"second"}
                ]
                """;

        String mainCode = generator.ToMain(
                parameterConfig,
                "concat"
        );

        String solutionCode = """

                class Solution {
                    public String concat(
                            String first,
                            String second
                    ) {
                        return first + second;
                    }
                }
                """;

        Path sourceFile = tempDirectory.resolve("Main.java");

        Files.writeString(
                sourceFile,
                mainCode + solutionCode,
                StandardCharsets.UTF_8
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        assertNotNull(
                compiler,
                "测试必须使用 JDK，不能使用只有 JRE 的环境"
        );

        int exitCode = compiler.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-encoding",
                "UTF-8",
                sourceFile.toString()
        );

        assertEquals(0, exitCode, "生成的 Main.java 应该能够编译");
    }

    @Test
    void generatedMainShouldWriteReturnValueToFileAndKeepDebugOutputOnStdout() throws Exception {
        String config = """
            [{"type":"int","name":"value"}]
            """;
        String name = "buildArray";

        String mainCode = generator.ToMain(config, name);
        String solutionCode = """
            import java.util.List;

            public class Solution {
                public List<Integer> buildArray(int value) {
                    return List.of(1, 2);
                }
            }
            """;

        Path mainFile = tempDirectory.resolve("Main.java");
        Path solutionFile = tempDirectory.resolve("Solution.java");

        Files.writeString(mainFile, mainCode, StandardCharsets.UTF_8);
        Files.writeString(solutionFile, solutionCode, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        assertNotNull(
                compiler,
                "测试必须使用 JDK，不能使用只有 JRE 的环境"
        );

        int exitCode = compiler.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-encoding",
                "UTF-8",
                mainFile.toString(),
                solutionFile.toString()
        );

        assertEquals(0, exitCode, "生成的源码应该能够编译");

        String runtimeClasspath = tempDirectory
                + File.pathSeparator
                + System.getProperty("java.class.path");

        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath",
                runtimeClasspath,
                "Main"
        )
                .directory(tempDirectory.toFile())
                .start();

        process.getOutputStream().write("1\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        assertEquals(0, process.waitFor());



        assertEquals(
                "[1,2]",
                Files.readString(
                        tempDirectory.resolve("result.txt"),
                        StandardCharsets.UTF_8
                )
        );
    }

    @Test
    void generatedSolutionBuild()throws Exception {
        String config = """
            [{"type":"String","name":"s"}]
            """;
        String userSolutionCode = "public class Solution {\n" +
                "    public int lengthOfLongestSubstring(String s) {\n" +
                "        // 哈希集合，记录每个字符是否出现过\n" +
                "        Set<Character> occ = new HashSet<Character>();\n" +
                "        int n = s.length();\n" +
                "        // 右指针，初始值为 -1，相当于我们在字符串的左边界的左侧，还没有开始移动\n" +
                "        int rk = -1, ans = 0;\n" +
                "        for (int i = 0; i < n; ++i) {\n" +
                "            if (i != 0) {\n" +
                "                // 左指针向右移动一格，移除一个字符\n" +
                "                occ.remove(s.charAt(i - 1));\n" +
                "            }\n" +
                "            while (rk + 1 < n && !occ.contains(s.charAt(rk + 1))) {\n" +
                "                // 不断地移动右指针\n" +
                "                occ.add(s.charAt(rk + 1));\n" +
                "                ++rk;\n" +
                "            }\n" +
                "            // 第 i 到 rk 个字符是一个极长的无重复字符子串\n" +
                "            ans = Math.max(ans, rk - i + 1);\n" +
                "        }\n" +
                "        return ans;\n" +
                "    }\n" +
                "}" ;
        String res= CodeBuild.build(userSolutionCode,config);
        String ans="import java.util.*;\n" +
                "import java.lang.*;\n" +
                "public class Solution {\n" +
                "    public int lengthOfLongestSubstring(String s) {\n" +
                "        // 哈希集合，记录每个字符是否出现过\n" +
                "        Set<Character> occ = new HashSet<Character>();\n" +
                "        int n = s.length();\n" +
                "        // 右指针，初始值为 -1，相当于我们在字符串的左边界的左侧，还没有开始移动\n" +
                "        int rk = -1, ans = 0;\n" +
                "        for (int i = 0; i < n; ++i) {\n" +
                "            if (i != 0) {\n" +
                "                // 左指针向右移动一格，移除一个字符\n" +
                "                occ.remove(s.charAt(i - 1));\n" +
                "            }\n" +
                "            while (rk + 1 < n && !occ.contains(s.charAt(rk + 1))) {\n" +
                "                // 不断地移动右指针\n" +
                "                occ.add(s.charAt(rk + 1));\n" +
                "                ++rk;\n" +
                "            }\n" +
                "            // 第 i 到 rk 个字符是一个极长的无重复字符子串\n" +
                "            ans = Math.max(ans, rk - i + 1);\n" +
                "        }\n" +
                "        return ans;\n" +
                "    }\n" +
                "}";
        assertEquals(ans, res);
    }




    @Test
    void generatedSolutionBuildWithListNode()throws Exception {
        String config = """
                [{"type":"ListNode","name":"head"},
                {"type":"int","name":"left"},
                {"type":"int","name":"right"}]
                """;
        String name = "reverseBetween";

        String mainCode = generator.ToMain(config, name);
        System.out.println("mainCode=================\n"+mainCode);
        String solutionCode = """
                    class Solution {
                          public ListNode fan(ListNode head){
                              ListNode prve=null,cur=head,nextnode=null;
                              while(cur!=null){
                                  nextnode=cur.next;
                                  cur.next=prve;
                                  prve=cur;
                                  cur=nextnode;
                              }
                              return prve;
                          }
                          public ListNode reverseBetween(ListNode head, int left, int right) {
                              ListNode dummy=new ListNode(-1);
                              ListNode cur=head;
                              ListNode l=head;
                              ListNode r=head;
                              l=dummy;
                              dummy.next=head;
                              for(int i=0;i<left-1;i++){
                                  l=l.next;
                              }
                              for(int i=0;i<right-1;i++){
                                  r=r.next;
                              }
                              ListNode second=null;
                              if(r!=null){
                                  second=r.next;
                                  r.next=null;}
                              ListNode first=l.next;
                              first=fan(first);
                              l.next=first;
                              while(cur.next!=null){
                                  cur=cur.next;
                              }
                              if(second!=null)
                                  cur.next=second;
                              return dummy.next;  \s
                          }
                      }
                """;

        System.out.println("SolutionCode======================\n"+CodeBuild.build(solutionCode,config));
        Path mainFile = tempDirectory.resolve("Main.java");
        Path solutionFile = tempDirectory.resolve("Solution.java");

        Files.writeString(mainFile, mainCode, StandardCharsets.UTF_8);
        Files.writeString(solutionFile, CodeBuild.build(solutionCode, config), StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        assertNotNull(
                compiler,
                "测试必须使用 JDK，不能使用只有 JRE 的环境"
        );

        int exitCode = compiler.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-encoding",
                "UTF-8",
                mainFile.toString(),
                solutionFile.toString()
        );

        assertEquals(0, exitCode, "生成的源码应该能够编译");



        String runtimeClasspath = tempDirectory
                + File.pathSeparator
                + System.getProperty("java.class.path");

        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath",
                runtimeClasspath,
                "Main"
        )
                .directory(tempDirectory.toFile())
                .start();
        String input = """
        [1,2,3,4,5]
        2
        4
        """;
        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        int processExitCode = process.waitFor();

        String stdout = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        String stderr = new String(
                process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8
        );

        System.out.println("Main stdout:\n" + stdout);
        System.out.println("Main stderr:\n" + stderr);

        assertEquals(0, processExitCode, "Main 执行失败:\n" + stderr);

        Path resultPath = tempDirectory.resolve("result.txt");

        System.out.println(Files.readString(resultPath, StandardCharsets.UTF_8));
        assertEquals(
                "[1,4,3,2,5]",
                Files.readString(resultPath, StandardCharsets.UTF_8)

        );


    }
    @Test
    void generatedSolutionBuildWithDoubleArray()throws Exception {
        String config = """
                [{"type":"int[][]","name":" matrix"},
                {"type":"int","name":"target"}]
                """;
        String name = "searchMatrix";
        String mainCode = generator.ToMain(config, name);
        System.out.println("mainCode=================\n" + mainCode);
        String solutionCode = "class Solution {\n" +
                "    public boolean searchMatrix(int[][] matrix, int target) {\n" +
                "        int l=matrix.length;\n" +
                "        int h=matrix[0].length;\n" +
                "        for(int i=0;i<l;i++){\n" +
                "            for(int j=0;j<h;j++){\n" +
                "                if(matrix[i][j]==target)return true;\n" +
                "            }\n" +
                "        }\n" +
                "        return false;\n" +
                "    }\n" +
                "}";


        Path mainFile = tempDirectory.resolve("Main.java");
        Path solutionFile = tempDirectory.resolve("Solution.java");

        Files.writeString(mainFile, mainCode, StandardCharsets.UTF_8);
        Files.writeString(solutionFile, solutionCode, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        assertNotNull(
                compiler,
                "测试必须使用 JDK，不能使用只有 JRE 的环境"
        );

        int exitCode = compiler.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-encoding",
                "UTF-8",
                mainFile.toString(),
                solutionFile.toString()
        );

        assertEquals(0, exitCode, "生成的源码应该能够编译");


        String runtimeClasspath = tempDirectory
                + File.pathSeparator
                + System.getProperty("java.class.path");

        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath",
                runtimeClasspath,
                "Main"
        )
                .directory(tempDirectory.toFile())
                .start();
        String input = """
               [[1,3,5,7],[10,11,16,20],[23,30,34,60]]
               3
               """;
        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        int processExitCode = process.waitFor();

        String stdout = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        String stderr = new String(
                process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8
        );

        System.out.println("Main stdout:\n" + stdout);
        System.out.println("Main stderr:\n" + stderr);

        assertEquals(0, processExitCode, "Main 执行失败:\n" + stderr);

        Path resultPath = tempDirectory.resolve("result.txt");

        System.out.println(Files.readString(resultPath, StandardCharsets.UTF_8));
        assertEquals(
                "true",
                Files.readString(resultPath, StandardCharsets.UTF_8)

        );


    }

    @Test
    void generatedBatchMainShouldRunAllCasesInOneJvm() throws Exception {
        String config = """
                [{"type":"int","name":"value"}]
                """;
        String mainCode = generator.ToMain(config, "calculate");
        String solutionCode = """
                public class Solution {
                    private static int invocationCount = 0;

                    public int calculate(int value) {
                        System.out.println("debug-" + value);
                        invocationCount++;
                        return value + invocationCount;
                    }
                }
                """;

        Path mainFile = tempDirectory.resolve("Main.java");
        Path solutionFile = tempDirectory.resolve("Solution.java");
        Path casesDirectory = Files.createDirectories(tempDirectory.resolve("cases"));
        Files.writeString(mainFile, mainCode, StandardCharsets.UTF_8);
        Files.writeString(solutionFile, solutionCode, StandardCharsets.UTF_8);
        Files.writeString(casesDirectory.resolve("case-000001.txt"), "1\n", StandardCharsets.UTF_8);
        Files.writeString(casesDirectory.resolve("case-000002.txt"), "2\n", StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        int compileExitCode = compiler.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-encoding",
                "UTF-8",
                mainFile.toString(),
                solutionFile.toString()
        );
        assertEquals(0, compileExitCode, "批量入口源码应该能够编译");

        String runtimeClasspath = tempDirectory
                + File.pathSeparator
                + System.getProperty("java.class.path");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dcodewise.timeLimitMs=1000",
                "-classpath",
                runtimeClasspath,
                "BatchMain"
        )
                .directory(tempDirectory.toFile())
                .start();

        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "批量入口执行超时");
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        String normalizedStdout = stdout.replace("\r\n", "\n");

        assertAll(
                () -> assertEquals(0, process.exitValue(), stderr),
                () -> assertEquals(2, normalizedStdout.split("__CODEWISE_CASE_BEGIN__0", -1).length - 1),
                () -> assertTrue(normalizedStdout.contains("__CODEWISE_RESULT_BEGIN__\n2")),
                () -> assertTrue(normalizedStdout.contains("__CODEWISE_RESULT_BEGIN__\n4")),
                () -> assertTrue(normalizedStdout.contains("debug-1")),
                () -> assertTrue(normalizedStdout.contains("debug-2"))
        );
    }
}
