package org.example.servicejudge.Util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Pattern;

public class CodeBuild {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern TOP_LEVEL_LIST_NODE = Pattern.compile(
            "(?m)^(?:public\\s+)?class\\s+ListNode\\b"
    );
    private static final Pattern TOP_LEVEL_TREE_NODE = Pattern.compile(
            "(?m)^(?:public\\s+)?class\\s+TreeNode\\b"
    );

    public static String build(String code,String paserConfig) throws JsonProcessingException {
        return build(code, paserConfig, null);
    }

    public static String build(String code, String paserConfig, String outputType) throws JsonProcessingException {
        StringBuilder sb=new StringBuilder();
        if(!code.contains("import java.util.*;")&&!code.contains("import  java.util.*;"))
        {
        sb.append("import java.util.*;\n");}
        if(!code.contains("import java.lang.*;")&&!code.contains("import  java.lang.*;")){
        sb.append("import java.lang.*;\n");}
        sb.append(code);
        List<String> types = getType(paserConfig);
        boolean needsListNode = types.contains("ListNode") || "ListNode".equals(outputType);
        boolean needsTreeNode = types.contains("TreeNode") || "TreeNode".equals(outputType);
        if (needsListNode && !containsTopLevelListNode(code)) {
            sb.append(listNodeClass());
        }
        if (needsTreeNode && !containsTopLevelTreeNode(code)) {
            sb.append(treeNodeClass());
        }
        return sb.toString();

    }

    public static List<String> getType(String parseConfig) throws JsonProcessingException {

        JsonNode paserConfigNode = objectMapper.readTree(parseConfig);
        List<String> paserType = new ArrayList<>();
        for (int i = 0; i < paserConfigNode.size(); i++) {
            JsonNode declarationNode = paserConfigNode.get(i);
            String type = declarationNode.get("type").asText();
            paserType.add(type);
        }
        return paserType;


    }
    public static List<String> getName(String parseConfig) throws JsonProcessingException {

        JsonNode paserConfigNode = objectMapper.readTree(parseConfig);
        List<String> paserType = new ArrayList<>();
        for (int i = 0; i < paserConfigNode.size(); i++) {
            JsonNode declarationNode = paserConfigNode.get(i);
            String name = declarationNode.get("name").asText();
            paserType.add(name);
        }
        return paserType;


    }
    public static String getType(String type, String name){

        return switch (type) {
            case "String" ->
                    "String " + name + " = reader.readLine();";

            case "int" ->
                    "int " + name +
                            " = Integer.parseInt(reader.readLine());";

            case "long" ->
                    "long " + name +
                            " = Long.parseLong(reader.readLine());";
            case "int[]", "int []" ->
                    "int[] " + name
                            + " = objectMapper.readValue(reader.readLine(), int[].class);";
            case "String[]", "String []" ->
                    "String[] " + name
                            + " = objectMapper.readValue(reader.readLine(), String[].class);";
            case "ListNode"->
                    "int[] " + name + "Values = objectMapper.readValue(reader.readLine(), int[].class);\n" +
                            "ListNode " + name + " = buildListForListNode(" + name + "Values);\n";
            case "TreeNode" ->
                    "Integer[] " + name + "Values = objectMapper.readValue(reader.readLine(), Integer[].class);\n" +
                            "TreeNode " + name + " = buildTreeNode(" + name + "Values);\n";
            case "int[][]","int [][]"->
                    "int[][] "+name+" = objectMapper.readValue(reader.readLine(), int[][].class);\n";


            default ->
                    throw new IllegalArgumentException(
                            "暂不支持的参数类型: " + type
                    );
        };

    }
    public static String formatClass(String paserConfig) throws JsonProcessingException {
        List<String>config=getType(paserConfig);
        StringBuilder s=new StringBuilder();
        if(config.contains("ListNode")){
            s.append(listNodeClass());
        }
        if(config.contains("TreeNode")){
            s.append(treeNodeClass());
        }
        return s.toString();
    }

    private static String listNodeClass() {
        return "\nclass ListNode {\n" +
                "    int val;\n" +
                "    ListNode next;\n" +
                "\n" +
                "    ListNode(int val) {\n" +
                "        this.val = val;\n" +
                "    }\n" +
                "}\n";
    }

    private static String treeNodeClass() {
        return "\nclass TreeNode {\n" +
                "    int val;\n" +
                "    TreeNode left;\n" +
                "    TreeNode right;\n" +
                "\n" +
                "    TreeNode(int val) {\n" +
                "        this.val = val;\n" +
                "    }\n" +
                "}\n";
    }

    private static boolean containsTopLevelListNode(String code) {
        return code != null && TOP_LEVEL_LIST_NODE.matcher(code).find();
    }

    private static boolean containsTopLevelTreeNode(String code) {
        return code != null && TOP_LEVEL_TREE_NODE.matcher(code).find();
    }

    public static String formatNodeMethod(String parseConfig) throws JsonProcessingException {
        return formatNodeMethod(parseConfig, null);
    }

    public static String formatNodeMethod(String parseConfig, String outputType) throws JsonProcessingException {
        List<String> config = getType(parseConfig);
        StringBuilder s = new StringBuilder();
        if (config.contains("ListNode") || "ListNode".equals(outputType)) {
            s.append("        private static ListNode buildListForListNode(int[] values) {\n" +
                    "                        ListNode dummy = new ListNode(0);\n" +
                    "                        ListNode current = dummy;\n" +
                    "\n" +
                    "                        for (int value : values) {\n" +
                    "                            current.next = new ListNode(value);\n" +
                    "                            current = current.next;\n" +
                    "                        }\n" +
                    "\n" +
                    "                        return dummy.next;\n" +
                    "                    }\n" +
                    "\n" +
                    "                    private static void writeListNodeResult(\n" +
                    "                            Path resultPath,\n" +
                    "                            ListNode head\n" +
                    "                    ) throws Exception {\n" +
                    "                        StringBuilder result = new StringBuilder(\"[\");\n" +
                    "                        ListNode current = head;\n" +
                    "\n" +
                    "                        while (current != null) {\n" +
                    "                            if (result.length() > 1) {\n" +
                    "                                result.append(\",\");\n" +
                    "                            }\n" +
                    "\n" +
                    "                            result.append(current.val);\n" +
                    "                            current = current.next;\n" +
                    "                        }\n" +
                    "\n" +
                    "                        result.append(\"]\");\n" +
                    "                        Files.writeString(\n" +
                    "                                resultPath,\n" +
                    "                                result.toString(),\n" +
                    "                                StandardCharsets.UTF_8\n" +
                    "                        );\n" +
                    "                    }");
        }
        if (config.contains("TreeNode") || "TreeNode".equals(outputType)) {
            s.append("""

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

                        private static void writeTreeNodeResult(Path resultPath, TreeNode root) throws Exception {
                            Files.writeString(
                                    resultPath,
                                    serializeTreeNode(root),
                                    StandardCharsets.UTF_8
                            );
                        }

                        private static String serializeTreeNode(TreeNode root) {
                            if (root == null) {
                                return "[]";
                            }
                            List<String> values = new ArrayList<>();
                            Queue<TreeNode> queue = new LinkedList<>();
                            queue.offer(root);
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
        return s.toString();
    }

}
