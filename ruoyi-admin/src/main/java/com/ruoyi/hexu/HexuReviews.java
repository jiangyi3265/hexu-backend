package com.ruoyi.hexu;

import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

/** Public review data is built from approved documents and explicit image bindings. */
final class HexuReviews {
    private final HexuStore db;
    HexuReviews(HexuStore db){this.db=db;}

    Map<String,Object> original(Actor actor,long shop,Map<String,Object> input,Map<String,Object> old){
        require(input.get("orderId") instanceof String&&input.get("skuId") instanceof String,"请选择真实订单与商品");
        String order=str(input,"orderId"),sku=str(input,"skuId");
        if(old!=null)require(samePurchase(input,db.parse(old.get("body_json"))),"不能更换评价的订单或商品");
        completedOrder(actor.id,shop,order,sku);
        long rating=number(input,"rating");require(rating>=1&&rating<=5,"评分须为1至5分");
        String content=content(input);
        for(Map<String,Object> prior:db.jdbc().queryForList("select id,body_json from hx_document where shop_id=? and member_id=? and kind='review' and status<>'REJECTED' for update",shop,actor.id)){
            if(old!=null&&str(old,"id").equals(str(prior,"id")))continue;
            Map<String,Object> body=db.parse(prior.get("body_json"));require(!order.equals(str(body,"orderId"))||!sku.equals(str(body,"skuId")),"该商品已经评价");
        }
        return map("orderId",order,"skuId",sku,"rating",rating,"content",content,"anonymous",bool(input,"anonymous"),"uploads",uploads(actor.id,shop,input));
    }

    Map<String,Object> append(Actor actor,long shop,Map<String,Object> input,Map<String,Object> old){
        require(input.get("parentId") instanceof String&&!str(input,"parentId").isEmpty(),"请先选择已公开的原评价");
        Map<String,Object> parent=db.one("select * from hx_document where id=? and shop_id=? and kind='review' and status='APPROVED' for update",input.get("parentId"),shop);
        require(number(parent,"member_id")==actor.id,"只能追加自己的评价");
        Map<String,Object> body=db.parse(parent.get("body_json"));
        require(input.get("skuId") instanceof String&&str(input,"skuId").equals(str(body,"skuId")),"追评商品与原评价不一致");
        if(input.containsKey("orderId"))require(str(input,"orderId").equals(str(body,"orderId")),"追评订单与原评价不一致");
        if(old!=null)require(str(db.parse(old.get("body_json")),"parentId").equals(str(parent,"id")),"不能更换追评的原评价");
        completedOrder(actor.id,shop,str(body,"orderId"),str(body,"skuId"));
        for(Map<String,Object> prior:db.jdbc().queryForList("select id,body_json from hx_document where shop_id=? and member_id=? and kind='review_append' and status<>'REJECTED' for update",shop,actor.id)){
            if(old!=null&&str(old,"id").equals(str(prior,"id")))continue;
            require(!str(parent,"id").equals(str(db.parse(prior.get("body_json")),"parentId")),"该评价已有进行中或已公开的追评");
        }
        return map("parentId",parent.get("id"),"orderId",body.get("orderId"),"skuId",body.get("skuId"),"content",content(input),"anonymous",bool(body,"anonymous"),"uploads",uploads(actor.id,shop,input));
    }

    void approve(Map<String,Object> document){
        long member=number(document,"member_id"),shop=number(document,"shop_id");Map<String,Object> body=db.parse(document.get("body_json"));
        completedOrder(member,shop,str(body,"orderId"),str(body,"skuId"));content(body);uploads(member,shop,body);
        if("review_append".equals(str(document,"kind"))){
            Map<String,Object> parent=db.one("select * from hx_document where id=? and shop_id=? and member_id=? and kind='review' and status='APPROVED'",str(body,"parentId"),shop,member);
            Map<String,Object> original=db.parse(parent.get("body_json"));require(samePurchase(body,original),"追评与原评价的订单或商品不一致");
        }else for(Map<String,Object> prior:db.jdbc().queryForList("select id,body_json from hx_document where shop_id=? and member_id=? and kind='review' and status='APPROVED' for update",shop,member))
            require(str(prior,"id").equals(str(document,"id"))||!samePurchase(body,db.parse(prior.get("body_json"))),"该商品评价已经审核，不能重复赠送积分");
    }

