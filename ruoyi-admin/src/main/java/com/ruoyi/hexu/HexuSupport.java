package com.ruoyi.hexu;

import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

/** Member support input is canonicalized separately from private attachment access. */
final class HexuSupport {
    private final HexuStore db;
    private static final Set<String> QUESTIONS=new HashSet<>(Arrays.asList("订单问题","商品咨询","售后服务","账户与代理","活动咨询","其他问题"));
    HexuSupport(HexuStore db){this.db=db;}

    Map<String,Object> prepare(Actor actor,long shop,Map<String,Object> input){
        db.shop(shop);
        require(input.get("question") instanceof String,"请选择留言问题类型");
        String question=HexuText.trim((String)input.get("question"));require(QUESTIONS.contains(question),"请选择有效的留言问题类型");
        require(input.get("message") instanceof String,"留言内容须为文本");
        String raw=(String)input.get("message");require(!raw.codePoints().anyMatch(point->Character.isISOControl(point)&&point!='\n'&&point!='\r'&&point!='\t'),"留言内容不能包含非法控制字符");
        String message=HexuText.trim(raw);require(!message.isEmpty()&&message.codePointCount(0,message.length())<=500,"留言内容须为1至500个字符");
        String canonical=orderKey(input,"orderId"),legacy=orderKey(input,"关联订单");
        require(!input.containsKey("orderId")||!input.containsKey("关联订单")||canonical.equals(legacy),"关联订单字段不一致");
        String order=input.containsKey("orderId")?canonical:legacy;
        if(!order.isEmpty()){
            Map<String,Object> purchase=db.optional("select id from hx_order where id=? and buyer_id=? and shop_id=?",order,actor.id,shop);
            require(purchase!=null&&order.equals(str(purchase,"id")),"本人本商城订单不存在");
        }
        // The common association validator already checks list shape, count and ownership.
        List<?> uploads=input.containsKey("uploads")?new ArrayList<>((List<?>)input.get("uploads")):Collections.emptyList();
        for(Object file:uploads)require(db.optional("select id from hx_attachment where id=? and shop_id=? and member_id=? and purpose='SUPPORT'",file,shop,actor.id)!=null,"客服图片须为本人在本商城上传的客服图片");
        return map("question",question,"message",message,"orderId",order,"uploads",uploads);
    }

    private static String orderKey(Map<String,Object> input,String key){
        if(!input.containsKey(key))return "";
        require(input.get(key) instanceof String,"关联订单须为文本，未关联可留空");
        String value=(String)input.get(key);require(!value.codePoints().anyMatch(Character::isISOControl),"关联订单不能包含控制字符");
        return HexuText.trim(value);
    }
}
