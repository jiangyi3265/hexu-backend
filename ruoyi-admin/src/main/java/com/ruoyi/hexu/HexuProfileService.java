package com.ruoyi.hexu;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

/** A member's personal details belong to the account, rather than a shop. */
@Service
public class HexuProfileService {
    private static final Set<String> FIELDS=new HashSet<>(Arrays.asList("name","phone","gender","birthday","region","signature","avatarId"));
    private final HexuStore db;
    public HexuProfileService(HexuStore db){this.db=db;}

    public Map<String,Object> read(Actor a){
        db.member(a);
        Map<String,Object> member=db.one("select name,phone from hx_member where id=?",a.id);
        Map<String,Object> doc=current(a.id);
        Map<String,Object> body=doc==null?Collections.emptyMap():db.parse(doc.get("body_json"));
        String gender=legacyString(body,"gender","性别"),birthday=legacyString(body,"birthday","生日");
        if(!validGender(gender))gender="";
        if(!validBirthday(birthday))birthday="";
        Object storedRegion=body.containsKey("region")?body.get("region"):body.get("所在地区");
        List<String> region=legacyRegion(storedRegion);
        String signature=legacyString(body,"signature","个性签名");
        if(length(signature)>100)signature="";
        String avatarId=legacyString(body,"avatarId","");
        if(!avatarId.isEmpty()&&db.optional("select id from hx_attachment where id=? and member_id=? and purpose='AVATAR'",avatarId,a.id)==null)avatarId="";
        return map("name",str(member,"name"),"phone",str(member,"phone"),"gender",gender,"birthday",birthday,
            "region",region,"signature",signature,"avatarId",avatarId);
    }

    public Map<String,Object> managementRead(Actor manager,long shop,long memberId){
        db.manage(manager,shop,"OPERATOR");
        require(db.optional("select member_id from hx_binding where shop_id=? and member_id=?",shop,memberId)!=null
            ||db.optional("select member_id from hx_agent where shop_id=? and member_id=? and status='ACTIVE'",shop,memberId)!=null,"无权查看其他商城会员资料");
        Map<String,Object> profile=new LinkedHashMap<>(read(new Actor(memberId,false,false)));
        String phone=str(profile,"phone");
        Map<String,Object> member=map("id",memberId,"name",profile.get("name"),"phone",phone.matches("1[3-9][0-9]{9}")?phone.substring(0,3)+"****"+phone.substring(7):"");
        profile.remove("phone");
        return map("member",member,"profile",profile);
    }

    @Transactional
    public Map<String,Object> authorizePhone(Actor a,String code,WechatIdentityGateway gateway){
        db.member(a);
        require(code!=null&&!code.trim().isEmpty()&&code.length()<=256,"请重新获取微信手机号授权凭证");
        require(db.optional("select member_id from hx_wechat_account where member_id=?",a.id)!=null,"请先通过微信登录，再授权手机号");
        Map<String,Object> verified=gateway.authorizedPhone(code);
        String phone=str(verified,"phone");
        require(phone.matches("1[3-9][0-9]{9}"),"手机号授权结果无效");
        // Serialize phone claims using a stable database row; existing data and constraints are retained.
        db.one("select id from hx_member order by id limit 1 for update");
        db.one("select id from hx_member where id=? for update",a.id);
        require(db.optional("select id from hx_member where phone=? and id<>?",phone,a.id)==null,"此手机号已绑定其他账号，请先处理原账号");
        db.jdbc().update("update hx_member set phone=?,authorized=true where id=?",phone,a.id);
        db.audit(a,0,"PHONE_CONSENT",String.valueOf(a.id),null,map("consent",true,"source","WECHAT_PHONE_CODE"));
        return read(a);
    }

