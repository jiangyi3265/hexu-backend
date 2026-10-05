package com.ruoyi.hexu;

/** Text trimming only; each business entry point keeps its own type/control/length rules. */
final class HexuText {
    private HexuText(){}
    static String trim(String value){
        int start=0,end=value.length();
        while(start<end){int point=value.codePointAt(start);if(!Character.isWhitespace(point)&&!Character.isSpaceChar(point)&&point!=0xfeff)break;start+=Character.charCount(point);}
        while(end>start){int point=value.codePointBefore(end);if(!Character.isWhitespace(point)&&!Character.isSpaceChar(point)&&point!=0xfeff)break;end-=Character.charCount(point);}
        return value.substring(start,end);
    }
}
