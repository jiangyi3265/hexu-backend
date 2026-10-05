package com.ruoyi.hexu;
import java.util.*;
import java.util.regex.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
/** Templates affect new in-app messages only; no unconfigured external channel is claimed as sent. */
public final class HexuNotifications {
 private final HexuStore db;public HexuNotifications(HexuStore d){db=d;}
 public static final List<String> EVENTS=Arrays.asList("ORDER_PAID","ORDER_SHIPPED","ORDER_COMPLETED","REFUND_UPDATED","WITHDRAW_UPDATED","INVENTORY_LOW","POINTS_UPDATED","AGENT_UPDATED","SYSTEM");
 private static final Set<String> AGENT_REVIEW_KINDS=new HashSet<>(Arrays.asList("agent_application","migration","promotion","downgrade","shop_application","cross_purchase","shop_transfer","link_relation"));
 private static final Set<String> OTHER_REVIEW_KINDS=new HashSet<>(Arrays.asList("review","review_append","support","settlement_account","stocktake"));
 public static String reviewEvent(String kind){return AGENT_REVIEW_KINDS.contains(kind)?"AGENT_UPDATED":"SYSTEM";}
 public static void validate(Map<String,Object>b){require(EVENTS.contains(str(b,"event")),"消息触发事件无效");require(str(b,"content").trim().length()>0&&str(b,"content").length()<=500,"通知内容须为1至500字");require(str(b,"title").length()<=80,"消息标题不能超过80字");Matcher m=Pattern.compile("\\{\\{(.*?)\\}\\}").matcher(str(b,"content")+str(b,"title"));while(m.find())require(Arrays.asList("shop","reference","message").contains(m.group(1)),"模板仅支持shop、reference、message变量");}
 public static String classify(String title){if(title.contains("退款")||title.contains("售后")||title.contains("换货"))return "REFUND_UPDATED";if(title.contains("提现")||title.contains("付款失败"))return "WITHDRAW_UPDATED";if(title.contains("付款成功")||title.contains("支付成功"))return "ORDER_PAID";if(title.contains("发货")||title.contains("已发出"))return "ORDER_SHIPPED";if(title.contains("订单完成"))return "ORDER_COMPLETED";if(title.contains("积分")||title.contains("转赠")||title.contains("冻结"))return "POINTS_UPDATED";if(title.contains("职级")||title.contains("申请")||title.contains("归属")||title.contains("考核"))return "AGENT_UPDATED";return "SYSTEM";}
 public void emit(long shop,long member,String event,String title,String ref){Map<String,Object>settings=db.config(shop,"system_parameter");String preference=mapPreference(event);if(!preference.isEmpty()&&settings.containsKey(preference)&&!bool(settings,preference))return;String content=title;Map<String,Object>chosen=null;for(Map<String,Object>d:db.jdbc().queryForList("select body_json from hx_document where shop_id=? and kind='notification_template' and status='ACTIVE' order by created_at desc,id desc limit 200",shop)){Map<String,Object>b=db.parse(d.get("body_json"));if(event.equals(str(b,"event"))){chosen=b;break;}}if(chosen!=null){Map<String,Object>s=db.optional("select name from hx_shop where id=?",shop);String shopName=s==null?"平台":str(s,"name");content=render(str(chosen,"content"),shopName,ref,title);if(!str(chosen,"title").isEmpty())title=render(str(chosen,"title"),shopName,ref,title);}if(title.length()>160)title=title.substring(0,160);if(content.length()>2000)content=content.substring(0,2000);db.jdbc().update("insert into hx_message(shop_id,member_id,title,reference_id,event_type,body_text) values(?,?,?,?,?,?)",shop,member,title,ref,event,content);}
 private String render(String text,String shop,String ref,String message){return text.replace("{{shop}}",shop).replace("{{reference}}",ref==null?"":ref).replace("{{message}}",message);}
 private String mapPreference(String e){switch(e){case "ORDER_PAID":return "notifyPaid";case "ORDER_SHIPPED":return "notifyShipped";case "REFUND_UPDATED":return "notifyRefund";case "WITHDRAW_UPDATED":return "notifyWithdrawal";case "INVENTORY_LOW":return "notifyInventory";default:return "";}}
 public List<Map<String,Object>> list(Actor a,long shop){db.member(a);List<Map<String,Object>> rows=db.jdbc().queryForList("select m.*,d.kind reference_kind from hx_message m left join hx_document d on d.id=m.reference_id and d.shop_id=m.shop_id and d.member_id=m.member_id where m.member_id=? and m.shop_id in (0,?) order by m.id desc limit 100",a.id,shop);for(Map<String,Object>row:rows)normalizeReviewEvent(row);return rows;}
 public Map<String,Object> read(Actor a,long id){db.member(a);Map<String,Object>m=db.one("select m.*,d.kind reference_kind from hx_message m left join hx_document d on d.id=m.reference_id and d.shop_id=m.shop_id and d.member_id=m.member_id where m.id=? and m.member_id=?",id,a.id);normalizeReviewEvent(m);db.jdbc().update("update hx_message set is_read=true where id=? and member_id=?",id,a.id);m.put("is_read",true);return m;}
 private static void normalizeReviewEvent(Map<String,Object>message){
  String kind=str(message,"reference_kind");
  if(!AGENT_REVIEW_KINDS.contains(kind)&&!OTHER_REVIEW_KINDS.contains(kind))return;
  message.put("event_type",reviewEvent(kind));
  String oldTitle=str(message,"title");
  if(!Arrays.asList("review","review_append","support").contains(kind)||!oldTitle.startsWith("申请处理结果："))return;
  String decision=oldTitle.substring("申请处理结果：".length());
  if(!Arrays.asList("APPROVED","REJECTED","SUPPLEMENT").contains(decision))return;
  String title=HexuWorkflowService.reviewNotificationTitle(kind,decision);
  message.put("title",title);
  if(oldTitle.equals(str(message,"body_text")))message.put("body_text",title);
 }
}
