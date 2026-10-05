package com.ruoyi.hexu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

/** H2 test-only equivalents of the MySQL JSON string functions used in queries. */
public final class HexuTestJson {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private HexuTestJson(){}
    static void register(JdbcTemplate jdbc){
        jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'com.ruoyi.hexu.HexuTestJson.extract'");
        jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'com.ruoyi.hexu.HexuTestJson.unquote'");
    }
    public static String extract(String json,String path)throws Exception{
        if(json==null)return null;
        if(!path.matches("\\$\\.[A-Za-z][A-Za-z0-9]*"))throw new IllegalArgumentException("Unsupported test JSON path");
        JsonNode node=MAPPER.readTree(json).get(path.substring(2));return node==null?null:node.toString();
    }
    public static String unquote(String json)throws Exception{return json==null?null:MAPPER.readTree(json).asText();}
}