    List<Map<String,Object>> list(Actor requester,long shop,String sku){
        db.shop(shop);if(requester!=null)db.member(requester);
        List<Map<String,Object>> appends=db.jdbc().queryForList("select id,member_id,status,body_json,created_at,updated_at from hx_document where shop_id=? and kind='review_append' and JSON_UNQUOTE(JSON_EXTRACT(body_json,'$.skuId'))=? order by case when status='REJECTED' then 1 else 0 end,updated_at desc,id desc",shop,sku);
        Map<String,List<Map<String,Object>>> byParent=new HashMap<>();
        for(Map<String,Object> append:appends)byParent.computeIfAbsent(str(db.parse(append.get("body_json")),"parentId"),key->new ArrayList<>()).add(append);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> document:db.jdbc().queryForList("select d.id,d.member_id,d.body_json,d.created_at,m.name from hx_document d join hx_member m on m.id=d.member_id where d.shop_id=? and d.kind='review' and d.status='APPROVED' and JSON_UNQUOTE(JSON_EXTRACT(d.body_json,'$.skuId'))=? order by d.created_at desc,d.id desc limit 500",shop,sku))
            result.add(publicReview(requester,shop,document,byParent.getOrDefault(str(document,"id"),Collections.emptyList())));
        return result;
    }

    /** An authenticated purchase context must not depend on public/list page caps. */
    Map<String,Object> context(Actor requester,long shop,String order,String sku,String parentId){
        db.member(requester);db.shop(shop);
        Map<String,Object> purchase=purchase(requester.id,shop,order,sku,false);
        boolean eligible="COMPLETED".equals(str(purchase,"status"))&&number(purchase,"refunded")==0;
        Map<String,Object> original=submission(requester.id,shop,"review",order,sku,null),parent=null,appended=null;
        if(parentId!=null&&!parentId.isEmpty()){
            Map<String,Object> document=db.optional("select d.id,d.member_id,d.body_json,d.created_at,m.name from hx_document d join hx_member m on m.id=d.member_id where d.id=? and d.shop_id=? and d.member_id=? and d.kind='review' and d.status='APPROVED' and JSON_UNQUOTE(JSON_EXTRACT(d.body_json,'$.orderId'))=? and JSON_UNQUOTE(JSON_EXTRACT(d.body_json,'$.skuId'))=?",parentId,shop,requester.id,order,sku);
            require(document!=null,"原评价不存在、未公开或不属于该订单商品");
            List<Map<String,Object>> children=db.jdbc().queryForList("select id,member_id,status,body_json,created_at,updated_at from hx_document where shop_id=? and member_id=? and kind='review_append' and JSON_UNQUOTE(JSON_EXTRACT(body_json,'$.parentId'))=? order by case when status='REJECTED' then 1 else 0 end,updated_at desc,id desc",shop,requester.id,parentId);
            parent=publicReview(requester,shop,document,children);
            appended=submission(requester.id,shop,"review_append",order,sku,parentId);
        }
        return map("shopId",shop,"orderId",order,"skuId",sku,"reviewAllowed",eligible&&(original==null||"SUPPLEMENT".equals(str(original,"status"))),"parent",parent,"submission",original,"appendSubmission",appended);
    }

    private Map<String,Object> submission(long member,long shop,String kind,String order,String sku,String parentId){
        String sql="select id,status,review_note,body_json from hx_document where shop_id=? and member_id=? and kind=? and status<>'REJECTED' and JSON_UNQUOTE(JSON_EXTRACT(body_json,'$.orderId'))=? and JSON_UNQUOTE(JSON_EXTRACT(body_json,'$.skuId'))=?";
        List<Object> args=new ArrayList<>(Arrays.asList(shop,member,kind,order,sku));
        if(parentId!=null){sql+=" and JSON_UNQUOTE(JSON_EXTRACT(body_json,'$.parentId'))=?";args.add(parentId);}
        Map<String,Object> document=db.optional(sql+" order by updated_at desc,id desc limit 1",args.toArray());if(document==null)return null;
        Map<String,Object> raw=db.parse(document.get("body_json")),body=new LinkedHashMap<>();
        for(String key:Arrays.asList("orderId","skuId","parentId","rating","content","anonymous","uploads"))if(raw.containsKey(key))body.put(key,raw.get(key));
        return map("id",document.get("id"),"status",document.get("status"),"review_note",str(document,"review_note"),"body",body);
    }

    private Map<String,Object> publicReview(Actor requester,long shop,Map<String,Object> document,List<Map<String,Object>> appends){
        Map<String,Object> body=db.parse(document.get("body_json"));String name=str(document,"name");long member=number(document,"member_id");boolean mine=requester!=null&&requester.id==member;
        List<Map<String,Object>> approved=new ArrayList<>();Map<String,Object> latest=null;boolean hasAppend=false;
        for(Map<String,Object> append:appends){
            Map<String,Object> appended=db.parse(append.get("body_json"));if(number(append,"member_id")!=member||!samePurchase(body,appended))continue;
            if(latest==null)latest=append;if(!"REJECTED".equals(str(append,"status")))hasAppend=true;
            if("APPROVED".equals(str(append,"status")))approved.add(map("id",append.get("id"),"content",appended.get("content"),"images",publicImages(shop,member,appended),"createdAt",append.get("created_at")));
        }
        Map<String,Object> purchase=mine?db.optional("select o.id,l.sku_id from hx_order o join hx_order_line l on l.order_id=o.id where o.id=? and o.shop_id=? and o.buyer_id=? and o.status='COMPLETED' and o.refunded=0 and l.sku_id=?",str(body,"orderId"),shop,member,str(body,"skuId")):null;
        boolean eligible=purchase!=null&&str(body,"orderId").equals(str(purchase,"id"))&&str(body,"skuId").equals(str(purchase,"sku_id"));
        Map<String,Object> review=map("id",document.get("id"),"skuId",body.get("skuId"),"name",bool(body,"anonymous")?"匿名用户":name.isEmpty()?"会员":name.substring(0,name.offsetByCodePoints(0,1))+"**",
            "rating",body.get("rating"),"content",body.get("content"),"images",publicImages(shop,member,body),"createdAt",document.get("created_at"),"appends",approved,"mine",mine,
            "appendAllowed",eligible&&!hasAppend,"appendSupplementAllowed",eligible&&latest!=null&&"SUPPLEMENT".equals(str(latest,"status")));
        if(mine){review.put("orderId",body.get("orderId"));review.put("appendStatus",latest==null?null:latest.get("status"));review.put("appendId",latest==null?null:latest.get("id"));}
        return review;
    }

    boolean publishedImage(Map<String,Object> attachment){
        long shop=number(attachment,"shop_id"),member=number(attachment,"member_id");String image=str(attachment,"id");
        for(Map<String,Object> document:db.jdbc().queryForList("select kind,body_json from hx_document where shop_id=? and member_id=? and kind in ('review','review_append') and status='APPROVED'",shop,member)){
            Map<String,Object> body=db.parse(document.get("body_json"));Object images=body.get("uploads");if(!(images instanceof List)||!((List<?>)images).contains(image))continue;
            if("review".equals(str(document,"kind")))return true;
            Map<String,Object> parent=db.optional("select body_json from hx_document where id=? and shop_id=? and member_id=? and kind='review' and status='APPROVED'",str(body,"parentId"),shop,member);
            if(parent!=null&&samePurchase(body,db.parse(parent.get("body_json"))))return true;
        }
        return false;
    }

    private void completedOrder(long member,long shop,String order,String sku){
        Map<String,Object> purchase=purchase(member,shop,order,sku,true);
        require("COMPLETED".equals(str(purchase,"status"))&&number(purchase,"refunded")==0,"只能评价本人已完成且未退款的订单");
    }
    private Map<String,Object> purchase(long member,long shop,String order,String sku,boolean lock){
        Map<String,Object> purchase=db.one("select id,status,refunded from hx_order where id=? and buyer_id=? and shop_id=?"+(lock?" for update":""),order,member,shop);
        require(order.equals(str(purchase,"id")),"请使用订单的真实编号");
        Map<String,Object> line=db.one("select sku_id from hx_order_line where order_id=? and sku_id=?",order,sku);
        require(sku.equals(str(line,"sku_id")),"请选择订单中的真实商品编号");
        return purchase;
    }
    private String content(Map<String,Object> input){
        require(input.get("content") instanceof String,"请填写评价内容");String raw=(String)input.get("content");
        require(!raw.codePoints().anyMatch(point->Character.isISOControl(point)&&point!='\n'&&point!='\r'&&point!='\t'),"评价内容不能包含非法控制字符");
        String content=HexuText.trim(raw);require(!content.isEmpty()&&content.codePointCount(0,content.length())<=500,"评价内容须为1至500个字符");return content;
    }
    private List<String> uploads(long member,long shop,Map<String,Object> input){
        Object raw=input.getOrDefault("uploads",Collections.emptyList());require(raw instanceof List&&((List<?>)raw).size()<=9,"评价图片最多9张");
        List<String> images=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Object image:(List<?>)raw){require(image instanceof String&&seen.add((String)image),"评价图片格式错误或重复");require(db.optional("select id from hx_attachment where id=? and shop_id=? and member_id=? and purpose='REVIEW'",image,shop,member)!=null,"评价图片须为本人在本商城上传的评价图片");images.add((String)image);}
        return images;
    }
    private List<String> publicImages(long shop,long member,Map<String,Object> body){
        List<String> result=new ArrayList<>();Object raw=body.get("uploads");if(!(raw instanceof List))return result;
        for(Object image:(List<?>)raw)if(image instanceof String&&db.optional("select id from hx_attachment where id=? and shop_id=? and member_id=? and purpose='REVIEW'",image,shop,member)!=null){String url="/hexu/app/attachments/review/"+image;if(!result.contains(url))result.add(url);}
        return result;
    }
    private static boolean samePurchase(Map<String,Object> first,Map<String,Object> second){return str(first,"skuId").equals(str(second,"skuId"))&&str(first,"orderId").equals(str(second,"orderId"));}
}