    @Transactional
    public Map<String,Object> save(Actor a,Map<String,Object> input){
        db.member(a);
        // This lock makes first save, migration and repeated saves one account-wide operation.
        db.one("select id from hx_member where id=? for update",a.id);
        require(input!=null,"请提交个人资料");
        for(String key:input.keySet())require(FIELDS.contains(key),"个人资料字段不支持："+key);
        Map<String,Object> before=read(a),body=new LinkedHashMap<>(before);
        if(input.containsKey("phone"))require(input.get("phone") instanceof String&&Objects.equals(input.get("phone"),before.get("phone")),"手机号须通过微信授权更新，不能在资料中修改");
        for(String key:FIELDS)if(input.containsKey(key)&&!"phone".equals(key))body.put(key,input.get(key));
        String name=text(body,"name"),gender=text(body,"gender"),birthday=text(body,"birthday"),signature=text(body,"signature"),avatarId=text(body,"avatarId");
        require(!hasControl(name),"昵称不能包含换行或控制字符");
        name=HexuText.trim(name);
        require(length(name)>=1&&length(name)<=20&&!hasControl(name),"昵称须为1至20个字符");
        require(validGender(gender),"性别请选择女、男或不透露");
        require(validBirthday(birthday),"生日须为有效日期且不能晚于今天");
        require(length(signature)<=100&&!signature.contains("\u0000"),"个性签名最多100个字符");
        require(body.get("region") instanceof List,"所在地区须通过地区选择器提交");
        List<?> values=(List<?>)body.get("region");
        require(values.isEmpty()||values.size()==2||values.size()==3,"请选择省、市和区县");
        List<String> region=new ArrayList<>();
        for(Object value:values){require(value instanceof String&&validRegionName((String)value),"所在地区名称不合法");region.add((String)value);}
        require(region.isEmpty()||HexuRegions.valid(region),"请选择有效的省、市、区");
        require(avatarId.isEmpty()||db.optional("select id from hx_attachment where id=? and member_id=? and purpose='AVATAR'",avatarId,a.id)!=null,"头像不存在或无权使用他人头像");
        Map<String,Object> saved=map("name",name,"gender",gender,"birthday",birthday,"region",region,"signature",signature,"avatarId",avatarId);
        Map<String,Object> doc=db.optional("select * from hx_document where member_id=? and shop_id=0 and kind='profile' and status='ACTIVE' order by updated_at desc,id desc limit 1",a.id);
        String documentId=doc==null?id("PROFILE"):str(doc,"id");
        if(doc==null)db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values(?,0,?,'profile','ACTIVE',?)",documentId,a.id,db.json(saved));
        else db.jdbc().update("update hx_document set body_json=?,updated_at=CURRENT_TIMESTAMP where id=?",db.json(saved),documentId);
        // Keep earlier per-shop details as history while preventing a stale second profile.
        db.jdbc().update("update hx_document set status='ARCHIVED',updated_at=CURRENT_TIMESTAMP where member_id=? and kind='profile' and status='ACTIVE' and id<>?",a.id,documentId);
        db.jdbc().update("update hx_member set name=? where id=?",name,a.id);
        db.audit(a,0,"SAVE_profile",documentId,before,saved);
        return read(a);
    }

    /** Compatibility for old document-save clients, with exactly the same validation. */
    public Map<String,Object> saveLegacy(Actor a,long shop,Map<String,Object> input){
        if(!str(input,"id").isEmpty())require(db.optional("select id from hx_document where id=? and member_id=? and kind='profile'",str(input,"id"),a.id)!=null,"无权修改其他用户数据");
        Map<String,Object> normalized=new LinkedHashMap<>();
        for(String key:FIELDS)if(input.containsKey(key))normalized.put(key,input.get(key));
        String[][] aliases={{"name","昵称"},{"gender","性别"},{"birthday","生日"},{"region","所在地区"},{"signature","个性签名"}};
        for(String[] alias:aliases)if(!normalized.containsKey(alias[0])&&input.containsKey(alias[1]))normalized.put(alias[0],input.get(alias[1]));
        if(normalized.get("region") instanceof String){String region=(String)normalized.get("region");normalized.put("region",region.trim().isEmpty()?Collections.emptyList():Arrays.asList(region.trim().split("\\s+")));}
        save(a,normalized);
        return db.one("select * from hx_document where member_id=? and shop_id=0 and kind='profile' and status='ACTIVE' order by updated_at desc,id desc limit 1",a.id);
    }

    private Map<String,Object> current(long member){
        return db.optional("select * from hx_document where member_id=? and kind='profile' and status='ACTIVE' order by case when shop_id=0 then 0 else 1 end,updated_at desc,id desc limit 1",member);
    }
    private static String text(Map<String,Object> body,String key){Object value=body.get(key);require(value instanceof String,"个人资料字段须为文本："+key);return (String)value;}
    private static String legacyString(Map<String,Object> body,String key,String alias){Object value=body.containsKey(key)?body.get(key):body.get(alias);return value instanceof String?(String)value:"";}
    private static int length(String value){return value.codePointCount(0,value.length());}
    private static boolean hasControl(String value){return value.codePoints().anyMatch(Character::isISOControl);}
    private static boolean validGender(String value){return Arrays.asList("","女","男","不透露").contains(value);}
    private static boolean validBirthday(String value){
        if(value.isEmpty())return true;
        if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))return false;
        try{LocalDate day=LocalDate.parse(value);return day.getYear()>=1900&&!day.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")));}catch(DateTimeParseException e){return false;}
    }
    private static boolean validRegionName(String name){return name.matches("[\\u3400-\\u9fff·]{2,30}");}
    private static List<String> legacyRegion(Object value){
        if(value instanceof String){String raw=((String)value).trim();value=raw.isEmpty()?Collections.emptyList():Arrays.asList(raw.split("\\s+"));}
        if(!(value instanceof List))return Collections.emptyList();
        List<?> values=(List<?>)value;
        if(!values.isEmpty()&&values.size()!=2&&values.size()!=3)return Collections.emptyList();
        List<String> result=new ArrayList<>();
        for(Object item:values){if(!(item instanceof String)||!validRegionName((String)item))return Collections.emptyList();result.add((String)item);}
        return result;
    }
}
