package org.example.servicejudge.Util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class CodeBuild {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern TOP_LEVEL_LIST_NODE = Pattern.compile(
            "(?m)^(?:public\\s+)?class\\s+ListNode\\b"
    );

    public static String build(String code,String paserConfig) throws JsonProcessingException {
        StringBuilder sb=new StringBuilder();
        if(!code.contains("import java.util.*;")&&!code.contains("import  java.util.*;"))
        {
        sb.append("import java.util.*;\n");}
        if(!code.contains("import java.lang.*;")&&!code.contains("import  java.lang.*;")){
        sb.append("import java.lang.*;\n");}
        sb.append(code);
        if (!containsTopLevelListNode(code)) {
            sb.append(formatClass(paserConfig));
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
                    "int []"+" nodeList = objectMapper.readValue(reader.readLine(), int[].class);\n"+
                            "ListNode "+name+" = buildListForListNode(nodeList);\n";
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
            s.append("class ListNode {\n" +
                    "    int val;\n" +
                    "    ListNode next;\n" +
                    "\n" +
                    "    ListNode(int val) {\n" +
                    "        this.val = val;\n" +
                    "    }\n" +
                    "}\n");
        }
        return s.toString();
    }

    private static boolean containsTopLevelListNode(String code) {
        return code != null && TOP_LEVEL_LIST_NODE.matcher(code).find();
    }

    public static String formatNodeMethod(String parseConfig) throws JsonProcessingException {
        List<String> config = getType(parseConfig);
        StringBuilder s = new StringBuilder();
        if (config.contains("ListNode")) {
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
        return s.toString();
    }

}
