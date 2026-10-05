package com.ruoyi.hexu;

import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

/** Validates association only; attachment read permissions remain separate. */
final class HexuAttachmentAssociation {
    private HexuAttachmentAssociation(){}
    static List<String> retained(HexuStore db,Map<String,Object> old,long shop,String kind,String field){
        if(old==null)return Collections.emptyList();
        require(number(old,"shop_id")==shop&&kind.equals(str(old,"kind")),"附件来源文档与当前业务不一致");
        Map<String,Object> body=db.parse(old.get("body_json"));Object value=body.get(field);
        List<String> result=new ArrayList<>();
        if(value instanceof List)for(Object file:(List<?>)value){if(file instanceof String)result.add((String)file);}
        else if(!"uploads".equals(field)&&value instanceof String)result.add((String)value);
        if("uploads".equals(field)&&Arrays.asList("decoration","page_schema").contains(kind))result.addAll(images(body));
        return result;
    }
    static void verify(HexuStore db,Actor actor,long shop,String file,Collection<String> retained){
        Map<String,Object> attachment=db.optional("select member_id from hx_attachment where id=? and shop_id=?",file,shop);
        require(attachment!=null&&(number(attachment,"member_id")==actor.id||retained.contains(file)),"附件不存在或无权关联他人资料");
    }
    static void uploads(HexuStore db,Actor actor,long shop,String kind,Map<String,Object> body,Map<String,Object> old,boolean management){
        if(!body.containsKey("uploads"))return;
        require(body.get("uploads") instanceof List,"附件列表格式错误");
        List<?> files=(List<?>)body.get("uploads");require(files.size()<=9,"最多9份附件");
        List<String> bound=management?retained(db,old,shop,kind,"uploads"):Collections.emptyList();
        Set<String> seen=new HashSet<>();
        for(Object file:files){require(file instanceof String&&!((String)file).isEmpty()&&seen.add((String)file),"附件不能重复或为空");verify(db,actor,shop,(String)file,bound);}
    }
    static void storefront(HexuStore db,Actor actor,long shop,String kind,Map<String,Object> body,Map<String,Object> old){
        if(!Arrays.asList("decoration","page_schema").contains(kind))return;
        List<String> bound=retained(db,old,shop,kind,"uploads");
        for(String image:images(body)){
            require(db.optional("select id from hx_attachment where id=? and shop_id=? and purpose='STOREFRONT'",image,shop)!=null,"装修图片须为本商城上传的装修图片");
            verify(db,actor,shop,image,bound);
        }
    }
    private static Set<String> images(Object value){
        Set<String> result=new LinkedHashSet<>();collectImages(value,result);return result;
    }
    private static void collectImages(Object value,Set<String> result){
        if(value instanceof List)for(Object item:(List<?>)value)collectImages(item,result);
        else if(value instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()){
            String key=String.valueOf(entry.getKey());Object item=entry.getValue();
            if(("asset".equals(key)||"image".equals(key))&&item instanceof String&&((String)item).startsWith("FILE"))result.add((String)item);
            if("assets".equals(key)&&item instanceof Map)for(Object asset:((Map<?,?>)item).values())if(asset instanceof String&&((String)asset).startsWith("FILE"))result.add((String)asset);
            collectImages(item,result);
        }
    }
}
